package com.ink.chat.ui.sessions

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.chat.data.repo.ChatRepository
import com.ink.chat.data.repo.ConversationHolder
import com.ink.chat.data.transfer.TransferCodec
import com.ink.chat.domain.model.ConversationRow
import com.ink.chat.domain.model.Message
import com.ink.chat.domain.model.SendOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 会话列表页 ViewModel（M3，§2.1 模块 B）：
 * 列表 / 搜索（防抖）/ 新建（空闲复用）/ 改名 / 置顶 / 删除 / 跳转弹窗 / 总结 / 单会话导出。
 * 打开会话与跳转只更新共享 [ConversationHolder]，由 UI 弹回对话页（导航栈恒为两层）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModel(
    private val repo: ChatRepository,
    private val holder: ConversationHolder,
) : ViewModel() {

    // —— 搜索（300ms 防抖；墨水屏块级刷新，不随输入逐字查询）——
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _activeQuery = MutableStateFlow("")

    /** 生效中的查询串（防抖后；空态文案据此区分「暂无对话 / 没有匹配」） */
    val activeQuery: StateFlow<String> = _activeQuery

    private var searchJob: Job? = null

    /** null = 首次加载中（不显示空态，避免闪文案） */
    val rows: StateFlow<List<ConversationRow>?> = _activeQuery
        .flatMapLatest { q -> if (q.isEmpty()) repo.conversationRows() else repo.searchRows(q) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onQueryChange(q: String) {
        _query.value = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            _activeQuery.value = q.trim()
        }
    }

    // —— 提示条 ——
    data class Notice(val text: String, val isError: Boolean = false)

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice

    fun dismissNotice() {
        _notice.value = null
    }

    // —— 总结中状态（按钮变「总结中…」并禁点）——
    private val _summarizing = MutableStateFlow<Long?>(null)
    val summarizing: StateFlow<Long?> = _summarizing

    // —— 跳转弹窗（消息摘要列表）——
    private val _jump = MutableStateFlow<List<Message>?>(null)
    val jumpMessages: StateFlow<List<Message>?> = _jump

    // —— 会话操作 ——

    /** 打开会话：切槽位 → UI 弹回对话页 */
    fun open(id: Long, done: () -> Unit) {
        holder.current.value = id
        done()
    }

    /** 新建：空闲会话复用，否则新建（§2.1 B1） */
    fun newConversation(done: () -> Unit) {
        viewModelScope.launch {
            holder.current.value = repo.findBlankOrCreate()
            done()
        }
    }

    fun rename(id: Long, title: String) {
        viewModelScope.launch { repo.renameConversation(id, title) }
    }

    fun togglePin(row: ConversationRow) {
        viewModelScope.launch { repo.togglePin(row.id, row.pinned) }
    }

    /** 删除会话（二次确认由 UI 承担）；删除当前会话时自动切到最近会话 */
    fun delete(id: Long) {
        viewModelScope.launch {
            repo.deleteConversation(id)
            if (holder.current.value == id) {
                holder.current.value = repo.ensureConversation()
            }
        }
    }

    // —— 消息跳转弹窗（§2.1 B7）——

    fun openJumpDialog(id: Long) {
        viewModelScope.launch { _jump.value = repo.listMessages(id) }
    }

    fun dismissJumpDialog() {
        _jump.value = null
    }

    /** 跳到目标消息：切会话 + 设待处理跳转 → UI 弹回对话页由对话页定位 */
    fun jumpToMessage(msg: Message, done: () -> Unit) {
        holder.current.value = msg.conversationId
        holder.pendingJump.value = msg.id
        _jump.value = null
        done()
    }

    // —— 对话总结（§2.1 B8）——

    fun summarize(id: Long) {
        if (_summarizing.value != null) return
        viewModelScope.launch {
            _summarizing.value = id
            _notice.value = Notice("正在生成摘要…")
            val outcome = repo.summarize(id)
            _notice.value = when (outcome) {
                SendOutcome.Success -> Notice("已生成摘要，已追加到会话末尾。")
                SendOutcome.NoApiKey -> Notice("请先在设置里填写 API Key。")
                SendOutcome.Canceled -> Notice("已取消。")
                is SendOutcome.Failure -> Notice(outcome.humanError, isError = true)
            }
            _summarizing.value = null
        }
    }

    // —— 单会话导出（§2.1 B9；SAF 由 UI 发起，回调传入 uri）——

    fun exportOne(id: Long, resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    val payload = repo.buildExport(id)
                    resolver.openOutputStream(uri)?.use { TransferCodec.write(payload, it) }
                        ?: error("无法写入所选位置")
                }
            }.isSuccess
            _notice.value = if (ok) Notice("已导出 1 个会话。") else Notice("导出失败，请重试。", isError = true)
        }
    }

    // —— 批量管理（M5.5）——

    private val _manageMode = MutableStateFlow(false)
    val manageMode: StateFlow<Boolean> = _manageMode

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected

    fun enterManage() {
        _selected.value = emptySet()
        _manageMode.value = true
    }

    fun exitManage() {
        _manageMode.value = false
        _selected.value = emptySet()
    }

    fun toggleSelect(id: Long) {
        _selected.value = if (id in _selected.value) _selected.value - id else _selected.value + id
    }

    /** 全选 / 清空（作用于当前列表——含搜索过滤后的结果） */
    fun toggleSelectAll(rows: List<ConversationRow>?) {
        val ids = rows.orEmpty().map { it.id }.toSet()
        _selected.value = if (ids.isNotEmpty() && _selected.value.containsAll(ids)) emptySet() else ids
    }

    /** 删除所选（二次确认由 UI 承担；删除当前会话时自动切到最近会话） */
    fun deleteSelected() {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repo.deleteConversations(ids.toList())
            if (holder.current.value in ids) {
                holder.current.value = repo.ensureConversation()
            }
            _notice.value = Notice("已删除 ${ids.size} 个会话。")
            _selected.value = emptySet()
            _manageMode.value = false
        }
    }

    /** 导出所选（SAF 由 UI 发起，回调传入 uri；ids 为点击「导出所选」时的快照） */
    fun exportSelected(ids: Set<Long>, resolver: ContentResolver, uri: Uri) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    val payload = repo.buildExportSelected(ids.toList())
                    resolver.openOutputStream(uri)?.use { TransferCodec.write(payload, it) }
                        ?: error("无法写入所选位置")
                }
            }.isSuccess
            _notice.value = if (ok) Notice("已导出 ${ids.size} 个会话。") else Notice("导出失败，请重试。", isError = true)
            if (ok) {
                _selected.value = emptySet()
                _manageMode.value = false
            }
        }
    }
}