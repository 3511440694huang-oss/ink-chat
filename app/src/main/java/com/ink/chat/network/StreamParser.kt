package com.ink.chat.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * SSE 流式解析（移植自 novel-agent，裁剪为聊天场景）。
 * 仅区分 content（正文）与 reasoning_content（思维链）——
 * 思维链绝不写入历史上下文（回传规则见 §4.5，仅联网 tools 场景例外）。
 */
object StreamParser {

    data class Chunk(
        val content: String? = null,
        val reasoning: String? = null,
        val finishReason: String? = null,
        val usage: JsonObject? = null,
    )

    /** 解析一行 SSE（形如 "data: {...}"）。返回 null 表示非数据行或解析失败（容错跳过）。 */
    fun parseSseLine(line: String): Chunk? {
        val t = line.trim()
        if (!t.startsWith("data:")) return null
        val payload = t.substring(5).trim()
        if (payload.isEmpty()) return null
        if (payload == "[DONE]") return Chunk(finishReason = "stop")
        return try {
            val obj = JsonParser.parseString(payload).asJsonObject
            val choice = obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            val delta = choice?.get("delta")?.asJsonObject
            Chunk(
                content = delta?.get("content")?.takeIf { !it.isJsonNull }?.asString,
                reasoning = delta?.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString,
                finishReason = choice?.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString,
                usage = obj.get("usage")?.takeIf { !it.isJsonNull }?.asJsonObject,
            )
        } catch (t: Throwable) {
            null
        }
    }
}