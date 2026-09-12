package com.ink.chat.data.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ink.chat.domain.model.Conversation
import com.ink.chat.domain.model.Message
import com.ink.chat.domain.model.MessageRole
import com.ink.chat.domain.model.MessageStatus

/**
 * Room 表结构（框架 §4.4 完整 DDL 的 Kotlin 映射）。
 * 【心得·代码层 Room-1】字段名全部显式 snake_case，避免运行时 no such column。
 */

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "title") val title: String = "新对话",
    @ColumnInfo(name = "pinned") val pinned: Int = 0,
    @ColumnInfo(name = "archived") val archived: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversation_id", "created_at"], name = "idx_msg_conv_time")],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content") val content: String = "",
    @ColumnInfo(name = "reasoning_content") val reasoningContent: String? = null,
    @ColumnInfo(name = "attachments") val attachments: List<String>? = null,
    @ColumnInfo(name = "status") val status: String = "done",
    @ColumnInfo(name = "error") val error: String? = null,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int? = null,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Int? = null,
    @ColumnInfo(name = "think_ms") val thinkMs: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** 用量流水（余额页数据源，§4.4） */
@Entity(tableName = "usage_logs")
data class UsageEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long? = null,
    @ColumnInfo(name = "model") val model: String? = null,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    /** 缓存命中 tokens（M5.6；usage.prompt_cache_hit_tokens，旧记录为空） */
    @ColumnInfo(name = "cache_hit_tokens") val cacheHitTokens: Int? = null,
    /** 缓存未命中 tokens（M5.6；usage.prompt_cache_miss_tokens，旧记录为空 → 按未命中估算） */
    @ColumnInfo(name = "cache_miss_tokens") val cacheMissTokens: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** 模型列表缓存（§4.4 models_cache） */
@Entity(tableName = "models_cache")
data class ModelCacheEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

// —— 实体 → 域模型 ——

fun ConversationEntity.toDomain() = Conversation(
    id = id,
    title = title,
    pinned = pinned == 1,
    archived = archived == 1,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun MessageEntity.toDomain() = Message(
    id = id,
    conversationId = conversationId,
    role = MessageRole.fromWire(role),
    content = content,
    reasoningContent = reasoningContent,
    attachments = attachments,
    status = MessageStatus.fromDb(status),
    error = error,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    thinkMs = thinkMs,
    createdAt = createdAt,
)
