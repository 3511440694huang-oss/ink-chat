package com.ink.chat.network

import kotlin.random.Random

/**
 * 重试策略（§4.5 / 附录 A.8）。
 * - 429：指数退避 1s → 2s → 4s（±50% 抖动），最多 3 次；尊重服务端 Retry-After
 * - 5xx / IO 异常：最多 2 次
 */
object RetryPolicy {

    private const val MAX_RETRY_429 = 3
    private const val MAX_RETRY_5XX = 2

    data class Plan(val shouldRetry: Boolean, val delayMs: Long)

    /**
     * @param attempt 已进行的尝试次数（从 1 开始，表示刚失败的是第 attempt 次）
     * @param code HTTP 状态码；null 表示 IO 异常
     * @param retryAfterMs 服务端 Retry-After 头（毫秒，若有）
     */
    fun plan(attempt: Int, code: Int?, retryAfterMs: Long? = null): Plan {
        val isRateLimit = code == 429
        val isServerOrIo = code == null || code in 500..599
        if (!isRateLimit && !isServerOrIo) return Plan(false, 0)

        val overBudget = if (isRateLimit) attempt > MAX_RETRY_429 else attempt > MAX_RETRY_5XX
        if (overBudget) return Plan(false, 0)

        // 服务端明确指定等待时间 → 优先尊重（上限 60s）
        retryAfterMs?.let { if (it > 0) return Plan(true, it.coerceAtMost(60_000)) }

        val base = (1L shl (attempt - 1)) * 1000L // 1s, 2s, 4s, 8s
        val jitter = (base * 0.5 * (Random.nextDouble() * 2 - 1)).toLong() // ±50%
        return Plan(true, (base + jitter).coerceAtLeast(500))
    }
}