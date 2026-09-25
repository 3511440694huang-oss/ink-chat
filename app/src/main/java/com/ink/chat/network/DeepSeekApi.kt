package com.ink.chat.network

import android.os.SystemClock
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ink.chat.domain.model.BalanceSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * DeepSeek API 客户端（OkHttp 直连 JSON，附录 A 口径）。
 * - 请求装配：thinking / reasoning_effort（A.2-A.9）；思考模式不传 temperature 系参数
 * - 重试：429 / 5xx / IO（§4.5，统一走 RetryPolicy）
 * - 流式：SSE，按段批量回调（≥200 字符或段落边界）——墨水屏不逐字刷新（§4.5）
 * - 速率：客户端 ≤ 2 次/秒（§4.5 并发保护）
 * - 中止：协程取消 → call.cancel() 打断阻塞 IO；半截内容随 [ChatCanceledException] 带出
 */
class DeepSeekApi(
    private val okHttpClient: OkHttpClient,
) {

    companion object {
        const val BASE_URL = "https://api.deepseek.com"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** 流式批量提交阈值（墨水屏：按段刷新，§4.5） */
        private const val STREAM_BATCH_CHARS = 200

        /** 联网来源随正文附加上限（M5.7：引用块版式；去重后取前 N 条） */
        private const val MAX_SOURCES = 5
    }

    /** 一次生成的结果（text = 成功；human != null = 失败，text 为可展示文案） */
    data class ApiOutcome(
        val content: String = "",
        val reasoning: String = "",
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        /** 缓存命中 / 未命中输入 tokens（M5.6；usage.prompt_cache_*） */
        val cacheHitTokens: Int? = null,
        val cacheMissTokens: Int? = null,
        /** 思考时长（ms）；仅流式可测（首正文抵达 − 请求发出） */
        val thinkMs: Long? = null,
        val human: ApiErrorMapper.HumanError? = null,
    ) {
        val ok: Boolean get() = human == null
    }

    private data class RawResult(
        /** 200 = 成功响应；其它 = HTTP 状态码；null 且 ioError != null = IO 异常 */
        val code: Int? = null,
        val retryAfterMs: Long? = null,
        val content: String = "",
        val reasoning: String = "",
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val cacheHitTokens: Int? = null,
        val cacheMissTokens: Int? = null,
        val thinkMs: Long? = null,
        val parseFailed: Boolean = false,
        val ioError: IOException? = null,
    )

    // —— 速率限制：≤2 次/秒 ——
    private var lastRequestAt = 0L

    private suspend fun acquireRate() {
        val wait = lastRequestAt + 500 - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    /**
     * 普通对话（§4.5 装配规则）。
     * @param onBatch 流式批量回调（content 全文, reasoning 全文）——注意在 IO 协程中调用
     */
    suspend fun chatCompletions(
        apiKey: String,
        model: String,
        messages: List<JsonObject>,
        stream: Boolean,
        thinkingEnabled: Boolean,
        effort: String?,
        onBatch: (suspend (content: String, reasoning: String) -> Unit)? = null,
    ): ApiOutcome = withContext(Dispatchers.IO) {
        acquireRate()
        var attempt = 0
        while (true) {
            attempt++
            val raw = try {
                doChat(apiKey, model, messages, stream, thinkingEnabled, effort, onBatch)
            } catch (e: IOException) {
                RawResult(ioError = e)
            }
            // 用户中止优先：携带半截内容抛出，绝不进入重试（§2.2 状态机）
            if (!coroutineContext.isActive) {
                throw ChatCanceledException(raw.content, raw.reasoning, raw.thinkMs)
            }
            val plan = RetryPolicy.plan(attempt, raw.code, raw.retryAfterMs)
            if (plan.shouldRetry) {
                delay(plan.delayMs)
                continue
            }
            return@withContext toOutcome(raw)
        }
        @Suppress("UNREACHABLE_CODE")
        toOutcome(RawResult())
    }

    /**
     * 联网搜索对话（A.4 实测修订：/responses 的 web_search 已于 2026-09-10 随 V4.1 下线，
     * 改走 Anthropic 兼容端点 POST /anthropic/v1/messages + 服务端 web_search_20250305；非流式）。
     * - 工具声明 {"type":"web_search_20250305","name":"web_search","max_uses":5}，服务端执行；
     * - 回应 content[] 含 text / server_tool_use / web_search_tool_result 块，来源随正文附加；
     * - 重试 / 中止策略与 chatCompletions 一致。
     */
    suspend fun anthropicMessages(
        apiKey: String,
        model: String,
        messages: List<JsonObject>,
    ): ApiOutcome = withContext(Dispatchers.IO) {
        acquireRate()
        var attempt = 0
        while (true) {
            attempt++
            val raw = try {
                doAnthropicMessages(apiKey, model, messages)
            } catch (e: IOException) {
                RawResult(ioError = e)
            }
            // 用户中止优先（同 chat 路径）
            if (!coroutineContext.isActive) {
                throw ChatCanceledException(raw.content, raw.reasoning, raw.thinkMs)
            }
            val plan = RetryPolicy.plan(attempt, raw.code, raw.retryAfterMs)
            if (plan.shouldRetry) {
                delay(plan.delayMs)
                continue
            }
            return@withContext toOutcome(raw)
        }
        @Suppress("UNREACHABLE_CODE")
        toOutcome(RawResult())
    }

    private suspend fun doAnthropicMessages(
        apiKey: String,
        model: String,
        messages: List<JsonObject>,
    ): RawResult {
        // 内部装配（OpenAI 风格）→ Anthropic Messages 格式：
        // system 提升为顶层字段；user / assistant 的 content 转为 [{type:"text"}] 内容块
        var systemPrompt: String? = null
        val anthMessages = JsonArray()
        messages.forEach { m ->
            val role = m.get("role")?.asString ?: return@forEach
            val raw = m.get("content")
            val text = raw?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            if (role == "system") {
                systemPrompt = text
            } else {
                anthMessages.add(JsonObject().apply {
                    addProperty("role", role)
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", text)
                        })
                    })
                })
            }
        }
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", 4096)
            systemPrompt?.takeIf { it.isNotBlank() }?.let { addProperty("system", it) }
            add("messages", anthMessages)
            // 服务端联网搜索（唯一仍受支持的内置搜索入口；§2.1 C1 / A.4）
            add("tools", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "web_search_20250305")
                    addProperty("name", "web_search")
                    addProperty("max_uses", 5)
                })
            })
        }
        val req = Request.Builder()
            .url("$BASE_URL/anthropic/v1/messages")
            .header("x-api-key", apiKey)
            .header("Authorization", "Bearer $apiKey")
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val call = okHttpClient.newCall(req)
        Log.w("ink-http", "→ POST /anthropic/v1/messages | " + body.toString().take(1200))
        val cancelWatcher = CoroutineScope(coroutineContext).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("ink-http", "← POST /anthropic/v1/messages ${resp.code} | " + (resp.body?.string() ?: "").take(2000))
                    val retryAfterMs = resp.header("Retry-After")?.let { h ->
                        h.toLongOrNull()?.times(1000)
                            ?: h.toDoubleOrNull()?.times(1000)?.toLong()
                    }
                    return RawResult(code = resp.code, retryAfterMs = retryAfterMs)
                }
                val respBody = resp.body?.string() ?: ""
                Log.w("ink-http", "← POST /anthropic/v1/messages 200 | " + respBody.take(4000))
                return parseAnthropic(respBody)
            }
        } finally {
            cancelWatcher.cancel()
        }
    }

    /** 余额查询（A.6；手动/启动刷新触发，不参与重试循环） */
    suspend fun queryBalance(apiKey: String): BalanceSnapshot? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE_URL/user/balance")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val obj = JsonParser.parseString(resp.body?.string() ?: return@withContext null).asJsonObject
                val info = obj.getAsJsonArray("balance_infos")?.firstOrNull()?.asJsonObject
                BalanceSnapshot(
                    available = obj.get("is_available")?.asBoolean ?: false,
                    currency = info?.get("currency")?.asString ?: "CNY",
                    totalBalance = info?.get("total_balance")?.asString ?: "0",
                    grantedBalance = info?.get("granted_balance")?.takeIf { !it.isJsonNull }?.asString,
                    toppedUpBalance = info?.get("topped_up_balance")?.takeIf { !it.isJsonNull }?.asString,
                    fetchedAt = System.currentTimeMillis(),
                )
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 模型列表（A.7；ID 含 deepseek 即纳入，避免 ID 变更导致下拉为空） */
    suspend fun listModels(apiKey: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE_URL/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val obj = JsonParser.parseString(resp.body?.string() ?: return@withContext null).asJsonObject
                obj.getAsJsonArray("data")
                    ?.mapNotNull { it.asJsonObject.get("id")?.asString }
                    ?.filter { it.contains("deepseek", ignoreCase = true) }
                    ?: emptyList()
            }
        } catch (t: Throwable) {
            null
        }
    }
    /**
     * 图片上传（A.5：POST /files，multipart，purpose=user_data）。
     * 成功返回 file_id；失败返回 null（调用方转文案）。
     */
    suspend fun uploadFile(
        apiKey: String,
        fileName: String,
        fileBody: RequestBody,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("purpose", "user_data")
                .addFormDataPart("file", fileName, fileBody)
                .build()
            val req = Request.Builder()
                .url("$BASE_URL/files")
                .header("Authorization", "Bearer $apiKey")
                .post(multipart)
                .build()
            // 大文件上传放宽写超时（≤64MiB，A.5）
            val client = okHttpClient.newBuilder()
                .writeTimeout(5, TimeUnit.MINUTES)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val obj = JsonParser.parseString(resp.body?.string() ?: return@withContext null).asJsonObject
                obj.get("id")?.takeIf { !it.isJsonNull }?.asString
                    ?: obj.get("file_id")?.takeIf { !it.isJsonNull }?.asString
            }
        } catch (t: Throwable) {
            null
        }
    }

    // —— 内部实现 ——

    private suspend fun doChat(
        apiKey: String,
        model: String,
        messages: List<JsonObject>,
        stream: Boolean,
        thinkingEnabled: Boolean,
        effort: String?,
        onBatch: (suspend (String, String) -> Unit)?,
    ): RawResult {
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", JsonArray().apply { messages.forEach { add(it) } })
            addProperty("stream", stream)
            if (stream) {
                // 流式：显式要求末段携带 usage（含缓存命中/未命中；OpenAI 兼容字段）
                add("stream_options", JsonObject().apply { addProperty("include_usage", true) })
            }
            // 思考模式开关与强度（A.2；思考模式不支持 temperature 系参数，一律不传）
            add(
                "thinking",
                JsonObject().apply {
                    addProperty("type", if (thinkingEnabled) "enabled" else "disabled")
                }
            )
            if (thinkingEnabled && !effort.isNullOrBlank()) addProperty("reasoning_effort", effort)
        }

        val req = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        // 流式场景禁用读取超时（§4.5）
        val client = if (stream) {
            okHttpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        } else {
            okHttpClient
        }

        val requestStartAt = SystemClock.elapsedRealtime()
        val call = client.newCall(req)
        // 中止杆：当前协程被取消时立即打断阻塞中的网络 IO。
        // 子协程监听取消信号（awaitCancellation），在 finally 中 call.cancel()；
        // 不用 Job 完成回调——那要等阻塞中的读取结束，会形成死锁。
        val cancelWatcher = CoroutineScope(coroutineContext).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("ink-http", "← POST /chat/completions ${resp.code} | " + (resp.body?.string() ?: "").take(2000))
                    val retryAfterMs = resp.header("Retry-After")?.let { h ->
                        h.toLongOrNull()?.times(1000)
                            ?: h.toDoubleOrNull()?.times(1000)?.toLong()
                    }
                    return RawResult(code = resp.code, retryAfterMs = retryAfterMs)
                }
                return if (stream) {
                    val reader = resp.body?.charStream()?.buffered()
                        ?: return RawResult(code = 200, parseFailed = true)
                    parseStream(reader, onBatch, requestStartAt)
                } else {
                    parseNonStream(resp.body?.string() ?: "")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            return RawResult(code = 200, parseFailed = true)
        } finally {
            cancelWatcher.cancel()
        }
    }

    /** chat/completions 非流式解析：choices[0].message */
    private fun parseNonStream(body: String): RawResult {
        return try {
            val obj = JsonParser.parseString(body).asJsonObject
            val msg = obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                ?.get("message")?.takeIf { !it.isJsonNull }?.asJsonObject
            val usage = obj.get("usage")?.takeIf { !it.isJsonNull }?.asJsonObject
            RawResult(
                code = 200,
                content = msg?.get("content")?.takeIf { !it.isJsonNull }?.asString ?: "",
                reasoning = msg?.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString ?: "",
                promptTokens = usage.intOrNull("prompt_tokens"),
                completionTokens = usage.intOrNull("completion_tokens"),
                cacheHitTokens = usage.intOrNull("prompt_cache_hit_tokens", "cache_hit_tokens"),
                cacheMissTokens = usage.intOrNull("prompt_cache_miss_tokens", "cache_miss_tokens"),
            )
        } catch (t: Throwable) {
            RawResult(code = 200, parseFailed = true)
        }
    }

    /**
     * Anthropic Messages 非流式解析：
     * content[] 块 → text（正文）/ thinking（思维链）/ web_search_tool_result（来源）。
     * 来源随正文附「引用块」清单（M5.7 版式）：——参考来源—— + [n] 标题 + URL，逐条两行；
     * URL 去重、标题缺省取域名；上限 [MAX_SOURCES] 条（墨水屏阅读友好）。
     */
    private fun parseAnthropic(body: String): RawResult {
        return try {
            val obj = JsonParser.parseString(body).asJsonObject
            val content = StringBuilder()
            val reasoning = StringBuilder()
            /** 来源（标题 to URL；标题缺省已回退为域名） */
            val sources = mutableListOf<Pair<String, String>>()
            val seenUrls = mutableSetOf<String>()
            obj.getAsJsonArray("content")?.forEach { item ->
                val c = item.asJsonObject
                when (c.get("type")?.asString) {
                    "text" -> c.get("text")?.takeIf { !it.isJsonNull }?.asString?.let(content::append)
                    "thinking" -> (c.get("thinking") ?: c.get("reasoning") ?: c.get("text"))
                        ?.takeIf { !it.isJsonNull }?.asString?.let(reasoning::append)
                    "web_search_tool_result" -> {
                        c.getAsJsonArray("content")?.forEach resultLoop@{ r ->
                            if (sources.size >= MAX_SOURCES || r.isJsonNull) return@resultLoop
                            val ro = r.asJsonObject
                            val url = ro.get("url")?.takeIf { !it.isJsonNull }?.asString ?: return@resultLoop
                            if (!seenUrls.add(url)) return@resultLoop // M5.7：同 URL 去重
                            val title = ro.get("title")?.takeIf { !it.isJsonNull }?.asString
                                ?.trim()?.takeIf { it.isNotEmpty() } ?: domainOf(url)
                            sources += title to url
                        }
                    }
                }
            }
            val text = if (sources.isEmpty()) {
                content.toString()
            } else {
                buildString {
                    append(content)
                    append("\n\n> ——参考来源——")
                    sources.forEachIndexed { i, (title, url) ->
                        append("\n> [").append(i + 1).append("] ").append(title)
                        append("\n> ").append(url)
                    }
                }
            }
            val usage = obj.get("usage")?.takeIf { !it.isJsonNull }?.asJsonObject
            val inputTokens = usage.intOrNull("input_tokens", "prompt_tokens")
            val hitTokens = usage.intOrNull("cache_read_input_tokens", "prompt_cache_hit_tokens")
            RawResult(
                code = 200,
                content = text,
                reasoning = reasoning.toString(),
                promptTokens = inputTokens,
                completionTokens = usage.intOrNull("output_tokens", "completion_tokens"),
                cacheHitTokens = hitTokens,
                cacheMissTokens = usage.intOrNull("cache_miss_tokens", "prompt_cache_miss_tokens")
                    ?: inputTokens?.let { p -> hitTokens?.let { h -> (p - h).coerceAtLeast(0) } },
            )
        } catch (t: Throwable) {
            RawResult(code = 200, parseFailed = true)
        }
    }

    private suspend fun parseStream(
        reader: BufferedReader,
        onBatch: (suspend (String, String) -> Unit)?,
        requestStartAt: Long,
    ): RawResult {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        var cacheHitTokens: Int? = null
        var cacheMissTokens: Int? = null
        var firstContentAt: Long? = null
        var lastEmit = 0
        try {
            for (line in reader.lineSequence()) {
                val chunk = StreamParser.parseSseLine(line) ?: continue
                chunk.content?.let {
                    if (it.isNotEmpty() && firstContentAt == null) {
                        firstContentAt = SystemClock.elapsedRealtime()
                    }
                    content.append(it)
                }
                chunk.reasoning?.let(reasoning::append)
                chunk.usage?.let { u ->
                    promptTokens = u.intOrNull("prompt_tokens") ?: promptTokens
                    completionTokens = u.intOrNull("completion_tokens") ?: completionTokens
                    cacheHitTokens = u.intOrNull("prompt_cache_hit_tokens", "cache_hit_tokens") ?: cacheHitTokens
                    cacheMissTokens = u.intOrNull("prompt_cache_miss_tokens", "cache_miss_tokens") ?: cacheMissTokens
                }
                if (onBatch != null) {
                    val total = content.length + reasoning.length
                    val paragraph = content.endsWith("\n\n") || reasoning.endsWith("\n\n")
                    if (total - lastEmit >= STREAM_BATCH_CHARS || paragraph) {
                        lastEmit = total
                        onBatch(content.toString(), reasoning.toString())
                    }
                }
            }
        } catch (e: IOException) {
            // 流中断（含用户中止）：保留已收内容，按失败处理（内容不丢）
            return RawResult(
                code = 200,
                content = content.toString(),
                reasoning = reasoning.toString(),
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                cacheHitTokens = cacheHitTokens,
                cacheMissTokens = cacheMissTokens,
                thinkMs = thinkDuration(firstContentAt, reasoning, requestStartAt),
                ioError = e,
            )
        }
        onBatch?.invoke(content.toString(), reasoning.toString())
        return RawResult(
            code = 200,
            content = content.toString(),
            reasoning = reasoning.toString(),
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cacheHitTokens = cacheHitTokens,
            cacheMissTokens = cacheMissTokens,
            thinkMs = thinkDuration(firstContentAt, reasoning, requestStartAt),
        )
    }

    /** 思考时长：确有思维链内容时记录（首正文抵达 − 请求发出）；无信号返回 null */
    private fun thinkDuration(firstContentAt: Long?, reasoning: CharSequence, requestStartAt: Long): Long? =
        if (firstContentAt != null && reasoning.isNotEmpty()) firstContentAt - requestStartAt else null

    private fun toOutcome(raw: RawResult): ApiOutcome = when {
        raw.ioError != null -> ApiOutcome(
            content = raw.content,
            reasoning = raw.reasoning,
            thinkMs = raw.thinkMs,
            human = ApiErrorMapper.fromIo(raw.ioError),
        )
        raw.parseFailed -> ApiOutcome(human = ApiErrorMapper.fromParse())
        raw.code != 200 -> ApiOutcome(human = ApiErrorMapper.fromHttp(raw.code ?: 0))
        else -> {
            // 缓存未命中缺省推导：命中已知而未命中未知时 = 输入 − 命中（兼容端点/旧响应兜底）
            val miss = raw.cacheMissTokens
                ?: raw.promptTokens?.let { p -> raw.cacheHitTokens?.let { h -> (p - h).coerceAtLeast(0) } }
            ApiOutcome(
                content = raw.content,
                reasoning = raw.reasoning,
                promptTokens = raw.promptTokens,
                completionTokens = raw.completionTokens,
                cacheHitTokens = raw.cacheHitTokens,
                cacheMissTokens = miss,
                thinkMs = raw.thinkMs,
            )
        }
    }
}

/**
 * 用户中止（停止按钮）时抛出，携带未完成内容。
 * 是 CancellationException 子类：结构化并发语义不变，但上层可在落库时回收半截内容（§2.2）。
 */
class ChatCanceledException(
    val content: String = "",
    val reasoning: String = "",
    val thinkMs: Long? = null,
) : CancellationException("generation canceled by user")

/** 宽容取整型字段（多候选键、容忍 null 节点）；M5.6 供 usage 解析（缓存命中/未命中） */
private fun JsonObject?.intOrNull(vararg keys: String): Int? {
    if (this == null) return null
    keys.forEach { k ->
        val v = get(k) ?: return@forEach
        if (!v.isJsonNull) return v.asInt
    }
    return null
}

/** 从 URL 提取域名（来源标题缺省回退；M5.7） */
private fun domainOf(url: String): String =
    url.substringAfter("://", url).substringBefore('/').ifEmpty { url }