package com.ink.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.ink.chat.domain.model.ConversationUsage
import com.ink.chat.ui.theme.Ink
import com.ink.chat.ui.theme.InkType
import com.ink.chat.util.Pricing
import com.ink.chat.util.TimeFmt

/**
 * 纸面对话框容器：2dp 黑框、直角、静态出现（无淡入动画）。
 * 遮罩：黑 30%（静态），点击外部关闭。
 */
@Composable
fun InkDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val view = LocalView.current
        LaunchedEffect(Unit) {
            // 静态遮罩 30%，避免系统默认深遮罩在墨屏上显脏
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0.30f)
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .background(Ink.Paper)
                    .border(Ink.FocusLine, Ink.Ink)
                    .padding(vertical = 8.dp),
                content = content
            )
        }
    }
}

/** 单选列表对话框：点击行即选中并关闭；当前项 = 反色 */
@Composable
fun InkSelectDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    InkDialog(onDismiss = onDismiss) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            fontSize = InkType.Title,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Ink
        )
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .fillMaxWidth()
                    .inkClickable { onSelect(index) }
                    .background(if (selected) Ink.Ink else Ink.Paper)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    text = label,
                    fontSize = InkType.Body,
                    color = if (selected) Ink.Paper else Ink.Ink,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                Modifier.inkClickable(onDismiss).padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text("取消", fontSize = InkType.Alt, color = Ink.Ink)
            }
        }
    }
}

/** 对话框文字按钮（触区 ≥48dp） */
@Composable
fun InkDialogTextButton(label: String, onClick: () -> Unit) {
    Box(Modifier.inkClickable(onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(label, fontSize = InkType.Alt, color = Ink.Ink, fontWeight = FontWeight.SemiBold)
    }
}

/** 二次确认对话框（如删除消息，§2.1 A8）；按钮行右对齐 */
@Composable
fun InkConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    InkDialog(onDismiss = onDismiss) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            fontSize = InkType.Title,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Ink
        )
        Text(
            text = message,
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
            InkDialogTextButton(confirmLabel, onConfirm)
        }
    }
}

/** 单行文本输入对话框（重命名会话等；空输入不提交） */
@Composable
fun InkInputDialog(
    title: String,
    initial: String,
    confirmLabel: String = "确定",
    placeholder: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    InkDialog(onDismiss = onDismiss) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            fontSize = InkType.Title,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Ink
        )
        InkInputField(
            value = text,
            onValueChange = { text = it },
            placeholder = placeholder,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            InkDialogTextButton("取消", onDismiss)
            InkDialogTextButton(confirmLabel) {
                if (text.isNotBlank()) onConfirm(text.trim())
            }
        }
    }
}

/** 本次对话用量对话框（M5.6）：token / 缓存命中率 / 花费估算 */
@Composable
fun InkUsageDialog(usage: ConversationUsage, onDismiss: () -> Unit) {
    InkDialog(onDismiss = onDismiss) {
        Text(
            text = "本次对话用量",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            fontSize = InkType.Title,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Ink
        )
        UsageRow("计费轮次", "${usage.rounds} 次")
        UsageRow(
            "输入 tokens",
            "↑" + (TimeFmt.tokens(usage.inputTokens) ?: "0") +
                "（命中 " + (TimeFmt.tokens(usage.cacheHitTokens) ?: "0") +
                " / 未命中 " + (TimeFmt.tokens(usage.cacheMissTokens) ?: "0") + "）"
        )
        UsageRow("输出 tokens", "↓" + (TimeFmt.tokens(usage.outputTokens) ?: "0"))
        UsageRow("缓存命中率", usage.hitRatePercent?.let { "$it%" } ?: "—")
        UsageRow("估算花费", if (usage.costCny > 0) Pricing.money(usage.costCny) else "—")
        Text(
            text = "按官方价目表估算（含峰谷时段价），仅供参考；实际以账单为准。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            fontSize = InkType.Caption,
            color = Ink.InkMid
        )
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            InkDialogTextButton("好的", onDismiss)
        }
    }
}

/** 用量明细行：左标签 / 右数值（M5.6） */
@Composable
private fun UsageRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.9f),
            fontSize = InkType.Alt,
            color = Ink.InkMid
        )
        Text(
            text = value,
            modifier = Modifier.weight(1.1f),
            fontSize = InkType.Alt,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Ink,
            textAlign = TextAlign.End
        )
    }
}