package com.ink.chat.data.room.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ink.chat.data.room.entity.ConversationEntity
import com.ink.chat.data.room.entity.MessageEntity
import com.ink.chat.data.room.entity.ModelCacheEntity
import com.ink.chat.data.room.entity.UsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert
    suspend fun insert(entity: ConversationEntity): Long

    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY pinned DESC, updated_at DESC")
    fun all(): Flow<List<ConversationEntity>>

    /** 最近使用的会话（按 updated_at，不受置顶影响——启动进入用；§M3） */
    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY updated_at DESC LIMIT 1")
    suspend fun mostRecent(): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: Long): ConversationEntity?

    @Query("UPDATE conversations SET updated_at = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Int)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    /** 批量删除（M5.5 批量管理；messages 随外键 CASCADE 一并删除） */
    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 空闲会话（无任何消息）——新建时优先复用，避免空白堆积（§2.1 B1） */
    @Query(
        "SELECT * FROM conversations WHERE archived = 0 AND id NOT IN " +
            "(SELECT DISTINCT conversation_id FROM messages) " +
            "ORDER BY updated_at DESC LIMIT 1"
    )
    suspend fun blank(): ConversationEntity?

    /** 列表投影：条目数 + 最后消息预览（会话列表页主查询） */
    @Query(
        "SELECT c.*, " +
            "(SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id) AS msgCount, " +
            "(SELECT m.content FROM messages m WHERE m.conversation_id = c.id " +
            " ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS lastContent, " +
            "NULL AS matchContent " +
            "FROM conversations c WHERE c.archived = 0 " +
            "ORDER BY c.pinned DESC, c.updated_at DESC"
    )
    fun rows(): Flow<List<ConversationRowRaw>>

    /** 列表搜索：标题 + 消息全文（§2.1 B6）；摘要行优先展示首个命中片段 */
    @Query(
        "SELECT c.*, " +
            "(SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id) AS msgCount, " +
            "(SELECT m.content FROM messages m WHERE m.conversation_id = c.id " +
            " ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS lastContent, " +
            "(SELECT m.content FROM messages m WHERE m.conversation_id = c.id " +
            " AND m.content LIKE '%' || :q || '%' ESCAPE '\\' " +
            " ORDER BY m.created_at ASC, m.id ASC LIMIT 1) AS matchContent " +
            "FROM conversations c WHERE c.archived = 0 AND (" +
            " c.title LIKE '%' || :q || '%' ESCAPE '\\' " +
            " OR EXISTS (SELECT 1 FROM messages m2 WHERE m2.conversation_id = c.id " +
            " AND m2.content LIKE '%' || :q || '%' ESCAPE '\\')) " +
            "ORDER BY c.pinned DESC, c.updated_at DESC"
    )
    fun searchRows(q: String): Flow<List<ConversationRowRaw>>

    /** 导出用：全量会话（含归档） */
    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updated_at DESC")
    suspend fun allList(): List<ConversationEntity>

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int
}

/** 会话列表行投影（Room 投影 POJO，列别名与字段对齐） */
data class ConversationRowRaw(
    @Embedded val conv: ConversationEntity,
    val msgCount: Int,
    val lastContent: String?,
    val matchContent: String?,
)

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(entity: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at ASC, id ASC")
    fun byConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at ASC, id ASC")
    suspend fun listByConversation(conversationId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    /** 流式批量提交：仅更新正文与思维链（每批一次写） */
    @Query("UPDATE messages SET content = :content, reasoning_content = :reasoning WHERE id = :id")
    suspend fun updateStreaming(id: Long, content: String, reasoning: String?)

    /** 收尾：状态 + 错误 + 全文 + token 数 + 思考时长 */
    @Query(
        "UPDATE messages SET status = :status, error = :error, content = :content, " +
            "reasoning_content = :reasoning, prompt_tokens = :promptTokens, " +
            "completion_tokens = :completionTokens, think_ms = :thinkMs WHERE id = :id"
    )
    suspend fun finish(
        id: Long,
        status: String,
        error: String?,
        content: String,
        reasoning: String?,
        promptTokens: Int?,
        completionTokens: Int?,
        thinkMs: Long?,
    )

    /** 重试/重新生成：复用消息行，清空结果回到 sending（§4.6 Regenerate） */
    @Query(
        "UPDATE messages SET status = 'sending', error = NULL, content = '', " +
            "reasoning_content = NULL, think_ms = NULL, prompt_tokens = NULL, " +
            "completion_tokens = NULL WHERE id = :id"
    )
    suspend fun resetForRetry(id: Long)

    /** 单条删除（§2.1 A8） */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 启动清扫：进程被杀等因素留下的悬挂 sending 行 → canceled */
    @Query("UPDATE messages SET status = 'canceled', error = NULL WHERE status = 'sending'")
    suspend fun cancelDanglingSending()

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: Long)

    /** 批量插入（导入用；在事务中调用） */
    @Insert
    suspend fun insertAll(items: List<MessageEntity>)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int
}

@Dao
interface UsageDao {

    @Insert
    suspend fun insert(entity: UsageEntity): Long

    @Query("SELECT * FROM usage_logs ORDER BY created_at DESC LIMIT 100")
    fun recent(): Flow<List<UsageEntity>>

    /** 本次会话累计用量（每轮全量历史重复计费，即真实消耗口径；无数据时 SUM 为 null） */
    @Query(
        "SELECT SUM(input_tokens) AS inputTokens, SUM(output_tokens) AS outputTokens " +
            "FROM usage_logs WHERE conversation_id = :conversationId"
    )
    fun tokenSum(conversationId: Long): Flow<TokenSum?>

    /** 本会话逐轮流水（M5.6：花费需按「行时间 + 模型」计峰谷价，取明细行聚合） */
    @Query("SELECT * FROM usage_logs WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun rowsOf(conversationId: Long): Flow<List<UsageEntity>>

    /** 区间用量汇总（余额页：今日 / 本月 / 上月；since ≤ t < until） */
    @Query(
        "SELECT COALESCE(SUM(input_tokens), 0) AS inputTokens, " +
            "COALESCE(SUM(output_tokens), 0) AS outputTokens " +
            "FROM usage_logs WHERE created_at >= :since AND created_at < :until"
    )
    suspend fun sumBetween(since: Long, until: Long): TokenSum
}

/** token 聚合投影（Room 投影 POJO，列别名与字段对齐） */
data class TokenSum(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

@Dao
interface ModelCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ModelCacheEntity>)

    @Query("SELECT * FROM models_cache ORDER BY id ASC")
    suspend fun all(): List<ModelCacheEntity>
}
