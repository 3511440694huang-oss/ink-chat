package com.ink.chat.data.fonts

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 自定义字体管理（M5.7）。
 * 导入：SAF URI → filesDir/fonts/<安全文件名>；校验 = 扩展名白名单（ttf / otf / ttc）+ 文件头魔数；
 * 应用：设置页选择 fontId（[SYSTEM] 或文件名），InkTheme 从文件构建 FontFamily 全局生效。
 */
class FontManager(private val context: Context) {

    sealed class ImportResult {
        data class Ok(val fileName: String) : ImportResult()

        /** 非字体文件（扩展名或文件头校验不通过） */
        object Unsupported : ImportResult()
        object TooBig : ImportResult()
        object Failed : ImportResult()
    }

    data class FontItem(val fileName: String, val sizeBytes: Long)

    companion object {
        /** 系统默认字体（不加载自定义字体） */
        const val SYSTEM = "system"

        /** 单字体文件上限 8MB（中文字体常见 3–6MB） */
        const val MAX_BYTES = 8 * 1024 * 1024

        private val FONT_EXT = setOf("ttf", "otf", "ttc")

        /** 文件名非法字符（路径分隔 / 通配等 → 下划线） */
        private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\s]")
    }

    private val dir: File get() = File(context.filesDir, "fonts").apply { if (!exists()) mkdirs() }

    /** 已导入字体列表（按名称排序） */
    fun list(): List<FontItem> =
        dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in FONT_EXT }
            ?.sortedBy { it.name.lowercase() }
            ?.map { FontItem(it.name, it.length()) }
            ?: emptyList()

    /** 取字体文件；[SYSTEM] 或不存在返回 null */
    fun fileOf(name: String): File? {
        if (name == SYSTEM) return null
        val f = File(dir, name)
        return f.takeIf { it.isFile }
    }

    /** 删除一个字体文件；成功返回 true */
    fun delete(name: String): Boolean {
        val f = File(dir, name)
        return f.isFile && f.delete()
    }

    /** 导入字体（SAF 选中文件 → 校验 → 复制到 fonts/） */
    suspend fun import(resolver: ContentResolver, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val rawName = queryName(resolver, uri)
            val ext = rawName.substringAfterLast('.', "").lowercase()
            if (ext !in FONT_EXT) return@withContext ImportResult.Unsupported
            val bytes = readLimited(resolver, uri) ?: return@withContext ImportResult.Failed
            if (bytes.isEmpty()) return@withContext ImportResult.Failed
            if (bytes.size > MAX_BYTES) return@withContext ImportResult.TooBig
            if (!looksFont(bytes)) return@withContext ImportResult.Unsupported
            val name = safeName(rawName, ext)
            File(dir, name).writeBytes(bytes)
            ImportResult.Ok(name)
        } catch (t: Throwable) {
            ImportResult.Failed
        }
    }

    // —— 内部 ——

    /** 文件头魔数：TrueType（0x00010000 / "true"）/ TTC（"ttcf"）/ OpenType-CFF（"OTTO"） */
    private fun looksFont(b: ByteArray): Boolean {
        if (b.size < 4) return false
        if (b[0] == 0.toByte() && b[1] == 1.toByte() && b[2] == 0.toByte() && b[3] == 0.toByte()) return true
        val sig = String(b, 0, 4, Charsets.US_ASCII)
        return sig == "true" || sig == "ttcf" || sig == "OTTO"
    }

    /** 读取至多 MAX_BYTES + 1 字节（多读 1 字节用于判断是否超限） */
    private fun readLimited(resolver: ContentResolver, uri: Uri): ByteArray? {
        val input = resolver.openInputStream(uri) ?: return null
        input.use {
            val limit = MAX_BYTES + 1
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (total < limit) {
                val n = it.read(buf, 0, minOf(buf.size, limit - total))
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
            return out.toByteArray()
        }
    }

    /** 安全文件名：非法字符替换为下划线，空则回退 "font.<ext>" */
    private fun safeName(rawName: String, ext: String): String {
        val cleaned = rawName.replace(ILLEGAL, "_").trim('_').take(64)
        val base = cleaned.substringBeforeLast('.', cleaned).ifBlank { "font" }
        return "$base.$ext"
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getString(0)
                if (!n.isNullOrBlank()) return n
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "font.ttf"
    }
}