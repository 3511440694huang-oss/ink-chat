package com.ink.chat.ui.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.chat.BuildConfig
import com.ink.chat.data.backup.BackupManager
import com.ink.chat.data.datastore.AppSettings
import com.ink.chat.data.fonts.FontManager
import com.ink.chat.data.repo.ChatRepository
import com.ink.chat.data.repo.SettingsRepository
import com.ink.chat.data.transfer.TransferCodec
import com.ink.chat.domain.model.BalanceSnapshot
import com.ink.chat.domain.model.ThinkLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 设置页 ViewModel（M1：账户与对话默认值；M3：数据区块；M5.7：提示词 / 字体 / 备份恢复） */
class SettingsViewModel(
    private val repo: SettingsRepository,
    private val chatRepo: ChatRepository,
    private val backupManager: BackupManager,
    private val fontManager: FontManager,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    private val _keyHint = MutableStateFlow("—")
    val keyHint: StateFlow<String> = _keyHint

    /** 余额文案：从缓存流派生（任一页面刷新后自动更新；null → "—"） */
    val balanceText: StateFlow<String> = repo.balanceFlow()
        .map { it?.let { b -> formatBalance(b) } ?: "—" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "—")

    /** 模型列表（缓存 + 内置兜底；启动 / 进页静默刷新） */
    private val _models = MutableStateFlow(DEFAULT_MODELS)
    val models: StateFlow<List<String>> = _models

    init {
        viewModelScope.launch {
            refreshKeyHint()
            val cached = repo.cachedModels()
            if (cached.isNotEmpty()) _models.value = cached
            // 静默刷新模型列表（有 Key 时；失败保持缓存——§2.1 D3）
            if (repo.current().apiKeyEnc.isNotBlank()) {
                repo.refreshModels()?.takeIf { it.isNotEmpty() }?.let { _models.value = it }
            }
        }
    }

    companion object {
        /** 内置兜底模型（首次联网前 / 接口失败时；V4.1 起 Flash 正式名为 deepseek-flash） */
        val DEFAULT_MODELS = listOf(
            "deepseek-flash",
            "deepseek-v4-pro",
        )
    }

    fun thinkLevelOf(s: AppSettings): ThinkLevel = repo.thinkLevelOf(s)

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            repo.setApiKey(key)
            refreshKeyHint()
            _notice.value = "API Key 已保存。"
        }
    }

    fun setModel(m: String) {
        viewModelScope.launch { repo.setModel(m) }
    }

    fun setThinkLevel(l: ThinkLevel) {
        viewModelScope.launch { repo.setThinkLevel(l) }
    }

    fun setStream(v: Boolean) {
        viewModelScope.launch { repo.setStream(v) }
    }

    /** 联网搜索默认值（§2.1 D5；输入区 chip 与之共用同一设置） */
    fun setWebSearch(v: Boolean) {
        viewModelScope.launch { repo.setWebSearch(v) }
    }

    // —— 显示设置（M4 §2.1 D6）——
    fun setFontScale(v: Float) {
        viewModelScope.launch { repo.setFontScale(v) }
    }

    fun setLineSpacing(v: String) {
        viewModelScope.launch { repo.setLineSpacing(v) }
    }

    fun setTheme(v: String) {
        viewModelScope.launch { repo.setTheme(v) }
    }

    fun notify(text: String) {
        _notice.value = text
    }

    fun dismissNotice() {
        _notice.value = null
    }

    // —— 系统提示词与模板（M5.7）——

    /** 提示词模板库 */
    val prompts: StateFlow<List<String>> = repo.promptsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setSystemPrompt(text: String) {
        viewModelScope.launch { repo.setSystemPrompt(text) }
    }

    fun applyPrompt(text: String) {
        viewModelScope.launch {
            repo.applyPrompt(text)
            _notice.value = "已应用模板为系统提示词。"
        }
    }

    fun addPrompt(text: String) {
        viewModelScope.launch { repo.addPrompt(text) }
    }

    fun removePrompt(index: Int) {
        viewModelScope.launch { repo.removePrompt(index) }
    }

    // —— 自定义字体（M5.7）——

    private val _fonts = MutableStateFlow<List<FontManager.FontItem>>(emptyList())
    val fonts: StateFlow<List<FontManager.FontItem>> = _fonts

    init {
        viewModelScope.launch { _fonts.value = fontManager.list() }
    }

    fun refreshFonts() {
        _fonts.value = fontManager.list()
    }

    /** 导入字体并立即应用（成功时） */
    fun importFont(context: Context, uri: Uri) {
        viewModelScope.launch {
            _notice.value = "正在导入字体…"
            when (val r = fontManager.import(context.contentResolver, uri)) {
                is FontManager.ImportResult.Ok -> {
                    repo.setFontId(r.fileName)
                    refreshFonts()
                    _notice.value = "字体已导入并应用：${r.fileName}"
                }
                FontManager.ImportResult.Unsupported ->
                    _notice.value = "！不支持的文件格式（仅 ttf / otf / ttc）。"
                FontManager.ImportResult.TooBig ->
                    _notice.value = "！字体文件超过 8MB 上限。"
                FontManager.ImportResult.Failed ->
                    _notice.value = "！字体导入失败，请重试。"
            }
        }
    }

    /** 删除字体；若删除的是当前选中项则回退系统默认 */
    fun deleteFont(fileName: String) {
        viewModelScope.launch {
            if (fontManager.delete(fileName)) {
                if (repo.current().fontId == fileName) repo.setFontId(FontManager.SYSTEM)
                refreshFonts()
            } else {
                _notice.value = "！字体删除失败。"
            }
        }
    }

    fun setFontId(id: String) {
        viewModelScope.launch { repo.setFontId(id) }
    }

    // —— 数据区块（M3，§2.1 B9/B10）——

    private val _storageText = MutableStateFlow("—")
    val storageText: StateFlow<String> = _storageText

    /** 存储占用：DB 文件（含 WAL）+ 消息条数 */
    fun refreshStorage(context: Context) {
        viewModelScope.launch {
            val size = listOf("", "-wal", "-shm").sumOf { suffix ->
                val f = context.getDatabasePath("ink_chat.db$suffix")
                if (f != null && f.exists()) f.length() else 0L
            }
            val (_, msgs) = chatRepo.stats()
            _storageText.value = fmtSize(size) + "（${msgs}条消息）"
        }
    }

    /** 导出全部（§2.1 B9；SAF 由 UI 发起，回调传入 uri） */
    fun exportAll(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _notice.value = "正在导出…"
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val payload = chatRepo.buildExport(null)
                    resolver.openOutputStream(uri)?.use { TransferCodec.write(payload, it) }
                        ?: error("无法写入所选位置")
                    payload.conversations.size
                }
            }
            _notice.value = result.fold(
                onSuccess = { n -> "已导出 $n 个会话。" },
                onFailure = { "！导出失败，请重试。" },
            )
        }
    }

    /** 导入对话（§2.1 B10；事务写入，冲突追加；完成后刷新存储占用） */
    fun importFile(context: Context, resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _notice.value = "正在导入…"
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val payload = resolver.openInputStream(uri)?.use { TransferCodec.read(it) }
                        ?: error("无法读取所选文件。")
                    chatRepo.importPayload(payload)
                }
            }
            _notice.value = result.fold(
                onSuccess = { (c, m) -> "已导入 $c 个会话，共 $m 条消息。" },
                onFailure = { t -> "！" + (t.message ?: "导入失败。") },
            )
            refreshStorage(context)
        }
    }

    // —— 备份 / 恢复（M5.7）——

    /** 恢复暂存完成信号：UI 观察后自动重启应用完成收尾 */
    private val _rebootNeeded = MutableStateFlow(false)
    val rebootNeeded: StateFlow<Boolean> = _rebootNeeded

    /** 导出完整备份（数据库 + 设置 + 字体；不含 API Key） */
    fun exportBackup(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _notice.value = "正在备份…"
            when (val r = backupManager.export(resolver, uri, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) {
                is BackupManager.ExportResult.Ok ->
                    _notice.value = "已备份（${fmtSize(r.bytes)}）：${r.fileName}"
                BackupManager.ExportResult.Failed ->
                    _notice.value = "！备份失败，请重试。"
            }
        }
    }

    /** 从备份恢复：解压暂存 + 校验；成功后由 UI 触发重启以完成替换 */
    fun stageRestore(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _notice.value = "正在准备恢复…"
            when (val r = backupManager.stageRestore(resolver, uri)) {
                BackupManager.RestoreStage.Ok -> {
                    _notice.value = "恢复已就绪，应用将自动重启完成收尾。"
                    _rebootNeeded.value = true
                }
                is BackupManager.RestoreStage.Failed ->
                    _notice.value = "！" + r.reason
            }
        }
    }

    private fun fmtSize(bytes: Long): String = if (bytes >= 1024 * 1024) {
        String.format(Locale.US, "%.1fMB", bytes / 1048576.0)
    } else {
        String.format(Locale.US, "%.0fKB", bytes / 1024.0)
    }

    private suspend fun refreshKeyHint() {
        val enc = repo.current().apiKeyEnc
        if (enc.isBlank()) {
            _keyHint.value = "未设置"
            return
        }
        val plain = repo.plainApiKey()
        _keyHint.value = if (!plain.isNullOrEmpty() && plain.length >= 4) {
            "已设置 ····" + plain.takeLast(4)
        } else {
            "已设置"
        }
    }

    private fun formatBalance(b: BalanceSnapshot): String =
        (if (b.currency == "CNY") "¥" else b.currency + " ") + b.totalBalance
}