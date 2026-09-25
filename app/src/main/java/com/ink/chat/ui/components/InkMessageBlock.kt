package com.ink.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ink.chat.domain.model.Message
import com.ink.chat.domain.model.MessageRole
import com.ink.chat.domain.model.MessageStatus
import com.ink.chat.ui.markdown.InkMarkdown
import com.ink.chat.ui.theme.Ink
import com.ink.chat.ui.theme.InkType
import com.ink.chat.ui.theme.LocalInkFontFamily
import com.ink.chat.ui.theme.inkLh
import com.ink.chat.util.TimeFmt
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 消息块（§3.3-⑧，去气泡化书页排版）。
 * 用户消息：左侧 2dp 黑引用线 + 左缩进 12dp，正文 InkDeep；
 * 助手消息：全宽；元信息 Caption（模型 · 时间 · 已思考 · token）+ 思考面板 + 正文。
 * 点击块 → 展开操作条（复制/重新生成/删除；F4：无长按、无滑动）。
 */
@Composable
fun InkMessageBlock(
    message: Message,
    modelName: String = "DeepSeek",
    selected: Boolean = false,
    /** 当前会话最后一条 assistant 的 id（重试/重新生成仅对最后一条开放，避免乱序） */
    lastAssistantId: Long? = null,
    /** 全局有请求进行中（禁止并发触发重试） */
    busy: Boolean = false,
    onClickBlock: () -> Unit = {},
    onCopy: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val isLastAssistant = message.role == MessageRole.ASSISTANT && message.id == lastAssistantId
    Column(
        Modifier
            .fillMaxWidth()
            .inkClickable(onClickBlock)
            .padding(vertical = 12.dp)
    ) {
        when (message.role) {
            MessageRole.USER -> UserContent(message)
            MessageRole.ASSISTANT -> AssistantContent(message, modelName, isLastAssistant, busy, onRetry)
        }
        if (selected) {
            Spacer(Modifier.height(Ink.PadTight))
            ActionBar(
                showRegenerate = isLastAssistant && message.status == MessageStatus.DONE,
                busy = busy,
                onCopy = onCopy,
                onRegenerate = onRegenerate,
                onDelete = onDelete,
            )
        }
    }
}

// —— 用户消息 ——

@Composable
private fun UserContent(message: Message) {
    val section = remember(message.content) { parseFileSection(message.content) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Box(Modifier.width(Ink.FocusLine).fillMaxHeight().background(Ink.Ink))
        Spacer(Modifier.width(Ink.PadLine))
        if (section == null) {
            Text(
                text = message.content,
                modifier = Modifier.weight(1f),
                fontSize = InkType.Body,
                lineHeight = inkLh(30f),
                color = Ink.InkDeep,
            )
        } else {
            FileSectionView(message.id, section, Modifier.weight(1f))
        }
    }
}

/** 文件消息解析结果（[文件] name + 正文 + 其余文字；M5.5） */
private data class FileSection(val name: String, val body: String, val rest: String)

/** 解析 buildUserContent 的文件块：`[文件] name\n<body>\n[文件结束]`（单文件，v1） */
private fun parseFileSection(content: String): FileSection? {
    if (!content.startsWith("[文件] ")) return null
    val nl = content.indexOf('\n')
    if (nl < 0) return null
    val name = content.substring(5, nl).trim()
    val endMarker = "\n[文件结束]"
    val endIdx = content.indexOf(endMarker, nl + 1)
    if (endIdx < 0) return null
    val body = content.substring(nl + 1, endIdx)
    val rest = content.substring(endIdx + endMarker.length).trimStart('\n')
    return FileSection(name, body, rest)
}

/** 文件消息折叠视图：一行 `[文件] 名称 · N 字`（点击展开全文）+ 其余文字 */
@Composable
private fun FileSectionView(messageId: Long, section: FileSection, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Ink.Touch)
                .inkClickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = (if (expanded) "▾" else "▸") +
                    "[文件] " + section.name + " · " + section.body.length + " 字",
                fontSize = InkType.Alt,
                color = Ink.InkDeep,
            )
        }
        if (expanded) {
            Text(
                text = section.body,
                modifier = Modifier.padding(bottom = Ink.PadTight),
                fontSize = InkType.Alt,
                lineHeight = inkLh(26f),
                color = Ink.InkMid,
            )
        }
        if (section.rest.isNotBlank()) {
            if (expanded) Spacer(Modifier.height(6.dp))
            Text(
                text = section.rest,
                fontSize = InkType.Body,
                lineHeight = inkLh(30f),
                color = Ink.InkDeep,
            )
        }
    }
}

// —— 助手消息 ——

@Composable
private fun AssistantContent(
    message: Message,
    modelName: String,
    isLastAssistant: Boolean,
    busy: Boolean,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(metaLine(message, modelName), fontSize = InkType.Caption, color = Ink.InkMid)

        val reasoning = message.reasoningContent
        if (!reasoning.isNullOrEmpty()) {
            Spacer(Modifier.height(2.dp))
            ThinkPanel(message.id, reasoning, message.thinkMs)
        }

        Spacer(Modifier.height(6.dp))

        when {
            message.status == MessageStatus.SENDING && message.content.isEmpty() -> {
                WaitingLine(message.createdAt)
            }

            message.status == MessageStatus.FAILED -> {
                if (message.content.isNotEmpty()) {
                    InkMarkdown(message.content, color = Ink.Ink)
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    text = "！" + (message.error ?: "发送失败。"),
                    fontSize = InkType.Alt,
                    color = Ink.Ink,
                    fontWeight = FontWeight.Bold,
                )
                RetryRow(show = isLastAssistant, busy = busy, onRetry = onRetry)
            }

            message.status == MessageStatus.CANCELED -> {
                if (message.content.isNotEmpty()) {
                    InkMarkdown(message.content, color = Ink.Ink)
                    Spacer(Modifier.height(6.dp))
                }
                Text("已中止。可继续编辑或重新发送。", fontSize = InkType.Alt, color = Ink.InkMid)
                RetryRow(show = isLastAssistant, busy = busy, onRetry = onRetry)
            }

            else -> {
                InkMarkdown(message.content, color = Ink.Ink)
            }
        }
    }
}

/** 元信息行：DeepSeek · 14:32 · 已思考 8s · ↑1.2k ↓0.8k */
private fun metaLine(message: Message, modelName: String): String {
    val sb = StringBuilder()
    sb.append(modelName).append(" · ").append(TimeFmt.hhmm(message.createdAt))
    if (message.thinkMs != null && !message.reasoningContent.isNullOrEmpty()) {
        sb.append(" · 已思考 ").append(fmtSeconds(message.thinkMs))
    }
    val pt = TimeFmt.tokens(message.promptTokens)
    val ct = TimeFmt.tokens(message.completionTokens)
    if (pt != null || ct != null) {
        sb.append(" · ")
        if (pt != null) sb.append("↑").append(pt)
        if (pt != null && ct != null) sb.append(" ")
        if (ct != null) sb.append("↓").append(ct)
    }
    return sb.toString()
}

/**
 * 思考面板（§3.3-⑨）。
 * 折叠态一行 `▸ 思考过程 · 8s`；展开态：左 1dp 竖线 + 缩进 12dp，正文 T-Alt，首行缩进 2 字符。
 */
@Composable
private fun ThinkPanel(messageId: Long, text: String, thinkMs: Long?) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    val ff = LocalInkFontFamily.current
    val suffix = if (thinkMs != null) " · " + fmtSeconds(thinkMs) else ""
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Ink.Touch)
                .inkClickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = (if (expanded) "▾" else "▸") + "思考过程" + suffix,
                fontSize = InkType.Caption,
                color = Ink.InkMid,
            )
        }
        if (expanded) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
            ) {
                Box(Modifier.width(Ink.Hairline).fillMaxHeight().background(Ink.Line))
                Spacer(Modifier.width(Ink.PadLine))
                Text(
                    text = text,
                    modifier = Modifier.weight(1f).padding(bottom = Ink.PadTight),
                    style = TextStyle(
                        fontSize = InkType.Alt,
                        lineHeight = inkLh(26f),
                        color = Ink.InkMid,
                        textIndent = TextIndent(firstLine = 30.sp),
                        fontFamily = ff,
                    ),
                )
            }
        }
    }
}

/** 等待行（§2.2 / F2）：`正在思考…`，5 秒离散跳变「已等待 N 秒」 */
@Composable
private fun WaitingLine(startedAt: Long) {
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(startedAt) {
        while (true) {
            delay(5000)
            val s = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            elapsed = s / 5 * 5
        }
    }
    Text(
        text = if (elapsed < 5) "正在思考…" else "正在思考…已等待 ${elapsed}秒",
        fontSize = InkType.Alt,
        color = Ink.InkMid,
    )
}

/** 失败/中止后的重试入口（§2.1 A10；仅最后一条回答开放） */
@Composable
private fun RetryRow(show: Boolean, busy: Boolean, onRetry: () -> Unit) {
    if (show) {
        Box(
            Modifier
                .heightIn(min = Ink.Touch)
                .inkClickable(enabled = !busy) { onRetry() },
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                "重试",
                fontSize = InkType.Alt,
                color = if (busy) Ink.InkMid else Ink.Ink,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// —— 消息操作条（§3.4-②）——

@Composable
private fun ActionBar(
    showRegenerate: Boolean,
    busy: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(Ink.StrongLine, Ink.Ink),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionText("复制", enabled = true) { onCopy() }
        if (showRegenerate) {
            ActionDivider()
            ActionText("重新生成", enabled = !busy) { onRegenerate() }
        }
        ActionDivider()
        ActionText("删除", enabled = true) { onDelete() }
    }
}

@Composable
private fun ActionDivider() {
    Box(Modifier.width(Ink.Hairline).height(24.dp).background(Ink.Line))
}

@Composable
private fun ActionText(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = Ink.Touch)
            .inkClickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = InkType.Alt,
            color = if (enabled) Ink.Ink else Ink.InkMid,
        )
    }
}

private fun fmtSeconds(ms: Long): String = "${(ms / 1000.0).roundToInt()}s"