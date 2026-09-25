package com.ink.chat.data.repo

import androidx.room.withTransaction
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ink.chat.data.datastore.AppSettings
import com.ink.chat.data.datastore.CryptoManager
import com.ink.chat.data.datastore.SettingsStore
import com.ink.chat.data.room.InkDatabase
import com.ink.chat.data.room.dao.ConversationDao
import com.ink.chat.data.room.dao.ConversationRowRaw
import com.ink.chat.data.room.dao.MessageDao
import com.ink.chat.data.room.dao.TokenSum
import com.ink.chat.data.room.dao.UsageDao
import com.ink.chat.data.room.entity.ConversationEntity
import com.ink.chat.data.room.entity.MessageEntity
import com.ink.chat.data.room.entity.UsageEntity
import com.ink.chat.data.room.entity.toDomain
import com.ink.chat.data.transfer.TransferConversation
import com.ink.chat.data.transfer.TransferMessage
import com.ink.chat.data.transfer.TransferPayload
import com.ink.chat.domain.model.Conversation
import com.ink.chat.domain.model.ConversationRow
import com.ink.chat.domain.model.ConversationUsage
import com.ink.chat.domain.model.ErrorAction
import com.ink.chat.domain.model.Message
import com.ink.chat.domain.model.OutgoingAttachment
import com.ink.chat.domain.model.OutgoingTextFile
import com.ink.chat.domain.model.SendOutcome
import com.ink.chat.network.ChatCanceledException
import com.ink.chat.network.DeepSeekApi
import com.ink.chat.util.Pricing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 对话数据流（§4.3 repo 层）。
 * 发送流程：用户消息落库 → 助手占位行 → 全量历史装配 → 调用 → 收尾落库。
 */
class ChatRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val usageDao: UsageDao,
    private val api: DeepSeekApi,
    private val settings: SettingsStore,
    private val db: InkDatabase,
) {

    companion object {
        const val DEFAULT_TITLE = "新对话"

        /** 带图消息自动使用的模型（§2.1 D4 / A.5；V4.1 起统一正式名，旧 vision-exp 已下线） */
        const val VISION_MODEL = "deepseek-flash"
    }

    fun messagesOf(conversationId: Long): Flow<List<Message>> =
        messageDao.byConversation(conversationId).map { list -> list.map { it.toDomain() } }

    fun conversations(): Flow<List<Conversation>> =
        conversationDao.all().map { list -> list.map { it.toDomain() } }

    /** 本次会话用量汇总（M5.6：token + 缓存命中率 + 花费估算；无记录 → null） */
    fun usageOf(conversationId: Long): Flow<ConversationUsage?> =
        usageDao.rowsOf(conversationId).map { rows -> if (rows.isEmpty()) null else summarizeUsage(rows) }

    /** 逐轮流水聚合：旧记录无缓存列时整额按未命中估算（保守上限）；花费按行时间计峰谷价 */
    private fun summarizeUsage(rows: List<UsageEntity>): ConversationUsage {
        var input = 0
        var output = 0
        var hit = 0
        var miss = 0
        var cost = 0.0
        var cacheSeen = false
        rows.forEach { r ->
            val i = r.inputTokens ?: 0
            val o = r.outputTokens ?: 0
            val h = r.cacheHitTokens ?: 0
            val m = r.cacheMissTokens ?: r.cacheHitTokens?.let { (i - it).coerceAtLeast(0) } ?: i
            if (r.cacheHitTokens != null || r.cacheMissTokens != null) cacheSeen = true
            input += i
            output += o
            hit += h
            miss += m
            cost += Pricing.costOf(r.model, r.createdAt, h, m, o)
        }
        return ConversationUsage(
            rounds = rows.size,
            inputTokens = input,
            outputTokens = output,
            cacheHitTokens = hit,
            cacheMissTokens = miss,
            costCny = cost,
            cacheDataPresent = cacheSeen,
        )
    }

    /** 区间用量汇总（余额页；since ≤ t < until） */
    suspend fun usageBetween(since: Long, until: Long): TokenSum = usageDao.sumBetween(since, until)

    /** 取最近使用的会话；不存在则新建（M3 多会话入口——不受置顶影响） */
    suspend fun ensureConversation(): Long {
        conversationDao.mostRecent()?.let { return it.id }
        val now = System.currentTimeMillis()
        return conversationDao.insert(ConversationEntity(createdAt = now, updatedAt = now))
    }

    /** 发送一条消息（完整链路；M5：可携带图片附件 → 自动 vision 模型；M5.5：可携带文本文件） */
    suspend fun send(
        conversationId: Long,
        userText: String,
        attachments: List<OutgoingAttachment> = emptyList(),
        textFiles: List<OutgoingTextFile> = emptyList(),
    ): SendOutcome {
        val s = settings.current()
        val apiKey = CryptoManager.decrypt(s.apiKeyEnc)
        if (apiKey.isNullOrBlank()) return SendOutcome.NoApiKey

        val now = System.currentTimeMillis()

        // 1) 用户消息落库（带图：正文含 "[图片] 文件名" 占位行；文件：[文件]…[文件结束] 块）
        messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                role = "user",
                content = buildUserContent(userText, attachments, textFiles),
                attachments = attachments.map { it.fileId }.ifEmpty { null },
                status = "done",
                createdAt = now,
            )
        )

        // 首条消息 → 本地截取自动标题（不耗 token；无文字时取文件名 /「图片」）
        val conv = conversationDao.byId(conversationId)
        if (conv != null && conv.title == DEFAULT_TITLE) {
            conversationDao.rename(conversationId, autoTitle(userText, textFiles))
        }

        // 2) 助手占位行（sending；墨屏可从 DB 单向流式观察）
        val placeholderId = messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                role = "assistant",
                content = "",
                status = "sending",
                createdAt = now + 1,
            )
        )

        return performGeneration(conversationId, placeholderId, apiKey, s)
    }

    /**
     * 重试 / 重新生成（§2.1 A5/A10，§4.6 Regenerate）：
     * 复用目标消息行、不重复插入 user 消息；历史装配时排除该行自身与 failed 行。
     */
    suspend fun regenerate(conversationId: Long, assistantMessageId: Long): SendOutcome {
        val s = settings.current()
        val apiKey = CryptoManager.decrypt(s.apiKeyEnc)
        if (apiKey.isNullOrBlank()) return SendOutcome.NoApiKey

        val row = messageDao.byId(assistantMessageId)
            ?: return SendOutcome.Failure("消息已不存在。", ErrorAction.NONE)
        if (row.role != "assistant") return SendOutcome.Failure("只支持重新生成回答。", ErrorAction.NONE)

        messageDao.resetForRetry(assistantMessageId)
        return performGeneration(conversationId, assistantMessageId, apiKey, s)
    }

    /** 单条删除（§2.1 A8） */
    suspend fun deleteMessage(messageId: Long) {
        messageDao.deleteById(messageId)
    }

    /** 启动清扫：悬挂 sending → canceled（进程被杀等留下的半截） */
    suspend fun cleanupDanglingSending() {
        messageDao.cancelDanglingSending()
    }

    // —— 会话管理（M3，§2.1 B1-B10）——

    /** 列表投影流：条目数 + 摘要预览（列表页主数据源） */
    fun conversationRows(): Flow<List<ConversationRow>> =
        conversationDao.rows().map { list -> list.map { it.toRow() } }

    /** 列表搜索：标题 + 消息全文（§2.1 B6） */
    fun searchRows(query: String): Flow<List<ConversationRow>> =
        conversationDao.searchRows(escapeLike(query)).map { list -> list.map { it.toRow() } }

    /** 新建会话：空闲会话复用，否则新建（§2.1 B1，避免空白堆积） */
    suspend fun findBlankOrCreate(): Long {
        conversationDao.blank()?.let { return it.id }
        val now = System.currentTimeMillis()
        return conversationDao.insert(ConversationEntity(createdAt = now, updatedAt = now))
    }

    /** 重命名（§2.1 B3） */
    suspend fun renameConversation(id: Long, title: String) {
        conversationDao.rename(id, title.trim())
    }

    /** 置顶切换（§2.1 B5） */
    suspend fun togglePin(id: Long, pinned: Boolean) {
        conversationDao.setPinned(id, if (pinned) 0 else 1)
    }

    /** 删除会话及全部消息（外键 CASCADE；§2.1 B4） */
    suspend fun deleteConversation(id: Long) {
        conversationDao.delete(id)
    }

    /** 批量删除会话及全部消息（M5.5 批量管理；外键 CASCADE） */
    suspend fun deleteConversations(ids: List<Long>) {
        if (ids.isEmpty()) return
        conversationDao.deleteByIds(ids.distinct())
    }

    /** 会话全部消息（列表页「跳到…」弹窗用） */
    suspend fun listMessages(conversationId: Long): List<Message> =
        messageDao.listByConversation(conversationId).map { it.toDomain() }

    /** 数据统计（设置页「存储占用」；返回 会话数 to 消息数） */
    suspend fun stats(): Pair<Int, Int> = conversationDao.count() to messageDao.count()

    /**
     * 对话总结（§2.1 B8 / §4.6-②）：
     * 全量消息 → /chat/completions（非思考、非流式）→ 摘要追加为 assistant 消息（标注「·摘要」）。
     */
    suspend fun summarize(conversationId: Long): SendOutcome {
        val s = settings.current()
        val apiKey = CryptoManager.decrypt(s.apiKeyEnc)
        if (apiKey.isNullOrBlank()) return SendOutcome.NoApiKey

        val history = messageDao.listByConversation(conversationId)
            .filter { it.content.isNotBlank() && it.status != "failed" }
        if (history.isEmpty()) return SendOutcome.Failure("这个会话还没有可总结的内容。", ErrorAction.NONE)

        val transcript = buildString {
            history.forEach { m ->
                append(if (m.role == "user") "你：" else "AI：")
                append(m.content)
                append("\n\n")
            }
        }
        val prompt = "请把下面这段对话总结为简洁的摘要，保留关键结论、决定与待办事项；" +
            "直接给出摘要正文。\n\n【对话】\n$transcript"
        val messages = listOf(
            JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", prompt)
            }
        )

        val outcome = try {
            api.chatCompletions(
                apiKey = apiKey,
                model = s.model,
                messages = messages,
                stream = false,
                thinkingEnabled = false,
                effort = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return SendOutcome.Failure("摘要生成失败，请重试。", ErrorAction.RETRY)
        }

        return if (outcome.ok && outcome.content.isNotBlank()) {
            val now = System.currentTimeMillis()
            messageDao.insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = "assistant",
                    content = "·摘要\n\n" + outcome.content.trim(),
                    status = "done",
                    promptTokens = outcome.promptTokens,
                    completionTokens = outcome.completionTokens,
                    createdAt = now,
                )
            )
            usageDao.insert(
                UsageEntity(
                    conversationId = conversationId,
                    model = s.model,
                    inputTokens = outcome.promptTokens,
                    outputTokens = outcome.completionTokens,
                    cacheHitTokens = outcome.cacheHitTokens,
                    cacheMissTokens = outcome.cacheMissTokens,
                    createdAt = now,
                )
            )
            conversationDao.touch(conversationId, now)
            SendOutcome.Success
        } else {
            SendOutcome.Failure(
                outcome.human?.text ?: "摘要生成失败。",
                outcome.human?.action ?: ErrorAction.RETRY,
            )
        }
    }

    /** 组装导出载荷（§2.1 B9）：conversationId = null 时导出全部（含归档） */
    suspend fun buildExport(conversationId: Long?): TransferPayload {
        val convs = if (conversationId != null) {
            listOfNotNull(conversationDao.byId(conversationId))
        } else {
            conversationDao.allList()
        }
        return payloadOf(convs)
    }

    /** 组装批量导出载荷（M5.5 批量管理：所选会话） */
    suspend fun buildExportSelected(ids: List<Long>): TransferPayload =
        payloadOf(ids.distinct().mapNotNull { conversationDao.byId(it) })

    private suspend fun payloadOf(convs: List<ConversationEntity>): TransferPayload {
        val payload = convs.map { c ->
            TransferConversation(
                title = c.title,
                pinned = c.pinned == 1,
                createdAt = c.createdAt,
                updatedAt = c.updatedAt,
                messages = messageDao.listByConversation(c.id).map { m ->
                    TransferMessage(
                        role = m.role,
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
        }
        return TransferPayload(conversations = payload)
    }

    /**
     * 导入（§2.1 B10）：单事务写入，冲突策略「追加」（全部作为新会话）。
     * 悬挂 sending 状态归一为 done；返回 (会话数, 消息数)。
     */
    suspend fun importPayload(payload: TransferPayload): Pair<Int, Int> {
        var convCount = 0
        var msgCount = 0
        db.withTransaction {
            payload.conversations.forEach { tc ->
                val cid = conversationDao.insert(
                    ConversationEntity(
                        title = tc.title.ifBlank { DEFAULT_TITLE },
                        pinned = if (tc.pinned) 1 else 0,
                        createdAt = tc.createdAt,
                        updatedAt = tc.updatedAt,
                    )
                )
                val msgs = tc.messages.map { m ->
                    MessageEntity(
                        conversationId = cid,
                        role = m.role,
                        content = m.content,
                        reasoningContent = m.reasoningContent,
                        status = if (m.status == "sending") "done" else m.status,
                        error = m.error,
                        promptTokens = m.promptTokens,
                        completionTokens = m.completionTokens,
                        thinkMs = m.thinkMs,
                        createdAt = m.createdAt,
                    )
                }
                if (msgs.isNotEmpty()) messageDao.insertAll(msgs)
                convCount++
                msgCount += msgs.size
            }
        }
        return convCount to msgCount
    }

    private fun ConversationRowRaw.toRow() = ConversationRow(
        id = conv.id,
        title = conv.title,
        pinned = conv.pinned == 1,
        updatedAt = conv.updatedAt,
        messageCount = msgCount,
        preview = (matchContent ?: lastContent)?.replace('\n', ' ')?.trim().orEmpty(),
    )

    /** LIKE 转义：避免用户输入的 % / _ / \ 被当作通配符 */
    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    // —— 生成主链（send / regenerate 共用）——

    private suspend fun performGeneration(
        conversationId: Long,
        assistantId: Long,
        apiKey: String,
        s: AppSettings,
    ): SendOutcome {
        var lastContent = ""
        var lastReasoning = ""
        try {
            // 3) 全量历史装配（排除自身 / failed / 空助手消息；§4.5）
            val history = messageDao.listByConversation(conversationId)
                .filter { it.id != assistantId && it.status != "failed" }
                .filterNot { it.role == "assistant" && it.content.isEmpty() }

            // M5：末条带附件消息 → vision 模型 + file 内容块；联网开（且无图）→ Anthropic 兼容端点
            val visionMsgId = history.lastOrNull { it.role == "user" && !it.attachments.isNullOrEmpty() }?.id
            val useVision = visionMsgId != null
            val model = if (useVision) VISION_MODEL else s.model
            val useWeb = s.webSearch && !useVision
            val apiMessages = buildApiMessages(
                history,
                useTools = useWeb,
                visionMsgId = visionMsgId,
                systemPrompt = s.systemPrompt.takeIf { it.isNotBlank() },
            )
            if (history.isEmpty()) {
                messageDao.finish(assistantId, "failed", "没有可用的上下文。", "", null, null, null, null)
                return SendOutcome.Failure("没有可用的上下文。", ErrorAction.NONE)
            }

            // 4) 调用（流式时按段更新占位行——每批一次 DB 写；联网走 Anthropic 兼容端点非流式）
            val outcome = if (useWeb) {
                api.anthropicMessages(apiKey, model, apiMessages)
            } else {
                api.chatCompletions(
                    apiKey = apiKey,
                    model = model,
                    messages = apiMessages,
                    stream = s.stream,
                    thinkingEnabled = s.thinkingEnabled,
                    effort = s.effort,
                ) { content, reasoning ->
                    lastContent = content
                    lastReasoning = reasoning
                    messageDao.updateStreaming(assistantId, content, reasoning.ifEmpty { null })
                }
            }

            // 5) 收尾落库
            return if (outcome.ok) {
                messageDao.finish(
                    assistantId, "done", null,
                    outcome.content, outcome.reasoning.ifEmpty { null },
                    outcome.promptTokens, outcome.completionTokens, outcome.thinkMs,
                )
                usageDao.insert(
                    UsageEntity(
                        conversationId = conversationId,
                        model = model,
                        inputTokens = outcome.promptTokens,
                        outputTokens = outcome.completionTokens,
                        cacheHitTokens = outcome.cacheHitTokens,
                        cacheMissTokens = outcome.cacheMissTokens,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                conversationDao.touch(conversationId, System.currentTimeMillis())
                SendOutcome.Success
            } else {
                messageDao.finish(
                    assistantId, "failed", outcome.human?.text, outcome.content,
                    outcome.reasoning.ifEmpty { null },
                    outcome.promptTokens, outcome.completionTokens, outcome.thinkMs,
                )
                conversationDao.touch(conversationId, System.currentTimeMillis())
                SendOutcome.Failure(outcome.human?.text ?: "发送失败。", outcome.human?.action ?: ErrorAction.RETRY)
            }
        } catch (ce: ChatCanceledException) {
            // 用户中止：半截内容落库（NonCancellable —— 取消状态下仍必须写完）
            val c = ce.content.ifEmpty { lastContent }
            val r = if (ce.reasoning.isNotEmpty()) ce.reasoning else lastReasoning
            withContext(NonCancellable) {
                messageDao.finish(assistantId, "canceled", null, c, r.takeIf { it.isNotEmpty() }, null, null, ce.thinkMs)
                conversationDao.touch(conversationId, System.currentTimeMillis())
            }
            return SendOutcome.Canceled
        } catch (ce: CancellationException) {
            // 其它取消（如重试等待中被中断）：同样以「已中止」收尾
            withContext(NonCancellable) {
                messageDao.finish(assistantId, "canceled", null, lastContent, lastReasoning.takeIf { it.isNotEmpty() }, null, null, null)
                conversationDao.touch(conversationId, System.currentTimeMillis())
            }
            return SendOutcome.Canceled
        } catch (t: Throwable) {
            messageDao.finish(assistantId, "failed", "请求异常：${t.message}", lastContent, lastReasoning.takeIf { it.isNotEmpty() }, null, null, null)
            conversationDao.touch(conversationId, System.currentTimeMillis())
            return SendOutcome.Failure("请求异常，请重试。", ErrorAction.RETRY)
        }
    }

    private fun autoTitle(text: String, textFiles: List<OutgoingTextFile>): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isNotEmpty()) return if (t.length <= 16) t else t.take(16) + "…"
        textFiles.firstOrNull()?.let { f ->
            val n = f.name.trim().take(16)
            if (n.isNotEmpty()) return n
        }
        return "图片"
    }

    /**
     * 消息正文组装（§2.1 C2 / M5.5）：
     * [文件] name\n<content>\n[文件结束] → [图片] name → 用户文字。
     * 文件内容供模型读取；UI 渲染时折叠显示（InkMessageBlock.parseFileSection）。
     */
    private fun buildUserContent(
        text: String,
        attachments: List<OutgoingAttachment>,
        textFiles: List<OutgoingTextFile> = emptyList(),
    ): String {
        val parts = mutableListOf<String>()
        textFiles.forEach { parts += "[文件] ${it.name}\n${it.content}\n[文件结束]" }
        if (attachments.isNotEmpty()) {
            parts += attachments.joinToString("\n") { "[图片] ${it.fileName}" }
        }
        if (text.isNotBlank()) parts += text
        return parts.joinToString("\n")
    }

    /**
     * 思维链回传规则（§4.5 / A.3）：
     * useTools=true（联网）时拼回历史 reasoning_content；普通对话省略。
     * 注：Anthropic 兼容端点由 doAnthropicMessages 转换装配，当前仅转发文本内容块。
     * M5：visionMsgId 对应消息用 file 内容块引用 file_id（A.5），其余附件消息文本化。
     * M5.7：systemPrompt 非空时置于消息列表最前（Anthropic 端点会提升为顶层 system）。
     */
    private fun buildApiMessages(
        history: List<MessageEntity>,
        useTools: Boolean,
        visionMsgId: Long?,
        systemPrompt: String? = null,
    ): List<JsonObject> {
        val out = ArrayList<JsonObject>(history.size + 1)
        systemPrompt?.takeIf { it.isNotBlank() }?.let { sp ->
            out += JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", sp)
            }
        }
        history.forEach { m ->
            out += JsonObject().apply {
                addProperty("role", m.role)
                if (m.id == visionMsgId && !m.attachments.isNullOrEmpty()) {
                    add(
                        "content",
                        JsonArray().apply {
                            m.attachments.forEach { fid ->
                                // 与官方现行文档（2026-09）对齐：file 块平铺 file_id
                                // （旧嵌套 {"file":{"file_id"}} 为历史实测格式，已弃用）
                                add(
                                    JsonObject().apply {
                                        addProperty("type", "file")
                                        addProperty("file_id", fid)
                                    }
                                )
                            }
                            add(
                                JsonObject().apply {
                                    addProperty("type", "text")
                                    addProperty("text", m.content)
                                }
                            )
                        }
                    )
                } else {
                    addProperty("content", m.content)
                }
                if (useTools && m.role == "assistant" && !m.reasoningContent.isNullOrEmpty()) {
                    addProperty("reasoning_content", m.reasoningContent)
                }
            }
        }
        return out
    }
}