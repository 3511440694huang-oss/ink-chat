package com.ink.chat.ui.markdown

/**
 * Markdown 两级解析模型（M5.5 §4.7-⑤：块级 + 行内；自研渲染，无第三方依赖）。
 * 设计取舍：段落内保留换行（AI 文本自然换行常见）；表格以等宽预排版呈现。
 */

// —— 行内（第二级） ——

sealed interface MdInline {
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
    ) : MdInline

    data class Code(val text: String) : MdInline
    data class Link(val text: String, val url: String) : MdInline

    /** 行内公式 `$…$`（TeX 子集；见 TexRender） */
    data class Math(val tex: String) : MdInline
}

// —— 块级（第一级） ——

sealed interface MdBlock {
    data class Paragraph(val spans: List<MdInline>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdInline>) : MdBlock

    /** 列表项：marker =「·」或「1.」；depth = 0..3 级缩进 */
    data class Item(val marker: String, val depth: Int, val spans: List<MdInline>) : MdBlock

    /** 引用块：每行一段行内序列（空 list = 空行） */
    data class Quote(val lines: List<List<MdInline>>) : MdBlock

    data class CodeBlock(val lang: String, val code: String) : MdBlock

    /** 表格（首行为表头；渲染 = 等宽预排版 + 横向滚动） */
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock

    /** 块级公式 `$$…$$` */
    data class MathBlock(val tex: String) : MdBlock

    object Divider : MdBlock
}
