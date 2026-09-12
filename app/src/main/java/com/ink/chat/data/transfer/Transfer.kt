package com.ink.chat.data.transfer

import com.google.gson.GsonBuilder
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出 / 导入载荷（§2.1 B9/B10，§4.6-④）。
 * JSON 根结构：format + version + exportedAt + conversations[]。
 * 导入冲突策略：追加（全部作为新会话写入，id 重新生成）。
 */
data class TransferPayload(
    val format: String = TransferCodec.FORMAT,
    val version: Int = TransferCodec.VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val conversations: List<TransferConversation> = emptyList(),
)

data class TransferConversation(
    val title: String = "新对话",
    val pinned: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val messages: List<TransferMessage> = emptyList(),
)

data class TransferMessage(
    val role: String = "user",
    val content: String = "",
    val reasoningContent: String? = null,
    val status: String = "done",
    val error: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val thinkMs: Long? = null,
    val createdAt: Long = 0L,
)

/** JSON 编解码 + 文件名工具（SAF 读写由调用方负责流） */
object TransferCodec {

    const val FORMAT = "ink-chat-export"
    const val VERSION = 1

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun write(payload: TransferPayload, out: OutputStream) {
        val w = OutputStreamWriter(out, Charsets.UTF_8)
        gson.toJson(payload, w)
        w.flush()
    }

    /** 解析并校验；不合法时抛 IllegalArgumentException（消息可直接展示） */
    fun read(input: InputStream): TransferPayload {
        val payload = try {
            gson.fromJson(InputStreamReader(input, Charsets.UTF_8), TransferPayload::class.java)
        } catch (t: Throwable) {
            null
        } ?: throw IllegalArgumentException("文件内容为空或不是 JSON。")
        if (payload.format != FORMAT) throw IllegalArgumentException("不是「静墨」导出的对话文件。")
        if (payload.version < 1 || payload.version > VERSION) {
            throw IllegalArgumentException("文件版本不支持（v${payload.version}）。")
        }
        return try {
            sanitize(payload)
        } catch (t: Throwable) {
            throw IllegalArgumentException("文件内容不完整。")
        }
    }

    /** 防御性清洗：Gson 对缺失字段可能留下 null / 异常值 */
    private fun sanitize(raw: TransferPayload): TransferPayload = TransferPayload(
        format = FORMAT,
        version = VERSION,
        exportedAt = if (raw.exportedAt > 0) raw.exportedAt else System.currentTimeMillis(),
        conversations = raw.conversations.orEmpty().map { tc ->
            TransferConversation(
                title = tc.title.ifBlank { "新对话" },
                pinned = tc.pinned,
                createdAt = tc.createdAt,
                updatedAt = tc.updatedAt,
                messages = tc.messages.orEmpty().map { m ->
                    TransferMessage(
                        role = if (m.role == "assistant") "assistant" else "user",
                        content = m.content,
                        reasoningContent = m.reasoningContent,
                        status = m.status,
                        error = m.error,
                        promptTokens = m.promptTokens,
                        completionTokens = m.completionTokens,
                        thinkMs = m.thinkMs,
                        createdAt = m.createdAt,
                    )
                },
            )
        },
    )

    /** 导出文件名：「静墨-{会话标题}-{yyyyMMdd-HHmm}.json」；title=null 时为「全部对话」 */
    fun exportFileName(title: String?, now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        val base = title?.let(::safeTitle)?.takeIf { it.isNotBlank() } ?: "全部对话"
        return "静墨-$base-$stamp.json"
    }

    private fun safeTitle(t: String): String =
        t.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "").take(24)
}