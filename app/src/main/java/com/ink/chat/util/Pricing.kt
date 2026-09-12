package com.ink.chat.util

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * DeepSeek 价目表（人民币，2026-09 官方页快照；单位：元 / 百万 tokens）。
 * 峰谷：高峰 = 北京时间周一至周五 9:00-12:00、14:00-18:00；空闲价 = 高峰价的一半（官方规则）。
 * 说明：仅用于客户端估算展示，实际扣费以官方账单为准。
 */
object Pricing {

    /** 单档价格（元 / 1M tokens） */
    data class Rate(val hit: Double, val miss: Double, val out: Double) {
        /** 空闲时段价 = 高峰价的一半 */
        fun offPeak(): Rate = Rate(hit / 2, miss / 2, out / 2)
    }

    // 高峰价（元 / 1M tokens）
    private val FLASH_PEAK = Rate(hit = 0.04, miss = 2.0, out = 8.0)
    private val PRO_PEAK = Rate(hit = 0.30, miss = 9.0, out = 27.0)

    /** 北京时间（峰谷判定基准，不随设备时区变化） */
    private val BEIJING: TimeZone = TimeZone.getTimeZone("GMT+8")

    /** 是否高峰时段（北京时间，工作日 9-12 / 14-18） */
    fun isPeak(atMillis: Long): Boolean {
        val cal = Calendar.getInstance(BEIJING).apply { timeInMillis = atMillis }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false
        val h = cal.get(Calendar.HOUR_OF_DAY)
        return h in 9..11 || h in 14..17
    }

    /** 按模型名取价：含 "pro" → V4 Pro；其余（flash / 旧名 / vision 名）→ Flash（官方按 Flash 计费） */
    fun rateOf(model: String?, peak: Boolean): Rate {
        val base = if (model?.contains("pro", ignoreCase = true) == true) PRO_PEAK else FLASH_PEAK
        return if (peak) base else base.offPeak()
    }

    /** 单轮花费估算（元）：命中×命中价 + 未命中×未命中价 + 输出×输出价 */
    fun costOf(model: String?, atMillis: Long, hitTokens: Int, missTokens: Int, outTokens: Int): Double {
        val r = rateOf(model, isPeak(atMillis))
        return (hitTokens * r.hit + missTokens * r.miss + outTokens * r.out) / 1_000_000.0
    }

    /** 金额格式化（¥，按量级取小数位：≥1 两位 / ≥0.01 三位 / 其余四位） */
    fun money(cny: Double): String = "¥" + when {
        cny >= 1 -> String.format(Locale.US, "%.2f", cny)
        cny >= 0.01 -> String.format(Locale.US, "%.3f", cny)
        else -> String.format(Locale.US, "%.4f", cny)
    }
}
