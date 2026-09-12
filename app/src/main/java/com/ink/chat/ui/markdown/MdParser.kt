package com.ink.chat.ui.markdown

/**
 * Markdown 解析器（M5.5 C4：自研两级解析）。
 * 块级：段落 / 标题 / 列表 / 引用 / 代码块 / 表格 / 分割线 / 公式块；
 * 行内：加粗 / 斜体 / 行内代码 / 删除线 / 链接 / 行内公式。
 * 流式容错：未闭合代码块按已收内容渲染；未闭合公式 / ` 退化正文。
 */
object MdParser {

    fun parse(content: String): List<MdBlock> {
        val lines = content.split('\n')
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                // 代码块（``` / ~~~）
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    val fence = if (trimmed.startsWith("```")) "```" else "~~~"
                    val lang = trimmed.removePrefix(fence).trim()
                    val sb = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                        sb.append(lines[i]).append('\n')
                        i++
                    }
                    if (i < lines.size) i++ // 吃掉闭合行
                    blocks += MdBlock.CodeBlock(lang, sb.toString().trimEnd('\n'))
                }

                // 块级公式（$$ 独占一行 / 单行 $$…$$）
                trimmed == "$$" -> {
                    val sb = StringBuilder()
                    i++
                    while (i < lines.size && lines[i].trim() != "$$") {
                        sb.append(lines[i]).append('\n')
                        i++
                    }
                    if (i < lines.size) i++
                    val tex = sb.toString().trim()
                    if (tex.isNotEmpty()) blocks += MdBlock.MathBlock(tex)
                }

                trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length > 4 -> {
                    blocks += MdBlock.MathBlock(trimmed.removeSurrounding("$$").trim())
                    i++
                }

                // 块级公式（\[ 独占一行 / 单行 \[…\]；与 $$ 等价，DeepSeek 常用写法）
                trimmed == "\\[" ||
                    (trimmed.startsWith("\\[") && trimmed.endsWith("\\]") && trimmed.length > 3) -> {
                    if (trimmed != "\\[") {
                        val tex = trimmed.removeSurrounding("\\[", "\\]").trim()
                        if (tex.isNotEmpty()) blocks += MdBlock.MathBlock(tex)
                        i++
                    } else {
                        val sb = StringBuilder()
                        i++
                        while (i < lines.size && !lines[i].contains("\\]")) {
                            sb.append(lines[i]).append('\n')
                            i++
                        }
                        if (i < lines.size) {
                            sb.append(lines[i].substringBefore("\\]"))
                            i++
                        }
                        val tex = sb.toString().trim()
                        if (tex.isNotEmpty()) blocks += MdBlock.MathBlock(tex)
                    }
                }

                // 分割线
                isDivider(trimmed) -> {
                    blocks += MdBlock.Divider
                    i++
                }

                // 标题
                headingLevel(trimmed) != null -> {
                    val lv = headingLevel(trimmed)!!
                    val body = trimmed.removePrefix("#".repeat(lv)).trim()
                    blocks += MdBlock.Heading(lv, parseInline(body))
                    i++
                }

                // 引用
                trimmed.startsWith(">") -> {
                    val qlines = mutableListOf<List<MdInline>>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        val raw = lines[i].trim().removePrefix(">").trimStart()
                        qlines += if (raw.isEmpty()) emptyList() else parseInline(raw)
                        i++
                    }
                    blocks += MdBlock.Quote(qlines)
                }

                // 表格（表头 + 分隔行）
                trimmed.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1]) -> {
                    val header = splitTableRow(trimmed)
                    i += 2 // 表头 + 分隔行
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().contains('|') && lines[i].trim().isNotEmpty()) {
                        rows += splitTableRow(lines[i].trim())
                        i++
                    }
                    blocks += MdBlock.Table(header, rows)
                }

                // 无序列表
                bulletBody(line) != null -> {
                    val depth = indentDepth(line)
                    blocks += MdBlock.Item("·", depth, parseInline(bulletBody(line)!!))
                    i++
                }

                // 有序列表
                orderedBody(line) != null -> {
                    val (marker, body) = orderedBody(line)!!
                    blocks += MdBlock.Item(marker, indentDepth(line), parseInline(body))
                    i++
                }

                // 空行
                line.isBlank() -> i++

                // 段落（收集到空行 / 块级起始）
                else -> {
                    val sb = mutableListOf<String>()
                    while (i < lines.size && lines[i].isNotBlank() && !isBlockStart(lines, i)) {
                        sb += lines[i].trim()
                        i++
                    }
                    val text = sb.joinToString("\n")
                    if (text.isNotEmpty()) blocks += MdBlock.Paragraph(parseInline(text))
                }
            }
        }
        return blocks
    }

    // —— 行内解析 ——

    fun parseInline(text: String): List<MdInline> {
        val out = mutableListOf<MdInline>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                out += MdInline.Text(sb.toString())
                sb.setLength(0)
            }
        }
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                // 行内公式 \(…\) / \[…\]（DeepSeek 常用写法；须先于通用转义判定）
                c == '\\' && i + 1 < n && text[i + 1] == '(' -> {
                    val close = text.indexOf("\\)", i + 2)
                    val tex = if (close > i + 2) text.substring(i + 2, close).trim() else null
                    if (tex != null && tex.isNotEmpty()) {
                        flush(); out += MdInline.Math(tex); i = close + 2
                    } else {
                        sb.append('('); i += 2
                    }
                }

                c == '\\' && i + 1 < n && text[i + 1] == '[' -> {
                    val close = text.indexOf("\\]", i + 2)
                    val tex = if (close > i + 2) text.substring(i + 2, close).trim() else null
                    if (tex != null && tex.isNotEmpty()) {
                        flush(); out += MdInline.Math(tex); i = close + 2
                    } else {
                        sb.append('['); i += 2
                    }
                }

                // 转义
                c == '\\' && i + 1 < n && text[i + 1] in "\\`*_{}[]()#+-.!$~|" -> {
                    sb.append(text[i + 1]); i += 2
                }

                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush(); out += MdInline.Code(text.substring(i + 1, end)); i = end + 1
                    } else {
                        sb.append(c); i++
                    }
                }

                c == '$' -> {
                    val end = text.indexOf('$', i + 1)
                    if (end > i + 1) {
                        val tex = text.substring(i + 1, end)
                        // 行内公式规则：开闭 $ 内侧均不得贴空格（排除「$100 and $200」类货币场景）
                        if (tex.isNotBlank() && !tex.contains('\n') &&
                            !tex.first().isWhitespace() && !tex.last().isWhitespace()
                        ) {
                            flush(); out += MdInline.Math(tex.trim()); i = end + 1
                        } else {
                            sb.append(c); i++
                        }
                    } else {
                        sb.append(c); i++
                    }
                }

                c == '*' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        flush(); out += MdInline.Text(text.substring(i + 2, end), bold = true); i = end + 2
                    } else {
                        sb.append(c); i++
                    }
                }

                c == '*' || (c == '_' && (i == 0 || text[i - 1].isWhitespace())) -> {
                    val end = text.indexOf(c, i + 1)
                    if (end > i + 1) {
                        val inner = text.substring(i + 1, end)
                        if (inner.isNotBlank() && !inner.startsWith(' ') && !inner.endsWith(' ') && !inner.contains('\n')) {
                            flush(); out += MdInline.Text(inner, italic = true); i = end + 1
                        } else {
                            sb.append(c); i++
                        }
                    } else {
                        sb.append(c); i++
                    }
                }

                c == '~' && i + 1 < n && text[i + 1] == '~' -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i + 2) {
                        flush(); out += MdInline.Text(text.substring(i + 2, end), strike = true); i = end + 2
                    } else {
                        sb.append(c); i++
                    }
                }

                c == '[' -> {
                    val close = text.indexOf(']', i + 1)
                    val open = if (close > 0 && close + 1 < n && text[close + 1] == '(') {
                        text.indexOf(')', close + 2)
                    } else -1
                    if (close > i && open > close) {
                        flush()
                        out += MdInline.Link(text.substring(i + 1, close), text.substring(close + 2, open))
                        i = open + 1
                    } else {
                        sb.append(c); i++
                    }
                }

                else -> {
                    sb.append(c); i++
                }
            }
        }
        flush()
        return out
    }

    // —— 内部工具 ——

    private fun headingLevel(line: String): Int? {
        if (!line.startsWith("#")) return null
        val level = line.takeWhile { it == '#' }.length
        if (level > 6) return null
        if (line.length > level && !line[level].isWhitespace()) return null
        return level
    }

    private fun isDivider(line: String): Boolean {
        if (line.length < 3) return false
        val first = line[0]
        if (first != '-' && first != '*' && first != '_') return false
        if (line.any { it != first && !it.isWhitespace() }) return false
        return line.count { it == first } >= 3
    }

    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        if (!t.any { it == '-' }) return false
        return t.all { it == '-' || it == ':' || it == '|' || it.isWhitespace() }
    }

    private fun splitTableRow(line: String): List<String> =
        line.trim().removeSurrounding("|").split('|').map { it.trim() }

    private fun bulletBody(line: String): String? {
        val t = line.trim()
        if (t.length < 2) return null
        if (t[0] != '-' && t[0] != '*' && t[0] != '+') return null
        if (!t[1].isWhitespace()) return null
        val body = t.substring(1).trim()
        return body.ifEmpty { null }
    }

    private fun orderedBody(line: String): Pair<String, String>? {
        val t = line.trim()
        var i = 0
        while (i < t.length && t[i].isDigit() && i < 3) i++
        if (i == 0 || i >= t.length) return null
        if (t[i] != '.' && t[i] != '、' && t[i] != ')') return null
        if (i + 1 >= t.length || !t[i + 1].isWhitespace()) return null
        val marker = t.substring(0, i + 1)
        val body = t.substring(i + 1).trim()
        return if (body.isEmpty()) null else marker to body
    }

    private fun indentDepth(line: String): Int {
        var w = 0
        for (c in line) {
            if (c == ' ') w += 1 else if (c == '\t') w += 2 else break
        }
        return (w / 2).coerceIn(0, 3)
    }

    /** 某行是否为块级起始（用于段落收集时截断） */
    private fun isBlockStart(lines: List<String>, i: Int): Boolean {
        val t = lines[i].trim()
        return t.startsWith("```") || t.startsWith("~~~") ||
            t == "$$" || (t.startsWith("$$") && t.endsWith("$$") && t.length > 4) ||
            t == "\\[" || (t.startsWith("\\[") && t.endsWith("\\]") && t.length > 3) ||
            isDivider(t) || headingLevel(t) != null || t.startsWith(">") ||
            bulletBody(lines[i]) != null || orderedBody(lines[i]) != null ||
            (t.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1]))
    }
}