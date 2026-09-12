package com.ink.chat.ui.sessions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ink.chat.data.transfer.TransferCodec
import com.ink.chat.domain.model.ConversationRow
import com.ink.chat.ui.components.InkConfirmDialog
import com.ink.chat.ui.components.InkInputDialog
import com.ink.chat.ui.components.InkInputField
import com.ink.chat.ui.components.InkJumpDialog
import com.ink.chat.ui.components.InkNotice
import com.ink.chat.ui.components.InkTopBar
import com.ink.chat.ui.components.inkClickable
import com.ink.chat.ui.components.rememberNoFlingBehavior
import com.ink.chat.ui.theme.Ink
import com.ink.chat.ui.theme.InkType
import com.ink.chat.util.TimeFmt
import org.koin.androidx.compose.koinViewModel

/**
 * 会话列表页（M3，§3.4-③ 线框）：
 * 搜索框 + 置顶组 + 列表；每项标题/条数时间/摘要预览 + 常驻一行小号文字按钮
 * [置顶][跳到…][改名][总结][导出][删除]（直点直达，无长按、无滑动抽屉）。
 */
@Composable
fun SessionsScreen(
    onBack: () -> Unit,
    vm: SessionsViewModel = koinViewModel(),
) {
    val rows by vm.rows.collectAsState()
    val query by vm.query.collectAsState()
    val activeQuery by vm.activeQuery.collectAsState()
    val notice by vm.notice.collectAsState()
    val summarizing by vm.summarizing.collectAsState()
    val jumpMessages by vm.jumpMessages.collectAsState()
    val manage by vm.manageMode.collectAsState()
    val selected by vm.selected.collectAsState()

    var renameTarget by remember { mutableStateOf<ConversationRow?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationRow?>(null) }
    var pendingExport by remember { mutableStateOf<ConversationRow?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var batchExportIds by remember { mutableStateOf<Set<Long>?>(null) }

    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val row = pendingExport
        if (uri != null && row != null) {
            vm.exportOne(row.id, context.contentResolver, uri)
        }
        pendingExport = null
    }
    val exportSelectedLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val ids = batchExportIds
        if (uri != null && ids != null) {
            vm.exportSelected(ids, context.contentResolver, uri)
        }
        batchExportIds = null
    }

    Column(Modifier.fillMaxSize().background(Ink.Paper)) {
        InkTopBar(
            title = if (manage) "已选 ${selected.size} 项" else "全部对话",
            leftIcon = if (manage) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
            onLeft = { if (manage) vm.exitManage() else onBack() },
            rightTextActions = if (manage) {
                val list = rows.orEmpty()
                val allSelected = list.isNotEmpty() && selected.containsAll(list.map { it.id })
                listOf(
                    (if (allSelected) "清空" else "全选") to { vm.toggleSelectAll(list) }
                )
            } else {
                listOf("管理" to { vm.enterManage() })
            },
            rightIcons = if (manage) {
                emptyList()
            } else {
                listOf(
                    Icons.Outlined.Add to { vm.newConversation { onBack() } }
                )
            }
        )

        // —— 搜索框（§3.4-③；300ms 防抖查询；管理模式下隐藏）——
        if (!manage) {
            Box(Modifier.fillMaxWidth().padding(horizontal = Ink.PadPage, vertical = Ink.PadTight)) {
                InkInputField(
                    value = query,
                    onValueChange = vm::onQueryChange,
                    placeholder = "搜索对话或消息…"
                )
            }
        }

        val list = rows
        if (list == null) {
            // 首次加载中（瞬时）
            Box(Modifier.weight(1f).fillMaxWidth())
        } else if (list.isEmpty()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = Ink.PadPage),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (activeQuery.isNotEmpty()) "没有匹配的对话。" else "暂无对话。",
                        fontSize = InkType.Display,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.Ink
                    )
                    if (activeQuery.isEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "点右上角 + 新建。",
                            fontSize = InkType.Alt,
                            color = Ink.InkMid
                        )
                    }
                }
            }
        } else {
            val pinned = list.filter { it.pinned }
            val normal = list.filterNot { it.pinned }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                flingBehavior = rememberNoFlingBehavior()
            ) {
                if (pinned.isNotEmpty()) {
                    item(key = "group-pinned") { GroupHeader("置顶") }
                    items(pinned, key = { it.id }) { row ->
                        SessionItem(
                            row = row,
                            manage = manage,
                            selected = row.id in selected,
                            summarizing = summarizing == row.id,
                            onOpen = {
                                if (manage) vm.toggleSelect(row.id) else vm.open(row.id) { onBack() }
                            },
                            onPin = { vm.togglePin(row) },
                            onJump = { vm.openJumpDialog(row.id) },
                            onRename = { renameTarget = row },
                            onSummarize = { vm.summarize(row.id) },
                            onExport = {
                                pendingExport = row
                                exportLauncher.launch(TransferCodec.exportFileName(row.title))
                            },
                            onDelete = { deleteTarget = row },
                        )
                    }
                }
                items(normal, key = { it.id }) { row ->
                    SessionItem(
                        row = row,
                        manage = manage,
                        selected = row.id in selected,
                        summarizing = summarizing == row.id,
                        onOpen = {
                            if (manage) vm.toggleSelect(row.id) else vm.open(row.id) { onBack() }
                        },
                        onPin = { vm.togglePin(row) },
                        onJump = { vm.openJumpDialog(row.id) },
                        onRename = { renameTarget = row },
                        onSummarize = { vm.summarize(row.id) },
                        onExport = {
                            pendingExport = row
                            exportLauncher.launch(TransferCodec.exportFileName(row.title))
                        },
                        onDelete = { deleteTarget = row },
                    )
                }
            }
        }

        // —— 批量操作条（M5.5；管理模式下固定底部）——
        if (manage) {
            BatchBar(
                count = selected.size,
                onDelete = { confirmBatchDelete = true },
                onExport = {
                    batchExportIds = selected
                    exportSelectedLauncher.launch(TransferCodec.exportFileName("所选"))
                },
                onCancel = { vm.exitManage() }
            )
        }

        notice?.let { n ->
            InkNotice(n.text, onDismiss = vm::dismissNotice, isError = n.isError)
        }
    }

    // —— 批量删除确认（M5.5；二次确认）——
    if (confirmBatchDelete) {
        InkConfirmDialog(
            title = "删除这 ${selected.size} 个会话？",
            message = "将删除这些会话的全部消息，不可恢复。",
            confirmLabel = "删除",
            onConfirm = {
                vm.deleteSelected()
                confirmBatchDelete = false
            },
            onDismiss = { confirmBatchDelete = false }
        )
    }

    // —— 重命名对话框（§2.1 B3）——
    renameTarget?.let { row ->
        InkInputDialog(
            title = "重命名",
            initial = row.title,
            confirmLabel = "保存",
            placeholder = "会话标题",
            onConfirm = {
                vm.rename(row.id, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    // —— 删除确认（§2.1 B4：二次确认）——
    deleteTarget?.let { row ->
        InkConfirmDialog(
            title = "删除这个会话？",
            message = "将删除该会话及全部 ${row.messageCount} 条消息，不可恢复。",
            confirmLabel = "删除",
            onConfirm = {
                vm.delete(row.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }

    // —— 消息跳转弹窗（§2.1 B7：列表 → 摘要 → 对话页无动画定位）——
    jumpMessages?.let { msgs ->
        InkJumpDialog(
            messages = msgs,
            currentIndex = -1,
            onJump = { index ->
                msgs.getOrNull(index)?.let { msg ->
                    vm.jumpToMessage(msg) { onBack() }
                }
            },
            onDismiss = vm::dismissJumpDialog
        )
    }
}

/** 置顶分组标题（§3.4-③「┌置顶 ─」） */
@Composable
private fun GroupHeader(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(
            start = Ink.PadPage, end = Ink.PadPage,
            top = Ink.PadTight, bottom = 4.dp
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = InkType.Caption, color = Ink.InkMid)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(Ink.Hairline).background(Ink.Line))
    }
}

/** 单个会话项：标题行 + 摘要行（点击进入）+ 常驻操作按钮行；管理模式下点行 = 勾选（M5.5） */
@Composable
private fun SessionItem(
    row: ConversationRow,
    manage: Boolean,
    selected: Boolean,
    summarizing: Boolean,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onJump: () -> Unit,
    onRename: () -> Unit,
    onSummarize: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .inkClickable(onOpen)
                .padding(horizontal = Ink.PadPage, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (manage) {
                    SelectionMark(selected)
                    Spacer(Modifier.width(10.dp))
                }
                if (row.pinned) {
                    Text(
                        "★",
                        fontSize = InkType.Alt,
                        color = Ink.InkMid,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                Text(
                    text = row.title,
                    modifier = Modifier.weight(1f),
                    fontSize = InkType.Body,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${row.messageCount}条 · ${TimeFmt.relDay(row.updatedAt)}",
                    fontSize = InkType.Caption,
                    color = Ink.InkMid,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = row.preview.ifEmpty { "（空对话）" },
                fontSize = InkType.Alt,
                color = Ink.InkMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 常驻操作行（等宽 6 键，触区 48dp；直点直达；管理模式下隐藏）
        if (!manage) {
            Row(Modifier.fillMaxWidth()) {
                SessionAction(if (row.pinned) "已置顶" else "置顶", Modifier.weight(1f), onClick = onPin)
                SessionAction("跳到…", Modifier.weight(1f), onClick = onJump)
                SessionAction("改名", Modifier.weight(1f), onClick = onRename)
                SessionAction(
                    label = if (summarizing) "总结中" else "总结",
                    modifier = Modifier.weight(1f),
                    enabled = !summarizing,
                    onClick = onSummarize
                )
                SessionAction("导出", Modifier.weight(1f), onClick = onExport)
                SessionAction("删除", Modifier.weight(1f), onClick = onDelete)
            }
        }

        Box(Modifier.fillMaxWidth().height(Ink.Hairline).background(Ink.Line))
    }
}

/** 选择标记（M5.5 批量管理）：22dp 直角方块，选中 = 反色 + ✓ */
@Composable
private fun SelectionMark(selected: Boolean) {
    Box(
        Modifier
            .size(22.dp)
            .border(Ink.StrongLine, Ink.Ink)
            .background(if (selected) Ink.Ink else Ink.Paper),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Text("✓", fontSize = InkType.Caption, color = Ink.Paper)
        }
    }
}

/** 批量操作条（M5.5）：删除所选 / 导出所选 / 取消（等宽 48dp 触区） */
@Composable
private fun BatchBar(
    count: Int,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(Ink.Hairline).background(Ink.Line))
        Row(Modifier.fillMaxWidth()) {
            SessionAction("删除所选", Modifier.weight(1f), enabled = count > 0, onClick = onDelete)
            SessionAction("导出所选", Modifier.weight(1f), enabled = count > 0, onClick = onExport)
            SessionAction("取消", Modifier.weight(1f), onClick = onCancel)
        }
    }
}

/** 列表项小号文字按钮（等宽、48dp 触区、无涟漪） */
@Composable
private fun SessionAction(
    label: String,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier.height(48.dp).inkClickable(enabled, onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = InkType.Caption,
            color = if (enabled) Ink.Ink else Ink.InkMid,
            maxLines = 1
        )
    }
}