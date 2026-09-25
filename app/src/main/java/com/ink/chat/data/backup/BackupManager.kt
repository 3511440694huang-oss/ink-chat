package com.ink.chat.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ink.chat.data.datastore.SettingsStore
import com.ink.chat.data.room.InkDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份 / 恢复（M5.7）。
 *
 * 导出：`ink-backup-*.zip` =
 *   manifest.json（格式版本 / 应用版本 / 时间 / 条目统计）
 *   + db/ink_chat.db（WAL checkpoint 后复制，另含 -wal / -shm 若存在）
 *   + settings.json（对话默认值 / 显示 / 提示词 / 短语 / 字体选择；**不含 API Key**——密钥绑定设备 Keystore）
 *   + fonts/ 目录下全部自定义字体文件。
 *
 * 恢复（两阶段，避免运行时替换 Room 的不稳定）：
 *   1）[stageRestore] 解压到 filesDir/pending_restore/ 并校验；
 *   2）应用重启时 [applyPendingRestore] 在 Room 初始化之前完成替换（DB / 设置 / 字体），随后清空暂存。
 */
class BackupManager(
    private val context: Context,
    private val db: InkDatabase,
    private val settings: SettingsStore,
    private val gson: Gson,
) {

    sealed class ExportResult {
        data class Ok(val fileName: String, val bytes: Long) : ExportResult()
        object Failed : ExportResult()
    }

    sealed class RestoreStage {
        object Ok : RestoreStage()
        data class Failed(val reason: String) : RestoreStage()
    }

    companion object {
        const val DB_NAME = "ink_chat.db"
        private const val PENDING_DIR = "pending_restore"
        private const val FORMAT = 1

        /** 备份默认文件名（保存对话框用） */
        fun backupFileName(now: Long = System.currentTimeMillis()): String =
            "ink-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now)) + ".zip"

        /**
         * 启动收尾：若存在暂存恢复包，则在 Room 初始化前完成替换。
         * 由 [com.ink.chat.App.onCreate] 在 startKoin 之前调用（幂等；失败静默清理）。
         */
        fun applyPendingRestore(context: Context) {
            val pending = File(context.filesDir, PENDING_DIR)
            if (!pending.isDirectory) return
            try {
                // 1) 设置（DataStore；不含 API Key，保持本机现值）
                val settingsFile = File(pending, "settings.json")
                if (settingsFile.isFile) {
                    runBlocking {
                        val o = JsonParser.parseString(settingsFile.readText()).asJsonObject
                        SettingsStore(context).restoreFrom(
                            model = o.str("model", "deepseek-flash"),
                            effort = o.str("effort", "low"),
                            thinkingEnabled = o.bool("thinkingEnabled", true),
                            stream = o.bool("stream", false),
                            webSearch = o.bool("webSearch", false),
                            fontScale = o.float("fontScale", 0.9f),
                            lineSpacing = o.str("lineSpacing", "normal"),
                            theme = o.str("theme", "paper"),
                            systemPrompt = o.str("systemPrompt", ""),
                            balanceJson = "",
                            phrasesJson = o.str("phrasesJson", ""),
                            promptsJson = o.str("promptsJson", ""),
                            fontId = o.str("fontId", "system"),
                        )
                    }
                }
                // 2) 数据库替换（含 -wal / -shm 三件套；删除旧文件后再放新）
                val dbNew = File(pending, "db/$DB_NAME")
                if (dbNew.isFile) {
                    val dbPath = context.getDatabasePath(DB_NAME)
                    dbPath.parentFile?.mkdirs()
                    listOf("", "-wal", "-shm").forEach { File(dbPath.path + it).delete() }
                    dbNew.copyTo(File(dbPath.path), overwrite = true)
                    for (suffix in listOf("-wal", "-shm")) {
                        val f = File(pending, "db/$DB_NAME$suffix")
                        if (f.isFile) f.copyTo(File(dbPath.path + suffix), overwrite = true)
                    }
                }
                // 3) 字体文件
                val fontsNew = File(pending, "fonts")
                if (fontsNew.isDirectory) {
                    val fontsDst = File(context.filesDir, "fonts").apply { mkdirs() }
                    fontsNew.listFiles()?.filter { it.isFile }?.forEach {
                        it.copyTo(File(fontsDst, it.name), overwrite = true)
                    }
                }
            } catch (t: Throwable) {
                // 启动收尾失败：保持现网数据不动（清理暂存），不阻塞启动
            } finally {
                pending.deleteRecursively()
            }
        }

        private fun JsonObject.str(key: String, def: String): String =
            get(key)?.takeIf { !it.isJsonNull }?.asString ?: def

        private fun JsonObject.bool(key: String, def: Boolean): Boolean =
            get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: def

        private fun JsonObject.float(key: String, def: Float): Float =
            get(key)?.takeIf { !it.isJsonNull }?.asFloat ?: def
    }

    /** 导出完整备份到 [uri]（SAF） */
    suspend fun export(
        resolver: ContentResolver,
        uri: Uri,
        versionName: String,
        versionCode: Int,
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            // WAL 合并主库（失败容忍：仍复制三件套）
            runCatching {
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            }

            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.isFile) return@withContext ExportResult.Failed

            val s = settings.current()
            val fontDir = File(context.filesDir, "fonts")
            val fonts = fontDir.listFiles()?.filter { it.isFile } ?: emptyList()

            val manifest = JsonObject().apply {
                addProperty("format", FORMAT)
                addProperty("app", "ink-chat")
                addProperty("versionName", versionName)
                addProperty("versionCode", versionCode)
                addProperty("createdAt", System.currentTimeMillis())
                addProperty("dbBytes", dbFile.length())
                addProperty("fontCount", fonts.size)
            }
            val settingsJson = JsonObject().apply {
                addProperty("model", s.model)
                addProperty("effort", s.effort)
                addProperty("thinkingEnabled", s.thinkingEnabled)
                addProperty("stream", s.stream)
                addProperty("webSearch", s.webSearch)
                addProperty("fontScale", s.fontScale)
                addProperty("lineSpacing", s.lineSpacing)
                addProperty("theme", s.theme)
                addProperty("systemPrompt", s.systemPrompt)
                addProperty("phrasesJson", s.phrasesJson)
                addProperty("promptsJson", s.promptsJson)
                addProperty("fontId", s.fontId)
            }

            var written = 0L
            resolver.openOutputStream(uri)?.use { os ->
                ZipOutputStream(BufferedOutputStream(os)).use { zip ->
                    written += addBytes(zip, "manifest.json", gson.toJson(manifest).toByteArray())
                    written += addBytes(zip, "settings.json", gson.toJson(settingsJson).toByteArray())
                    written += addFile(zip, "db/$DB_NAME", dbFile)
                    for (suffix in listOf("-wal", "-shm")) {
                        val f = File(dbFile.path + suffix)
                        if (f.isFile && f.length() > 0) written += addFile(zip, "db/$DB_NAME$suffix", f)
                    }
                    fonts.forEach { written += addFile(zip, "fonts/${it.name}", it) }
                }
            } ?: return@withContext ExportResult.Failed

            ExportResult.Ok(backupFileName(), written)
        } catch (t: Throwable) {
            ExportResult.Failed
        }
    }

    /** 第一阶段：解压备份到暂存目录 + 校验（成功后应用需重启以收尾） */
    suspend fun stageRestore(resolver: ContentResolver, uri: Uri): RestoreStage = withContext(Dispatchers.IO) {
        try {
            val tmp = File(context.filesDir, PENDING_DIR)
            tmp.deleteRecursively()
            if (!tmp.mkdirs()) return@withContext RestoreStage.Failed("无法创建暂存目录。")

            resolver.openInputStream(uri)?.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zin ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name.contains("..")) return@withContext RestoreStage.Failed("压缩包路径非法。")
                        if (!entry.isDirectory) {
                            val out = File(tmp, name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zin.copyTo(it) }
                        }
                        zin.closeEntry()
                        entry = zin.nextEntry
                    }
                }
            } ?: return@withContext RestoreStage.Failed("无法读取所选文件。")

            if (!File(tmp, "manifest.json").isFile) return@withContext RestoreStage.Failed("不是有效的备份包（缺少 manifest.json）。")
            if (!File(tmp, "db/$DB_NAME").isFile) return@withContext RestoreStage.Failed("备份包缺少数据库文件。")
            RestoreStage.Ok
        } catch (t: Throwable) {
            RestoreStage.Failed(t.message ?: "恢复准备失败。")
        }
    }

    // —— zip 写入工具 ——

    private fun addBytes(zip: ZipOutputStream, name: String, bytes: ByteArray): Long {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
        return bytes.size.toLong()
    }

    private fun addFile(zip: ZipOutputStream, name: String, file: File): Long {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
        return file.length()
    }
}