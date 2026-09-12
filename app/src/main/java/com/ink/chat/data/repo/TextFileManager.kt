package com.ink.chat.data.repo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 文本文件读取编排（M5.5「文件上传」）。
 * DeepSeek Files API 仅支持图片，文档解析在客户端完成：
 * 纯文本类文件（txt / md / json / csv / 代码等）本地读取为文本，随消息注入对话。
 * 【要点】读取上限 [MAX_BYTES]（超出截断并由上层提示）；
 * 解码策略：UTF-8 严格 → 失败回退 GBK → 再失败用替换模式（中文老文件常见 GBK）。
 */
class TextFileManager(
    private val context: Context,
) {

    sealed class Result {
        data class Ok(
            val fileName: String,
            val content: String,
            val truncated: Boolean,
        ) : Result()

        /** 非纯文本（二进制或不认识的类型） */
        data class Unsupported(val fileName: String) : Result()
        object Empty : Result()
        object Failed : Result()
    }

    companion object {
        /** 单文件读取上限 256KB（≈8 万汉字；超出截断） */
        const val MAX_BYTES = 256 * 1024

        /** 二进制探测窗口（前 8KB 含 NUL 即视为二进制） */
        private const val SNIFF_BYTES = 8 * 1024

        private val TEXT_EXT = setOf(
            "txt", "text", "md", "markdown", "rst", "adoc", "org",
            "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml", "toml",
            "log", "ini", "cfg", "conf", "properties", "env",
            "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx",
            "c", "h", "cpp", "cc", "hpp", "cs", "go", "rs", "rb", "php",
            "sh", "bash", "bat", "cmd", "ps1", "sql",
            "html", "htm", "css", "scss", "less", "vue", "svelte",
            "dart", "swift", "lua", "r", "pl", "m", "tex", "gradle", "pro",
            "gitignore", "editorconfig", "license", "readme",
        )

        private val TEXT_MIMES = setOf(
            "application/json", "application/x-json", "application/ld+json",
            "application/xml", "application/xhtml+xml",
            "application/javascript", "application/x-javascript",
            "application/yaml", "application/x-yaml",
            "application/toml", "application/x-sh", "application/x-httpd-php",
            "application/sql", "application/graphql",
            "application/x-tex", "application/x-latex",
        )
    }

    suspend fun read(uri: Uri): Result = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val fileName = queryName(resolver, uri)
            val mime = resolver.getType(uri)?.lowercase()
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (!looksTextual(mime, ext)) return@withContext Result.Unsupported(fileName)

            val raw = readLimited(resolver, uri) ?: return@withContext Result.Failed
            if (raw.isEmpty()) return@withContext Result.Empty
            if (looksBinary(raw)) return@withContext Result.Unsupported(fileName)

            val truncated = raw.size > MAX_BYTES
            val bytes = if (truncated) raw.copyOf(MAX_BYTES) else raw
            val content = decodeText(bytes).trimEnd()
            if (content.isBlank()) return@withContext Result.Empty
            Result.Ok(fileName, content, truncated)
        } catch (t: Throwable) {
            Result.Failed
        }
    }

    /** 类型判定：MIME 为文本前缀或白名单 MIME；设备 MIME 不可靠时按扩展名兜底
     * （注意：注释内不要出现 斜杠+星号 连写——Kotlin 块注释支持嵌套，会吞掉后续代码） */
    private fun looksTextual(mime: String?, ext: String): Boolean {
        if (mime != null && (mime.startsWith("text/") || mime in TEXT_MIMES)) return true
        return ext in TEXT_EXT
    }

    /** 读取至多 MAX_BYTES + 1 字节（多读 1 字节用于判断是否被截断） */
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

    /** 二进制探测：前 8KB 出现 NUL 即判非文本（UTF-8 / GBK 文本都不会有） */
    private fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, SNIFF_BYTES)
        for (i in 0 until n) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    /** UTF-8 严格 → GBK → 替换模式 */
    private fun decodeText(bytes: ByteArray): String {
        strictDecode(bytes, StandardCharsets.UTF_8)?.let { return it }
        runCatching { Charset.forName("GBK") }.getOrNull()?.let { gbk ->
            strictDecode(bytes, gbk)?.let { return it }
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (t: Throwable) {
        null
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getString(0)
                if (!n.isNullOrBlank()) return n
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "文件"
    }
}
