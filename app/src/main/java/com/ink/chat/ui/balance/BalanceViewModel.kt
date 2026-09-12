package com.ink.chat.ui.balance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.chat.data.repo.ChatRepository
import com.ink.chat.data.repo.SettingsRepository
import com.ink.chat.domain.model.BalanceSnapshot
import com.ink.chat.util.TimeFmt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 余额页 ViewModel（M4 §2.1 D2：余额查询 + 用量统计）。
 * 余额读 DataStore 缓存流（任一页面刷新后全局可见）；用量来自 usage_logs 区间聚合。
 */
class BalanceViewModel(
    private val settingsRepo: SettingsRepository,
    private val chatRepo: ChatRepository,
) : ViewModel() {

    /** 余额缓存（DataStore 流；null = 尚无缓存） */
    val balance: StateFlow<BalanceSnapshot?> =
        settingsRepo.balanceFlow().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _usage = MutableStateFlow(UsageBundle())
    val usage: StateFlow<UsageBundle> = _usage

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    init {
        viewModelScope.launch { reloadUsage() }
    }

    /** 手动刷新（余额 + 用量；§2.1 D2「启动 + 手动刷新」） */
    fun refresh() {
        viewModelScope.launch {
            if (settingsRepo.current().apiKeyEnc.isBlank()) {
                _notice.value = "先设置 API Key。"
                return@launch
            }
            _refreshing.value = true
            val snap = settingsRepo.refreshBalance()
            _notice.value = when {
                snap == null -> "余额查询失败，请检查网络或 API Key。"
                !snap.available -> "！余额不足，请充值后再试。"
                else -> "余额已刷新。"
            }
            reloadUsage()
            _refreshing.value = false
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private suspend fun reloadUsage() {
        val now = System.currentTimeMillis()
        val todayStart = TimeFmt.startOfToday(now)
        val monthStart = TimeFmt.startOfMonth(now)
        val lastMonthStart = TimeFmt.startOfLastMonth(now)
        val today = chatRepo.usageBetween(todayStart, now + 1)
        val month = chatRepo.usageBetween(monthStart, now + 1)
        val last = chatRepo.usageBetween(lastMonthStart, monthStart)
        _usage.value = UsageBundle(
            todayIn = today.inputTokens ?: 0,
            todayOut = today.outputTokens ?: 0,
            monthIn = month.inputTokens ?: 0,
            monthOut = month.outputTokens ?: 0,
            lastMonthIn = last.inputTokens ?: 0,
            lastMonthOut = last.outputTokens ?: 0,
        )
    }
}

/** 余额页用量数据（token；今日 / 本月 / 上月） */
data class UsageBundle(
    val todayIn: Int = 0,
    val todayOut: Int = 0,
    val monthIn: Int = 0,
    val monthOut: Int = 0,
    val lastMonthIn: Int = 0,
    val lastMonthOut: Int = 0,
) {
    val monthTotal: Int get() = monthIn + monthOut
    val lastMonthTotal: Int get() = lastMonthIn + lastMonthOut
}