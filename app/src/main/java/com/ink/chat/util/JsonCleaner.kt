package com.ink.chat.util

/** JSON 容错清洗（移植自 novel-agent）：剥围栏、去尾逗号。供 M5 联网/工具场景使用。 */
object JsonCleaner {

    private val TRAILING_COMMA = Regex(",\\s*([}\\]])")

    fun clean(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        }
        s = TRAILING_COMMA.replace(s, "$1")
        return s
    }
}