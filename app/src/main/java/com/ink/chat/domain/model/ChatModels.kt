package com.ink.chat.domain.model

import kotlin.math.roundToInt

/** 消息角色（wire = API 传输值） */
enum class MessageRole(val wire: String) {
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromWire(v: String) = values().firstOrNull { it.wire == v } ?: USER
    }
}

/** 消息状态（持久值：sending / done / canceled / failed） */
enum class MessageStatus(val db: String) {
    SENDING("sending"),
    DONE("done"),
    CANCELED("canceled"),
    FAILED("failed");

    companion object {
        fun fromDb(v: String) = values().firstOrNull { it.db == v } ?: DONE
    }
}

/** 一条消息（域模型；§4.4 messages 表映射） */
data class Message(
    val id: Long,
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
    val reasoningContent: String? = null,
    val attachments: List<String>? = null,
    val status: MessageStatus = MessageStatus.DONE,
    val error: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    /** 思考时长（ms）；仅流式响应可测（首个正文抵达 − 请求发出），非流式为空 */
    val thinkMs: Long? = null,
    val createdAt: Long = 0L,
)

/** 会话（域模型；§4.4 conversations 表映射） */
data class Conversation(
    val id: Long,
    val title: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** 会话列表行（域模型；列表页显示用：条目数 + 摘要预览） */
data class ConversationRow(
    val id: Long,
    val title: String,
    val pinned: Boolean,
    val updatedAt: Long,
    val messageCount: Int,
    /** 摘要行文案：普通模式 = 最后消息预览；搜索模式 = 首个命中片段 */
    val preview: String,
)

/** 错误后续动作（与 §3.5 文案库的 [重试] / [去设置] / [去查询] 对应） */
enum class ErrorAction { NONE, GO_SETTINGS, QUERY_BALANCE, RETRY }

/** 发送流程结果（给 ViewModel 消费） */
sealed class SendOutcome {
    object Success : SendOutcome()
    object NoApiKey : SendOutcome()

    /** 用户主动中止；半截内容已按 canceled 落库（§2.2 状态机） */
    object Canceled : SendOutcome()
    data class Failure(val humanError: String, val action: ErrorAction) : SendOutcome()
}

/** 待发送附件（M5 §2.1 C2：file_id + 显示文件名） */
data class OutgoingAttachment(
    val fileId: String,
    val fileName: String,
)

/**
 * 待发送文本文件（M5.5「文件上传」）。
 * DeepSeek Files API 仅支持图片（JPEG/PNG/GIF/WebP），文档解析在客户端完成：
 * 纯文本类文件（txt / md / json / csv / 代码等）本地读取为文本，随消息注入对话。
 */
data class OutgoingTextFile(
    val name: String,
    val content: String,
)

/** 余额快照（附录 A.6；JSON 序列化后缓存于 DataStore） */
data class BalanceSnapshot(
    val available: Boolean = false,
    val currency: String = "CNY",
    val totalBalance: String = "0",
    /** 赠送余额（A.6 granted_balance，可空） */
    val grantedBalance: String? = null,
    /** 充值余额（A.6 topped_up_balance，可空） */
    val toppedUpBalance: String? = null,
    val fetchedAt: Long = 0L,
)

/**
 * 本次对话用量汇总（M5.6：缓存命中率 + 花费估算）。
 * rounds = 计费轮次；inputTokens = 每轮全量历史的真实计费口径（缓存命中 + 未命中）。
 */
data class ConversationUsage(
    val rounds: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val costCny: Double = 0.0,
    /** 是否含真实缓存字段数据（旧记录无 → 命中率不可知，显示「—」） */
    val cacheDataPresent: Boolean = false,
) {
    /** 缓存命中率（%）；无数据 / 无输入时 null */
    val hitRatePercent: Int?
        get() {
            if (!cacheDataPresent) return null
            val base = cacheHitTokens + cacheMissTokens
            return if (base <= 0) null else (cacheHitTokens * 100.0 / base).roundToInt()
        }
}
