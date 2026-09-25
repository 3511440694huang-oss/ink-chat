package com.ink.chat.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ink.chat.BuildConfig
import com.ink.chat.data.backup.BackupManager
import com.ink.chat.data.fonts.FontManager
import com.ink.chat.data.transfer.TransferCodec
import com.ink.chat.domain.model.ThinkLevel
import com.ink.chat.ui.components.InkConfirmDialog
import com.ink.chat.ui.components.InkDialog
import com.ink.chat.ui.components.InkDialogTextButton
import com.ink.chat.ui.components.InkInputField
import com.ink.chat.ui.components.InkNotice
import com.ink.chat.ui.components.InkPromptEditDialog
import com.ink.chat.ui.components.InkPromptsDialog
import com.ink.chat.ui.components.InkSelectDialog
import com.ink.chat.ui.components.InkTopBar
import com.ink.chat.ui.components.inkClickable
import com.ink.chat.ui.components.rememberNoFlingBehavior
import com.ink.chat.ui.theme.Ink
import com.ink.chat.ui.theme.InkType
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

/**
 * 设置页（M4：账户 / 对话默认值 / 显示 / 数据区块全真实接入）。
 * 版式：区块标题 + 1dp 线 + 可点行（行高 ≥ 54dp，符合宽容触控）。
 * 交互：行右侧文字值 + 点击进入对话框 / 子页（无 Switch、无滑动；纸面六则）。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenBalance: () -> Unit,
    vm: SettingsViewModel = koinViewModel(),
) {
    val settings by vm.settings.collectAsState()
    val notice by vm.notice.collectAsState()
    val keyHint by vm.keyHint.collectAsState()
    val balanceText by vm.balanceText.collectAsState()
    val storageText by vm.storageText.collectAsState()
    val models by vm.models.collectAsState()
    val prompts by vm.prompts.collectAsState()
    val fonts by vm.fonts.collectAsState()
    val rebootNeeded by vm.rebootNeeded.collectAsState()

    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showThinkDialog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showSpacingDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showPromptEdit by remember { mutableStateOf(false) }
    var showPrompts by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    val appContext = LocalContext.current.applicationContext
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importFile(appContext, appContext.contentResolver, uri)
    }
    val exportAllLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) vm.exportAll(appContext.contentResolver, uri)
    }
    // 字体导入（M5.7；任意 MIME，由解析层按扩展名 / 文件头判定）
    val fontImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importFont(appContext, uri)
    }
    // 备份导出（ZIP）/恢复选择
    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) vm.exportBackup(appContext.contentResolver, uri)
    }
    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingRestoreUri = uri
    }
    LaunchedEffect(Unit) { vm.refreshStorage(appContext) }

    // 恢复暂存完成 → 自动重启（拉起新实例 + 结束当前进程，启动时在 Room 之前完成替换）
    LaunchedEffect(rebootNeeded) {
        if (rebootNeeded) {
            delay(1400)
            val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                appContext.startActivity(launch)
            }
            delay(250)
            Process.killProcess(Process.myPid())
        }
    }

    Column(Modifier.fillMaxSize().background(Ink.Paper)) {
        InkTopBar(
            title = "设置",
            leftIcon = Icons.AutoMirrored.Outlined.ArrowBack,
            onLeft = onBack
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Ink.PadPage, vertical = Ink.PadTight)
        ) {
            SectionTitle("账户")
            SettingRow("API Key", keyHint) { showApiKeyDialog = true }
            SettingRow("余额", balanceText) { onOpenBalance() }
            SettingRow("模型", settings.model) { showModelDialog = true }

            SectionTitle("对话默认值")
            val tl = vm.thinkLevelOf(settings)
            SettingRow(
                "思考强度",
                if (tl == ThinkLevel.LOW) tl.label + "（默认）" else tl.label
            ) { showThinkDialog = true }
            SettingRow("流式输出", if (settings.stream) "开" else "关（推荐）") {
                vm.setStream(!settings.stream)
            }
            SettingRow("联网搜索", if (settings.webSearch) "开" else "关（默认）") {
                vm.setWebSearch(!settings.webSearch)
            }
            SettingRow(
                "系统提示词",
                if (settings.systemPrompt.isBlank()) "未设置" else "已设置 · ${settings.systemPrompt.length} 字"
            ) { showPromptEdit = true }
            SettingRow("提示词模板", if (prompts.isEmpty()) "无" else "${prompts.size} 条") { showPrompts = true }

            SectionTitle("显示")
            SettingRow("字号", fontScaleLabel(settings.fontScale)) { showFontSizeDialog = true }
            SettingRow("行距", if (settings.lineSpacing == "tight") "紧密" else "标准") {
                showSpacingDialog = true
            }
            SettingRow("主题", if (settings.theme == "inverse") "反色" else "纸白") {
                showThemeDialog = true
            }
            SettingRow(
                "字体",
                if (settings.fontId == FontManager.SYSTEM) "系统默认" else settings.fontId
            ) { showFontDialog = true }
            SettingRow("清屏重绘（去残影）", "执行") { vm.notify("依赖阅读器系统支持，M6 评估。") }

            SectionTitle("数据")
            SettingRow("导入对话") { importLauncher.launch(arrayOf("*/*")) }
            SettingRow("导出全部") { exportAllLauncher.launch(TransferCodec.exportFileName(null)) }
            SettingRow("备份 / 恢复", "完整备份 · ZIP") { showBackupDialog = true }
            SettingRow("存储占用", storageText) { vm.refreshStorage(appContext) }

            SectionTitle("关于")
            SettingRow("版本", BuildConfig.VERSION_NAME) { vm.notify("静墨 · DeepSeek 墨水屏客户端") }

            Spacer(Modifier.height(Ink.PadBlock))
        }

        notice?.let { msg ->
            InkNotice(msg, onDismiss = vm::dismissNotice)
        }
    }

    // —— API Key 输入对话框 ——
    if (showApiKeyDialog) {
        ApiKeyDialog(
            onSave = { key ->
                vm.saveApiKey(key)
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }

    // —— 模型选择（M4：/models 动态列表 + 内置兜底）——
    if (showModelDialog) {
        InkSelectDialog(
            title = "模型",
            options = models,
            selectedIndex = models.indexOf(settings.model).coerceAtLeast(0),
            onSelect = { index ->
                vm.setModel(models[index])
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false }
        )
    }

    // —— 思考强度 ——
    if (showThinkDialog) {
        InkSelectDialog(
            title = "思考强度",
            options = ThinkLevel.values().map { level ->
                if (level == ThinkLevel.LOW) level.label + "（默认）" else level.label
            },
            selectedIndex = vm.thinkLevelOf(settings).ordinal,
            onSelect = { index ->
                vm.setThinkLevel(ThinkLevel.values()[index])
                showThinkDialog = false
            },
            onDismiss = { showThinkDialog = false }
        )
    }

    // —— 字号（M4 §2.1 D6：0.85 / 0.9 / 1.0）——
    if (showFontSizeDialog) {
        InkSelectDialog(
            title = "字号",
            options = listOf("小", "中（默认）", "大"),
            selectedIndex = fontScaleIndex(settings.fontScale),
            onSelect = { index ->
                vm.setFontScale(FONT_SCALES[index])
                showFontSizeDialog = false
            },
            onDismiss = { showFontSizeDialog = false }
        )
    }

    // —— 行距 ——
    if (showSpacingDialog) {
        InkSelectDialog(
            title = "行距",
            options = listOf("标准", "紧密"),
            selectedIndex = if (settings.lineSpacing == "tight") 1 else 0,
            onSelect = { index ->
                vm.setLineSpacing(if (index == 1) "tight" else "normal")
                showSpacingDialog = false
            },
            onDismiss = { showSpacingDialog = false }
        )
    }

    // —— 主题（纸白 / 反色）——
    if (showThemeDialog) {
        InkSelectDialog(
            title = "主题",
            options = listOf("纸白", "反色"),
            selectedIndex = if (settings.theme == "inverse") 1 else 0,
            onSelect = { index ->
                vm.setTheme(if (index == 1) "inverse" else "paper")
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    // —— 系统提示词编辑（M5.7）——
    if (showPromptEdit) {
        InkPromptEditDialog(
            initial = settings.systemPrompt,
            onSave = { text ->
                vm.setSystemPrompt(text)
                showPromptEdit = false
            },
            onDismiss = { showPromptEdit = false }
        )
    }

    // —— 提示词模板库（M5.7）——
    if (showPrompts) {
        InkPromptsDialog(
            prompts = prompts,
            onApply = { text ->
                vm.applyPrompt(text)
                showPrompts = false
            },
            onDelete = { i -> vm.removePrompt(i) },
            onAdd = { t -> vm.addPrompt(t) },
            onDismiss = { showPrompts = false }
        )
    }

    // —— 字体（M5.7）——
    if (showFontDialog) {
        FontDialogView(
            fonts = fonts,
            currentId = settings.fontId,
            onSelect = { vm.setFontId(it) },
            onDelete = { vm.deleteFont(it) },
            onImport = {
                showFontDialog = false
                fontImportLauncher.launch(arrayOf("*/*"))
            },
            onDismiss = { showFontDialog = false }
        )
    }

    // —— 备份 / 恢复入口（M5.7）——
    if (showBackupDialog) {
        BackupDialogView(
            onExport = {
                showBackupDialog = false
                backupExportLauncher.launch(BackupManager.backupFileName())
            },
            onRestore = {
                showBackupDialog = false
                backupImportLauncher.launch(arrayOf("*/*"))
            },
            onDismiss = { showBackupDialog = false }
        )
    }

    // —— 恢复二次确认（M5.7：覆盖提示）——
    pendingRestoreUri?.let { uri ->
        InkConfirmDialog(
            title = "从备份恢复？",
            message = "将覆盖当前全部对话、设置与字体（API Key 保留本机现值）。完成后应用会自动重启。",
            confirmLabel = "恢复",
            onConfirm = {
                vm.stageRestore(appContext.contentResolver, uri)
                pendingRestoreUri = null
            },
            onDismiss = { pendingRestoreUri = null }
        )
    }
}

/** 字号三档（§2.1 D6） */
private val FONT_SCALES = listOf(0.85f, 0.9f, 1.0f)

private fun fontScaleIndex(v: Float): Int = when {
    v <= 0.87f -> 0
    v >= 0.95f -> 2
    else -> 1
}

private fun fontScaleLabel(v: Float): String = when (fontScaleIndex(v)) {
    0 -> "小"
    1 -> "中（默认）"
    else -> "大"
}

@Composable
private fun ApiKeyDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    InkDialog(onDismiss = onDismiss) {
        Text(
            text = "API Key",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            fontSize = InkType.Title,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Ink
        )
        Text(
            text = "使用 Android Keystore 加密存储在本机，仅用于调用 DeepSeek API。",
            modifier = Modifier.padding(horizontal = 16.dp),
            fontSize = InkType.Alt,
            color = Ink.InkMid
        )
        Spacer(Modifier.height(8.dp))
        InkInputField(
            value = text,
            onValueChange = { text = it },
            placeholder = "sk-…",
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            DialogTextButton("取消", onDismiss)
            DialogTextButton("保存") {
                if (text.isNotBlank()) onSave(text.trim())
            }
        }
    }
}

@Composable
private fun DialogTextButton(label: String, onClick: () -> Unit) {
    Box(Modifier.inkClickable(onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(label, fontSize = InkType.Alt, color = Ink.Ink, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = Ink.PadBlock, bottom = 4.dp)) {
        Text(
            text = text,
            fontSize = InkType.Caption,
            fontWeight = FontWeight.SemiBold,
            color = Ink.InkMid
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(Ink.Hairline).background(Ink.Line))
    }
}

@Composable
private fun SettingRow(label: String, value: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().inkClickable(onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = InkType.Body,
            color = Ink.Ink
        )
        if (value != null) {
            Text(
                text = value,
                fontSize = InkType.Alt,
                color = Ink.InkMid
            )
        }
    }
}

// —— 字体对话框（M5.7）——

@Composable
private fun FontDialogView(
    fonts: List<FontManager.FontItem>,
    currentId: String,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    InkDialog(onDismiss = onDismiss) {
        Text(
            text = "字体",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
            fontSize = InkType.Title,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Ink
        )
        Text(
            text = "选择正文字体；公式与代码保持专用字体。",
            modifier = Modifier.padding(horizontal = 16.dp),
            fontSize = InkType.Alt,
            color = Ink.InkMid
        )
        Spacer(Modifier.height(4.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState(), flingBehavior = rememberNoFlingBehavior())
        ) {
            // 系统默认
            Box(
                Modifier
                    .fillMaxWidth()
                    .inkClickable { onSelect(FontManager.SYSTEM) }
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
            ) {
                Text(
                    text = (if (currentId == FontManager.SYSTEM) "✓ " else "") + "系统默认",
                    fontSize = InkType.Body,
                    color = Ink.Ink
                )
            }
            fonts.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Ink.Touch),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .inkClickable { onSelect(f.fileName) }
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                    ) {
                        Text(
                            text = (if (f.fileName == currentId) "✓ " else "") +
                                f.fileName + " · " + fmtSizeShort(f.sizeBytes),
                            fontSize = InkType.Body,
                            color = Ink.Ink,
                            maxLines = 2
                        )
                    }
                    Box(
                        Modifier
                            .height(Ink.Touch)
                            .inkClickable { onDelete(f.fileName) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("删除", fontSize = InkType.Alt, color = Ink.InkMid)
                    }
                }
            }
            if (fonts.isEmpty()) {
                Text(
                    text = "还没有导入字体。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    fontSize = InkType.Alt,
                    color = Ink.InkMid
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(Ink.Hairline).background(Ink.Line))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            InkDialogTextButton("导入字体", onImport)
            InkDialogTextButton("关闭", onDismiss)
        }
    }
}

// —— 备份 / 恢复入口对话框（M5.7）——

@Composable
private fun BackupDialogView(
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    InkDialog(onDismiss = onDismiss) {
        Text(
            text = "备份 / 恢复",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
            fontSize = InkType.Title,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Ink
        )
        Text(
            text = "备份包含：全部对话、设置、提示词与字体（不含 API Key）。恢复会覆盖当前数据并重启应用。",
            modifier = Modifier.padding(horizontal = 16.dp),
            fontSize = InkType.Alt,
            color = Ink.InkMid
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            InkDialogTextButton("取消", onDismiss)
            InkDialogTextButton("导出备份", onExport)
            InkDialogTextButton("从备份恢复", onRestore)
        }
    }
}

/** 文件大小短格式（字体列表等） */
private fun fmtSizeShort(bytes: Long): String = if (bytes >= 1024 * 1024) {
    String.format(Locale.US, "%.1fMB", bytes / 1048576.0)
} else {
    String.format(Locale.US, "%.0fKB", bytes / 1024.0)
}