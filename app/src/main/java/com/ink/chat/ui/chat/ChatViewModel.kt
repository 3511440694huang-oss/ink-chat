package com.ink.chat.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.chat.data.datastore.AppSettings
import com.ink.chat.data.repo.ChatRepository
import com.ink.chat.data.repo.ConversationHolder
import com.ink.chat.data.repo.SettingsRepository
import com.ink.chat.data.repo.TextFileManager
import com.ink.chat.data.repo.UploadManager
import com.ink.chat.domain.model.ConversationUsage
import com.ink.chat.domain.model.ErrorAction
import com.ink.chat.domain.model.Message
import com.ink.chat.domain.model.OutgoingAttachment
import com.ink.chat.domain.model.OutgoingTextFile
import com.ink.chat.domain.model.SendOutcome
import com.ink.chat.domain.model.ThinkLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 对话页 ViewModel（M3：多会话——观察共享的 [ConversationHolder] 自动切换数据流）。
 * 消息列表直接观察 Room（单一真相源）；发送结果按 §3.5 文案库转内联提示。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val chatRepo: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val holder: ConversationHolder,
    private val uploader: UploadManager,
    private val fileReader: TextFileManager,
) : ViewModel() {

    /** 当前会话（列表页切换会话 / 新建时由 holder 驱动；null = 尚未挂载） */
    val conversationId: StateFlow<Long?> = holder.current

    /** 待处理的消息跳转（列表页「跳到…」设置；对话页滚动后消费） */
    val pendingJump: StateFlow<Long?> = holder.pendingJump

    val messages: StateFlow<List<Message>> = holder.current
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else chatRepo.messagesOf(id)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 本次对话用量汇总（token / 缓存命中率 / 花费；随成功轮次实时刷新） */
    val usage: StateFlow<ConversationUsage?> = holder.current
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else chatRepo.usageOf(id)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    data class Notice(
        val text: String,
        val isError: Boolean = false,
        val action: ErrorAction = ErrorAction.NONE,
    )

    /** 已就绪的待发图片（M5：file_id + 显示文件名） */
    data class UploadedImage(val fileId: String, val fileName: String)

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    // —— 图片上传（M5 §2.1 C2）——
    private val _uploadingImage = MutableStateFlow(false)
    val uploadingImage: StateFlow<Boolean> = _uploadingImage

    private val _pendingImage = MutableStateFlow<UploadedImage?>(null)
    val pendingImage: StateFlow<UploadedImage?> = _pendingImage

    // —— 文件上传（M5.5：纯文本文件本地解析）——
    /** 已解析的待发文件（truncated = 超过 256KB 被截断） */
    data class AttachedFile(val fileName: String, val content: String, val truncated: Boolean)

    private val _uploadingFile = MutableStateFlow(false)
    val uploadingFile: StateFlow<Boolean> = _uploadingFile

    private val _pendingFile = MutableStateFlow<AttachedFile?>(null)
    val pendingFile: StateFlow<AttachedFile?> = _pendingFile

    // —— 模板短语（M5 §2.1 A13）——
    val phrases: StateFlow<List<String>> = settingsRepo.phrasesFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice

    /** 当前生成任务（发送/重试共用）；点「停止」时取消它（§2.1 A4） */
    private var sendJob: Job? = null

    init {
        viewModelScope.launch {
            // 清扫上次运行遗留的悬挂 sending（进程被杀等）再挂载会话
            chatRepo.cleanupDanglingSending()
            // 已有槽位（列表页切换/新建过）则沿用；否则取最近会话或新建
            if (holder.current.value == null) {
                holder.current.value = chatRepo.ensureConversation()
            }
        }
    }

    /** 对话页完成目标消息跳转后调用（清除待处理标记） */
    fun consumeJump() {
        holder.pendingJump.value = null
    }

    /** 顶栏「新对话」：空闲会话复用，否则新建（与列表页 + 同口径）；已在空会话时给出反馈 */
    fun newConversation() {
        viewModelScope.launch {
            val target = chatRepo.findBlankOrCreate()
            if (holder.current.value == target) {
                _notice.value = Notice("已在新对话中。")
            } else {
                holder.current.value = target
            }
        }
    }

    /** 发送一条消息（M5：可选携带已上传图片；M5.5：可选携带文本文件；空文本 + 附件允许发送） */
    fun send(text: String) {
        val t = text.trim()
        val img = _pendingImage.value
        val file = _pendingFile.value
        if ((t.isEmpty() && img == null && file == null) || _sending.value) return
        if (_uploadingImage.value) {
            _notice.value = Notice("图片正在上传，请稍候。")
            return
        }
        if (_uploadingFile.value) {
            _notice.value = Notice("文件正在读取，请稍候。")
            return
        }
        sendJob = viewModelScope.launch {
            val id = holder.current.filterNotNull().first()
            _sending.value = true
            // 附件随发送动作消费（失败可经「重新生成」重发原图 / 原文）
            _pendingImage.value = null
            _pendingFile.value = null
            try {
                val att = img?.let { listOf(OutgoingAttachment(it.fileId, it.fileName)) } ?: emptyList()
                val files = file?.let { listOf(OutgoingTextFile(it.fileName, it.content)) } ?: emptyList()
                handleOutcome(chatRepo.send(id, t, att, files))
            } finally {
                _sending.value = false
            }
        }
    }

    /** 选择图片 → 预校验 → 上传（§2.1 C2）；结果按 §3.5 文案转内联提示 */
    fun attachImage(uri: Uri) {
        if (_uploadingImage.value) return
        viewModelScope.launch {
            _uploadingImage.value = true
            when (val r = uploader.upload(uri)) {
                is UploadManager.Result.Ok -> _pendingImage.value = UploadedImage(r.fileId, r.fileName)
                UploadManager.Result.TooBig ->
                    _notice.value = Notice("图片超过 64MB 上限，未上传。", isError = true)
                UploadManager.Result.Unsupported ->
                    _notice.value = Notice("不支持的图片格式（支持 JPEG / PNG / GIF / WebP）。", isError = true)
                UploadManager.Result.NoApiKey ->
                    _notice.value = Notice("请先在设置里填写 API Key。", action = ErrorAction.GO_SETTINGS)
                UploadManager.Result.Failed ->
                    _notice.value = Notice("图片上传失败，请重试。", isError = true)
            }
            _uploadingImage.value = false
        }
    }

    /** 移除待发送图片（已上传服务端的文件不回收——服务端 30 天自动过期） */
    fun clearPendingImage() {
        _pendingImage.value = null
    }

    /** 选择文件 → 本地解析（纯文本）→ 待发（M5.5）；结果按文案转内联提示 */
    fun attachFile(uri: Uri) {
        if (_uploadingFile.value) return
        viewModelScope.launch {
            _uploadingFile.value = true
            when (val r = fileReader.read(uri)) {
                is TextFileManager.Result.Ok -> {
                    _pendingFile.value = AttachedFile(r.fileName, r.content, r.truncated)
                    if (r.truncated) _notice.value = Notice("文件较大，已截断到 256KB。")
                }
                is TextFileManager.Result.Unsupported ->
                    _notice.value = Notice("暂只支持纯文本文件（txt / md / json / csv / 代码等）。", isError = true)
                TextFileManager.Result.Empty ->
                    _notice.value = Notice("文件是空的。", isError = true)
                TextFileManager.Result.Failed ->
                    _notice.value = Notice("文件读取失败，请重试。", isError = true)
            }
            _uploadingFile.value = false
        }
    }

    /** 移除待发文件 */
    fun clearPendingFile() {
        _pendingFile.value = null
    }

    /** 联网开关（持久设置；输入区 chip 与设置页共用，§2.1 C1/D5） */
    fun toggleWebSearch() {
        viewModelScope.launch {
            settingsRepo.setWebSearch(!settingsRepo.current().webSearch)
        }
    }

    /** 追加 / 删除模板短语（§2.1 A13） */
    fun addPhrase(text: String) {
        viewModelScope.launch { settingsRepo.addPhrase(text) }
    }

    fun removePhrase(index: Int) {
        viewModelScope.launch { settingsRepo.removePhrase(index) }
    }

    /** 停止生成：取消请求；半截内容由 Repository 以 canceled 落库（§2.1 A4） */
    fun stop() {
        sendJob?.cancel()
    }

    /** 重试 / 重新生成（§2.1 A5/A10）：复用目标回答行、原样重发 */
    fun retry(message: Message) {
        if (_sending.value) return
        sendJob = viewModelScope.launch {
            val id = holder.current.filterNotNull().first()
            _sending.value = true
            try {
                handleOutcome(chatRepo.regenerate(id, message.id))
            } finally {
                _sending.value = false
            }
        }
    }

    /** 单条删除（§2.1 A8） */
    fun deleteMessage(message: Message) {
        viewModelScope.launch { chatRepo.deleteMessage(message.id) }
    }

    private fun handleOutcome(outcome: SendOutcome) {
        when (outcome) {
            SendOutcome.Success, SendOutcome.Canceled -> Unit // 中止无需提示，消息块自带标注
            SendOutcome.NoApiKey -> _notice.value =
                Notice("请先在设置里填写 API Key。", isError = false, action = ErrorAction.GO_SETTINGS)
            is SendOutcome.Failure -> _notice.value =
                Notice(outcome.humanError, isError = true, action = outcome.action)
        }
    }

    fun setThinkLevel(level: ThinkLevel) {
        viewModelScope.launch { settingsRepo.setThinkLevel(level) }
    }

    /** 无 Key 时的引导提示（带「去设置」动作，UI 侧预检调用） */
    fun notifyNoApiKey() {
        _notice.value = Notice("请先在设置里填写 API Key。", isError = false, action = ErrorAction.GO_SETTINGS)
    }

    fun notify(text: String) {
        _notice.value = Notice(text)
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun thinkLevelOf(s: AppSettings): ThinkLevel = settingsRepo.thinkLevelOf(s)
}