package com.ink.chat.ui.settings

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
import com.ink.chat.data.transfer.TransferCodec
import com.ink.chat.domain.model.ThinkLevel
import com.ink.chat.ui.components.InkDialog
import com.ink.chat.ui.components.InkInputField
import com.ink.chat.ui.components.InkNotice
import com.ink.chat.ui.components.InkSelectDialog
import com.ink.chat.ui.components.InkTopBar
import com.ink.chat.ui.components.inkClickable
import com.ink.chat.ui.theme.Ink
import com.ink.chat.ui.theme.InkType
import org.koin.androidx.compose.koinViewModel

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

    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showThinkDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showSpacingDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

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
    LaunchedEffect(Unit) { vm.refreshStorage(appContext) }

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
            SettingRow("系统提示词", "未设置") { vm.notify("系统提示词将在后续版本接入。") }

            SectionTitle("显示")
            SettingRow("字号", fontScaleLabel(settings.fontScale)) { showFontDialog = true }
            SettingRow("行距", if (settings.lineSpacing == "tight") "紧密" else "标准") {
                showSpacingDialog = true
            }
            SettingRow("主题", if (settings.theme == "inverse") "反色" else "纸白") {
                showThemeDialog = true
            }
            SettingRow("清屏重绘（去残影）", "执行") { vm.notify("依赖阅读器系统支持，M6 评估。") }

            SectionTitle("数据")
            SettingRow("导入对话") { importLauncher.launch(arrayOf("*/*")) }
            SettingRow("导出全部") { exportAllLauncher.launch(TransferCodec.exportFileName(null)) }
            SettingRow("备份 / 恢复") { vm.notify("备份 / 恢复将在后续版本接入。") }
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
    if (showFontDialog) {
        InkSelectDialog(
            title = "字号",
            options = listOf("小", "中（默认）", "大"),
            selectedIndex = fontScaleIndex(settings.fontScale),
            onSelect = { index ->
                vm.setFontScale(FONT_SCALES[index])
                showFontDialog = false
            },
            onDismiss = { showFontDialog = false }
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