package com.ink.chat.domain.model

/**
 * 思考强度档位（框架 §2.1-A2）。
 * OFF 对应 thinking.disabled；其余对应 reasoning_effort。
 */
enum class ThinkLevel(val label: String, val effort: String?) {
    OFF("关闭", null),
    LOW("低", "low"),
    HIGH("高", "high"),
    MAX("最大", "max")
}