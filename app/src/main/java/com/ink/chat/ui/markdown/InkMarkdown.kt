package com.ink.chat.ui.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ink.chat.ui.components.inkClickable
import com.ink.chat.ui.theme.Ink
import com.ink.chat.ui.theme.InkType
import com.ink.chat.ui.theme.LocalInkFontFamily
import com.ink.chat.ui.theme.inkLh
import kotlinx.coroutines.delay

/**
 * Markdown 正文渲染器（M5.5 C4 / C5 / C6 + 数学公式）。
 * 块级：标题 / 段落 / 列表 / 引用 / 代码块（含复制）/ 表格（等宽预排版）/ 公式块 / 分割线；
 * 行内：加粗 / 斜体 / 行内代码 / 删除线 / 链接（v1 下划线近似）/ 行内公式（Unicode 近似）。
 * 超长保护：超过 [MAX_MD_CHARS] 降级纯文本（文件内容等场景防卡顿）。
 */
private const val MAX_MD_CHARS = 40000

@Composable
fun InkMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ink.Ink,
) {
    if (text.length > MAX_MD_CHARS) {
        Text(
            text = text,
            modifier = modifier,
            fontSize = InkType.Body,
            lineHeight = inkLh(30f),
            color = color,
        )
        return
    }
    val blocks = remember(text) { MdParser.parse(text) }
    Row(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { b ->
                when (b) {
                    is MdBlock.Heading -> HeadingBlock(b, color)
                    is MdBlock.Paragraph -> ParagraphBlock(b, color)
                    is MdBlock.Item -> ItemBlock(b, color)
                    is MdBlock.Quote -> QuoteBlock(b)
                    is MdBlock.CodeBlock -> CodeBlockView(b)
                    is MdBlock.Table -> TableView(b)
                    is MdBlock.MathBlock -> MathBlockView(b, color)
                    MdBlock.Divider -> DividerView()
                }
            }
        }
    }
}

// —— 块级渲染 ——

@Composable
private fun HeadingBlock(b: MdBlock.Heading, color: Color) {
    val ff = LocalInkFontFamily.current
    val (size, top) = when (b.level) {
        1 -> 22f to 8.dp
        2 -> 20f to 6.dp
        3 -> 19f to 4.dp
        else -> 17f to 2.dp
    }
    Column(Modifier.padding(top = top)) {
        InlineText(
            spans = b.spans,
            style = TextStyle(
                fontSize = size.sp,
                lineHeight = inkLh(size * 1.45f),
                fontWeight = FontWeight.SemiBold,
                color = color,
                fontFamily = ff,
            ),
        )
        // 一级 / 二级标题下细线（M5.7 排版优化：强化层级，书页感）
        if (b.level <= 2) {
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(Ink.Hairline).background(Ink.Line))
        }
    }
}

@Composable
private fun ParagraphBlock(b: MdBlock.Paragraph, color: Color) {
    val ff = LocalInkFontFamily.current
    InlineText(
        spans = b.spans,
        style = TextStyle(
            fontSize = InkType.Body,
            lineHeight = inkLh(30f),
            color = color,
            fontFamily = ff,
        ),
    )
}

@Composable
private fun ItemBlock(b: MdBlock.Item, color: Color) {
    val ff = LocalInkFontFamily.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (12 + b.depth * 16).dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = b.marker,
            modifier = Modifier.padding(end = 8.dp, top = 1.dp),
            fontSize = InkType.Alt,
            color = Ink.InkMid,
        )
        InlineText(
            spans = b.spans,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                fontSize = InkType.Body,
                lineHeight = inkLh(28f),
                color = color,
                fontFamily = ff,
            ),
        )
    }
}

@Composable
private fun QuoteBlock(b: MdBlock.Quote) {
    val ff = LocalInkFontFamily.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Box(Modifier.width(Ink.FocusLine).fillMaxHeight().background(Ink.InkMid))
        Spacer(Modifier.width(Ink.PadLine))
        Column(Modifier.weight(1f)) {
            b.lines.forEach { line ->
                if (line.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                } else {
                    InlineText(
                        spans = line,
                        style = TextStyle(
                            fontSize = InkType.Alt,
                            lineHeight = inkLh(26f),
                            color = Ink.InkDeep,
                            fontFamily = ff,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun DividerView() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(Ink.Hairline)
            .background(Ink.Line)
    )
}

@Composable
private fun CodeBlockView(b: MdBlock.CodeBlock) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(Ink.Hairline, Ink.Line)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Ink.Mist)
                .padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = b.lang.ifEmpty { "code" },
                modifier = Modifier.weight(1f),
                fontSize = InkType.Caption,
                color = Ink.InkMid,
            )
            CodeCopyButton(b.code)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Text(
                text = b.code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = inkLh(20f),
                color = Ink.Ink,
            )
        }
    }
}

/** 代码块「复制」文字按钮（C5）：点击 → 剪贴板；短暂回显「已复制」 */
@Composable
private fun CodeCopyButton(code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Box(
        Modifier
            .height(36.dp)
            .inkClickable {
                clipboard.setText(AnnotatedString(code))
                copied = true
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (copied) "已复制" else "复制",
            fontSize = InkType.Caption,
            color = if (copied) Ink.InkMid else Ink.Ink,
        )
    }
}

@Composable
private fun TableView(b: MdBlock.Table) {
    val text = remember(b) { formatTable(b) }
    Box(
        Modifier
            .fillMaxWidth()
            .border(Ink.Hairline, Ink.Line)
    ) {
        Box(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = inkLh(20f),
                color = Ink.Ink,
            )
        }
    }
}

/** 表格 → 等宽预排版文本（CJK 记 2 宽度） */
private fun formatTable(t: MdBlock.Table): String {
    val rows = listOf(t.header) + t.rows
    val cols = rows.maxOfOrNull { it.size } ?: 0
    if (cols == 0) return ""
    val widths = IntArray(cols)
    rows.forEach { r ->
        r.forEachIndexed { i, cell -> if (i < cols) widths[i] = maxOf(widths[i], cellWidth(cell)) }
    }
    fun fmt(r: List<String>): String = (0 until cols).joinToString(" | ") { i ->
        val cell = r.getOrNull(i) ?: ""
        cell + " ".repeat((widths[i] - cellWidth(cell)).coerceAtLeast(0))
    }
    val sb = StringBuilder()
    sb.append(fmt(t.header)).append('\n')
    sb.append(widths.joinToString("-+-") { "-".repeat(it) }).append('\n')
    t.rows.forEach { sb.append(fmt(it)).append('\n') }
    return sb.toString().trimEnd('\n')
}

private fun cellWidth(s: String): Int {
    var w = 0
    for (ch in s) {
        val code = ch.code
        w += if (code > 0x2E7F && code != 0x3000 && (code < 0xFF00 || code > 0xFF60)) 2
        else if (code in 0x1100..0x115F) 2
        else if (code in 0xAC00..0xD7A3) 2
        else 1
    }
    return w
}

@Composable
private fun MathBlockView(b: MdBlock.MathBlock, color: Color) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val box = remember(b.tex, color, density) {
        TexRender.measure(b.tex, 19f, measurer, color, density)
    }
    if (box == null) {
        // 解析失败：原文降级（不崩溃）
        Text(
            text = b.tex,
            fontFamily = FontFamily.Monospace,
            fontSize = InkType.Alt,
            color = Ink.InkMid,
        )
        return
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Canvas(
                Modifier
                    .padding(vertical = 6.dp)
                    .size(
                        width = with(density) { box.width.toDp() },
                        height = with(density) { box.height.toDp() },
                    )
            ) {
                drawTexBox(box)
            }
        }
    }
}

// —— 行内渲染（文本 + 公式 Unicode 精排） ——
// 注：当前 Compose 1.6 无 InlineContent API（1.7+ 提供）；
// 行内公式以 Unicode 上下标/数学符号近似呈现，块级公式走 TexRender 完整排版。

@Composable
private fun InlineText(
    spans: List<MdInline>,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val inkColor = Ink.Ink
    val codeBg = Ink.Mist
    val codeFg = Ink.InkDeep
    val linkColor = Ink.InkDeep

    val built = remember(spans, style, inkColor, codeBg, codeFg, linkColor) {
        val sb = AnnotatedString.Builder()
        spans.forEach { s ->
            when (s) {
                is MdInline.Text -> {
                    var st = SpanStyle()
                    if (s.bold) st = st.copy(fontWeight = FontWeight.SemiBold)
                    if (s.italic) st = st.copy(fontStyle = FontStyle.Italic)
                    if (s.strike) st = st.copy(textDecoration = TextDecoration.LineThrough)
                    sb.withStyle(st) { append(s.text) }
                }
                is MdInline.Code -> {
                    sb.withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBg,
                            color = codeFg,
                        )
                    ) { append(s.text) }
                }
                is MdInline.Link -> {
                    // v1：下划线+深色呈现（链接点击打开留待后续评估）
                    sb.withStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                    ) { append(s.text) }
                }
                is MdInline.Math -> {
                    sb.withStyle(SpanStyle(fontFamily = FontFamily.Serif)) {
                        append(TexUnicode.toUnicode(s.tex))
                    }
                }
            }
        }
        sb.toAnnotatedString()
    }

    Text(
        text = built,
        modifier = modifier,
        style = style,
    )
}