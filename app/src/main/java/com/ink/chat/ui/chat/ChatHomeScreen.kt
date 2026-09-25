package com.ink.chat.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ink.chat.domain.model.ConversationUsage
import com.ink.chat.domain.model.ErrorAction
import com.ink.chat.domain.model.Message
import com.ink.chat.domain.model.MessageRole
import com.ink.chat.domain.model.ThinkLevel
import com.ink.chat.ui.components.InkChip
import com.ink.chat.ui.components.InkConfirmDialog
import com.ink.chat.ui.components.InkIconButton
import com.ink.chat.ui.components.InkInputField
import com.ink.chat.ui.components.InkJumpDialog
import com.ink.chat.ui.components.InkMessageBlock
import com.ink.chat.ui.components.InkNotice
import com.ink.chat.ui.components.InkPhrasesDialog
import com.ink.chat.ui.components.InkSelectDialog
import com.ink.chat.ui.components.InkSquareButton
import com.ink.chat.ui.components.InkTopBar
import com.ink.chat.ui.components.InkUsageDialog
import com.ink.chat.ui.components.inkClickable
import com.ink.chat.ui.components.rememberNoFlingBehavior
import com.ink.chat.ui.theme.Ink
import com.ink.chat.ui.theme.InkType
import com.ink.chat.util.Pricing
import com.ink.chat.util.TimeFmt
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * 对话页（M3：多会话 —— 观察共享槽位自动切换；思考链折叠 / 停止 / 重试 / 复制 / 删除 / 跳转，零动画）。
 * 交互：顶栏 菜单→会话列表、目录→消息跳转弹窗、设置；↑/↓ 迷你翻页键右侧边缘悬浮（无动画整屏翻页）；
 * 点击消息 → 展开操作条；输入区发送键生成中变「停止」；列表页「跳到…」→ 本页无动画定位目标消息。
 */
@Composable
fun ChatHomeScreen(
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: ChatViewModel = koinViewModel(),
) {
    val messages by vm.messages.collectAsState()
    val sending by vm.sending.collectAsState()
    val notice by vm.notice.collectAsState()
    val settings by vm.settings.collectAsState()
    val usage by vm.usage.collectAsState()
    val conversationId by vm.conversationId.collectAsState()
    val pendingJump by vm.pendingJump.collectAsState()
    val uploadingImage by vm.uploadingImage.collectAsState()
    val pendingImage by vm.pendingImage.collectAsState()
    val uploadingFile by vm.uploadingFile.collectAsState()
    val pendingFile by vm.pendingFile.collectAsState()
    val phrases by vm.phrases.collectAsState()

    var input by remember { mutableStateOf("") }
    var showThinkPanel by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var showPhrases by remember { mutableStateOf(false) }
    var showUsage by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Message?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }

    // 图片选择（SAF / 相册；M5 §2.1 C2）
    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) vm.attachImage(uri)
    }

    // 文件选择（M5.5：纯文本文件本地解析；选任意类型，由解析层判定并提示）
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) vm.attachFile(uri)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val noFling = rememberNoFlingBehavior()

    // 最后一条回答：重试 / 重新生成仅对它开放（§4.6，避免乱序）
    val lastAssistantId = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id

    // 切换会话（列表页点选 / 新建 / 跳转）：复位输入草稿与选中态（防呆：避免串话）
    LaunchedEffect(conversationId) {
        input = ""
        selectedId = null
    }

    // 跳转快照：记录「跳转完成时」的会话与条数，用于抑制随后的「滚到底」一次
    // （快照比较与 effect 执行顺序无关：谁先执行结果都一致）
    var jumpSnapConv by remember { mutableStateOf<Long?>(null) }
    var jumpSnapCount by remember { mutableStateOf(-1) }

    // —— 待处理跳转（列表页「跳到…」→ 无动画定位目标消息；§2.1 B7）——
    LaunchedEffect(messages, pendingJump, conversationId) {
        val jumpId = pendingJump ?: return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect
        val idx = messages.indexOfFirst { it.id == jumpId }
        if (idx >= 0) {
            listState.scrollToItem(idx)
            jumpSnapConv = conversationId
            jumpSnapCount = messages.size
        }
        vm.consumeJump()
    }

    // 新消息 → 跳到底部（无动画，§4.7）
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (jumpSnapConv == conversationId && jumpSnapCount == messages.size) return@LaunchedEffect
        listState.scrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(Ink.Paper)) {
        InkTopBar(
            title = "静墨",
            leftIcon = Icons.Outlined.Menu,
            onLeft = onOpenSessions,
            rightIcons = listOf(
                Icons.AutoMirrored.Outlined.List to {
                    // 空会话时给出反馈（修复：此前点击静默无响应）
                    if (messages.isNotEmpty()) showJump = true else vm.notify("还没有消息。")
                },
                Icons.Outlined.Settings to onOpenSettings,
            )
        )

        // —— 消息区 ——（点空白处收起操作条）
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .inkClickable { selectedId = null }
        ) {
            if (messages.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = Ink.PadPage),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "从第一个问题开始。",
                        fontSize = InkType.Display,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.Ink
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "在下方输入，或在设置里检查 API Key。",
                        fontSize = InkType.Alt,
                        color = Ink.InkMid
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    flingBehavior = noFling,
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = Ink.PadPage)
                ) {
                    items(messages, key = { it.id }) { m ->
                        InkMessageBlock(
                            message = m,
                            selected = m.id == selectedId,
                            lastAssistantId = lastAssistantId,
                            busy = sending,
                            onClickBlock = { selectedId = if (selectedId == m.id) null else m.id },
                            onCopy = {
                                clipboard.setText(AnnotatedString(m.content))
                                vm.notify("已复制。")
                                selectedId = null
                            },
                            onDelete = {
                                pendingDelete = m
                                selectedId = null
                            },
                            onRegenerate = {
                                vm.retry(m)
                                selectedId = null
                            },
                            onRetry = { vm.retry(m) },
                        )
                    }
                }
            }

            // —— ↑/↓ 迷你翻页（右侧边缘悬浮 · 无动画整屏翻页，§2.1 F3）——
            // 无动画整屏翻页：步长 = 可视区域高度；落点 = 相邻屏尚未展示内容的起点（无缝衔接，连续阅读）
            if (messages.isNotEmpty()) {
                val flipPage: (Int) -> Unit = { dir ->
                    scope.launch {
                        val step = listState.layoutInfo.viewportSize.height.toFloat()
                        listState.scrollBy(dir * step)
                    }
                }
                Column(
                    Modifier.align(Alignment.CenterEnd),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ElevatorKey("↑") { flipPage(-1) }
                    Spacer(Modifier.height(6.dp))
                    ElevatorKey("↓") { flipPage(1) }
                }
            }
        }

        // —— 内联提示条（替代 Toast；带动作按钮）——
        notice?.let { n ->
            InkNotice(
                text = n.text,
                onDismiss = vm::dismissNotice,
                isError = n.isError,
                actionLabel = if (n.action == ErrorAction.GO_SETTINGS) "去设置" else null,
                onAction = if (n.action == ErrorAction.GO_SETTINGS) {
                    {
                        vm.dismissNotice()
                        onOpenSettings()
                    }
                } else null
            )
        }

        // —— 底部输入区（Composer）——
        Column(Modifier.fillMaxWidth().background(Ink.Paper)) {
            Box(Modifier.fillMaxWidth().height(Ink.Hairline).background(Ink.Line))
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Ink.PadPage, vertical = Ink.PadTight)
            ) {
                // 待发送图片状态行（M5 §2.1 C2；仅上传中 / 已就绪时出现）
                if (uploadingImage || pendingImage != null) {
                    ImageStatusRow(
                        uploading = uploadingImage,
                        image = pendingImage,
                        onClear = vm::clearPendingImage
                    )
                }
                // 待发送文件状态行（M5.5；仅读取中 / 已就绪时出现）
                if (uploadingFile || pendingFile != null) {
                    FileStatusRow(
                        uploading = uploadingFile,
                        file = pendingFile,
                        onClear = vm::clearPendingFile
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Ink.PadTight),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InkChip("联网", selected = settings.webSearch) { vm.toggleWebSearch() }
                    InkChip(
                        label = "思考 · " + vm.thinkLevelOf(settings).label,
                        selected = settings.thinkingEnabled,
                        onClick = { showThinkPanel = true }
                    )
                    Spacer(Modifier.weight(1f))
                    // 图片 / 文件 / 短语入口（M5 §2.1 C2 / A13；M5.5 文件；文字按钮，避免图标歧义）
                    ComposerAction("图片") { imageLauncher.launch("image/*") }
                    ComposerAction("文件") { fileLauncher.launch("*/*") }
                    ComposerAction("短语") { showPhrases = true }
                }
                // 本次对话用量行（M5.6：token / 缓存命中率 / 花费；独占一行防挤压，点击看明细）
                usage?.takeIf { it.inputTokens + it.outputTokens > 0 }?.let { u ->
                    UsageLine(u) { showUsage = true }
                }
                Spacer(Modifier.height(Ink.PadTight))
                Row(verticalAlignment = Alignment.Bottom) {
                    InkInputField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Ink.PadTight))
                    if (sending) {
                        // 生成中 →「停止」（黑框白底，§3.3-②）
                        InkSquareButton(filled = false, onClick = { vm.stop() }) {
                            Text("停止", fontSize = InkType.Alt, color = Ink.Ink)
                        }
                    } else {
                        InkSquareButton(
                            filled = true,
                            onClick = {
                                if (input.isBlank() && pendingImage == null && pendingFile == null) {
                                    vm.notify("先输入点什么。")
                                } else if (uploadingImage) {
                                    vm.notify("图片正在上传，请稍候。")
                                } else if (uploadingFile) {
                                    vm.notify("文件正在读取，请稍候。")
                                } else if (settings.apiKeyEnc.isEmpty()) {
                                    // UI 侧预检：无 Key 不发请求、不清空输入（防丢字）
                                    vm.notifyNoApiKey()
                                } else {
                                    vm.send(input)
                                    input = ""
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = Ink.Paper,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // —— 本次对话用量对话框（M5.6）——
    usage?.let { u ->
        if (showUsage) InkUsageDialog(usage = u, onDismiss = { showUsage = false })
    }

    // —— 模板短语对话框（M5 §2.1 A13）——
    if (showPhrases) {
        InkPhrasesDialog(
            phrases = phrases,
            onInsert = { p ->
                input = when {
                    input.isBlank() -> p
                    input.endsWith("\n") || input.endsWith(" ") -> input + p
                    else -> input + "\n" + p
                }
                showPhrases = false
            },
            onDelete = { i -> vm.removePhrase(i) },
            onAdd = { t -> vm.addPhrase(t) },
            onDismiss = { showPhrases = false }
        )
    }

    // —— 思考强度调节面板 ——
    if (showThinkPanel) {
        InkSelectDialog(
            title = "思考强度",
            options = ThinkLevel.values().map { level ->
                if (level == ThinkLevel.LOW) level.label + "（默认）" else level.label
            },
            selectedIndex = vm.thinkLevelOf(settings).ordinal,
            onSelect = { index ->
                vm.setThinkLevel(ThinkLevel.values()[index])
                showThinkPanel = false
            },
            onDismiss = { showThinkPanel = false }
        )
    }

    // —— 消息跳转弹窗（§2.1 B7）——
    if (showJump) {
        InkJumpDialog(
            messages = messages,
            currentIndex = listState.firstVisibleItemIndex,
            onJump = { index ->
                showJump = false
                scope.launch { listState.scrollToItem(index) }
            },
            onDismiss = { showJump = false }
        )
    }

    // —— 删除确认（§2.1 A8：二次确认）——
    pendingDelete?.let { m ->
        InkConfirmDialog(
            title = "删除这条消息？",
            message = "删除后不可恢复。",
            confirmLabel = "删除",
            onConfirm = {
                vm.deleteMessage(m)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}
/** 输入区文字动作按钮（48dp 触区；黑字，无涟漪；M5.6 缩一档给用量行让位） */
@Composable
private fun ComposerAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(Ink.Touch)
            .inkClickable(onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = InkType.Caption, color = Ink.Ink, maxLines = 1)
    }
}

/** 本次对话用量行（M5.6；点击 → 明细对话框）：↑输入 ↓输出 · 命中率 · 估算花费 */
@Composable
private fun UsageLine(u: ConversationUsage, onClick: () -> Unit) {
    val text = buildString {
        append("用量 ↑").append(TimeFmt.tokens(u.inputTokens) ?: "0")
        append(" ↓").append(TimeFmt.tokens(u.outputTokens) ?: "0")
        u.hitRatePercent?.let { append(" · 命中").append(it).append("%") }
        if (u.costCny > 0) append(" · ≈").append(Pricing.money(u.costCny))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .inkClickable(onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = InkType.Caption, color = Ink.InkMid, maxLines = 1)
    }
}

/** 待发送文件状态行（M5.5）：读取中 ▸ / 已就绪 ✓ + 移除 */
@Composable
private fun FileStatusRow(
    uploading: Boolean,
    file: ChatViewModel.AttachedFile?,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Ink.Touch),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (uploading) "▸正在读取文件…"
            else "✓ [文件] " + (file?.fileName ?: "") + " · " + (file?.content?.length ?: 0) + " 字",
            modifier = Modifier.weight(1f),
            fontSize = InkType.Caption,
            color = Ink.InkMid,
            maxLines = 1
        )
        if (!uploading) {
            Box(
                Modifier
                    .height(Ink.Touch)
                    .inkClickable(onClear)
                    .padding(horizontal = Ink.PadTight),
                contentAlignment = Alignment.Center
            ) {
                Text("移除", fontSize = InkType.Alt, color = Ink.Ink)
            }
        }
    }
}

/** 待发送图片状态行（M5 §2.1 C2）：上传中 ▸ / 已就绪 ✓ + 移除 */
@Composable
private fun ImageStatusRow(
    uploading: Boolean,
    image: ChatViewModel.UploadedImage?,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Ink.Touch),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (uploading) "▸正在上传图片…"
            else "✓ [图片] " + (image?.fileName ?: ""),
            modifier = Modifier.weight(1f),
            fontSize = InkType.Caption,
            color = Ink.InkMid,
            maxLines = 1
        )
        if (!uploading) {
            Box(
                Modifier
                    .height(Ink.Touch)
                    .inkClickable(onClear)
                    .padding(horizontal = Ink.PadTight),
                contentAlignment = Alignment.Center
            ) {
                Text("移除", fontSize = InkType.Alt, color = Ink.Ink)
            }
        }
    }
}

/** 迷你翻页键：48dp 触区 / 36dp 视觉方块（1dp 细框，右侧边缘悬浮） */
@Composable
private fun ElevatorKey(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(Ink.Touch).inkClickable(onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(36.dp)
                .border(Ink.Hairline, Ink.Ink)
                .background(Ink.Paper),
            contentAlignment = Alignment.Center
        ) {
            Text(label, fontSize = InkType.Alt, color = Ink.Ink)
        }
    }
}