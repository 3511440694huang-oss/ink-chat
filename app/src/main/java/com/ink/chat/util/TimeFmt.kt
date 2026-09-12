package com.ink.chat.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 时间与数字格式化（消息头元信息用） */
object TimeFmt {

    private val HHMM = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val MD = SimpleDateFormat("M/d", Locale.US)
    private val YMD = SimpleDateFormat("yyyy/M/d", Locale.US)

    private val WEEK = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    fun hhmm(ts: Long): String = HHMM.format(Date(ts))

    /**
     * 会话列表相对时间：今天 / 昨天 / 周X（7 天内）/ M/d（今年）/ yyyy/M/d。
     */
    fun relDay(ts: Long, now: Long = System.currentTimeMillis()): String {
        val today = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val day = Calendar.getInstance().apply {
            timeInMillis = ts
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diff = (today.timeInMillis - day.timeInMillis) / 86_400_000L
        return when {
            diff <= 0L -> "今天"
            diff == 1L -> "昨天"
            diff < 7L -> WEEK[day.get(Calendar.DAY_OF_WEEK) - 1]
            day.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> MD.format(Date(ts))
            else -> YMD.format(Date(ts))
        }
    }

    /** 1200 → "1.2k"；950 → "950" */
    fun tokens(n: Int?): String? = n?.let {
        if (it >= 1000) String.format(Locale.US, "%.1fk", it / 1000.0) else "$it"
    }

    // —— 统计区间（余额页「今日 / 本月 / 上月」，M4）——

    /** 今天 00:00:00.000（本地时区） */
    fun startOfToday(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 本月 1 日 00:00:00.000 */
    fun startOfMonth(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 上月 1 日 00:00:00.000（先落本月 1 日再退月，避免 31 日溢出问题） */
    fun startOfLastMonth(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, -1)
        }.timeInMillis
}