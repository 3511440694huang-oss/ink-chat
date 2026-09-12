package com.ink.chat.data.repo

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 当前会话槽位（全局单例，M3 多会话模型）。
 *
 * 设计：对话页与列表页共享同一个「当前会话」状态——
 * 列表页点会话 / 新建 / 跳转，只更新本持有器并弹回对话页（导航栈永远两层），
 * 对话页 ViewModel 观察 [current] 自动切换数据流。避免参数化路由导致的栈堆积。
 */
class ConversationHolder {

    /** 当前会话 id；null = 尚未挂载（聊天页启动时取最近会话或新建） */
    val current = MutableStateFlow<Long?>(null)

    /** 待处理的消息跳转（列表页「跳到…」→ 对话页滚动目标消息后消费） */
    val pendingJump = MutableStateFlow<Long?>(null)
}
