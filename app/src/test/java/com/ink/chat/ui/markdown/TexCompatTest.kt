package com.ink.chat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5.7 公式兼容性增强验证（纯 JVM）。
 * 两类断言：
 * 1) 常见语料「零残留」扫描——解析结果不得含未识别命令原文（"\cmd"）；
 * 2) 关键修复点结构断言——\left./\right.、\limits、\boxed、\overset、颜色 / 空白 / 旧字体声明等。
 */
class TexCompatTest {

    // —— 节点树文本化（递归；用于残留检测） ——

    private fun textOf(n: TexNode): String = when (n) {
        is TexNode.Sym -> n.text
        is TexNode.Group -> n.nodes.joinToString("") { textOf(it) }
        is TexNode.Frac -> textOf(n.num) + "/" + textOf(n.den)
        is TexNode.Sqrt -> (n.index?.let { textOf(it) } ?: "") + textOf(n.body)
        is TexNode.Scripted -> textOf(n.base) +
            (n.sup?.let { "^{" + textOf(it) + "}" } ?: "") +
            (n.sub?.let { "_{" + textOf(it) + "}" } ?: "")
        is TexNode.BigOp -> n.sym +
            (n.sup?.let { "^{" + textOf(it) + "}" } ?: "") +
            (n.sub?.let { "_{" + textOf(it) + "}" } ?: "")
        is TexNode.Fence -> n.open + textOf(n.body) + n.close
        is TexNode.Sp -> if (n.em > 0f) " " else ""
        is TexNode.Env -> n.rows.joinToString(";") { row -> row.joinToString("&") { textOf(it) } }
        is TexNode.Accent -> textOf(n.body)
        is TexNode.Boxed -> textOf(n.body)
        is TexNode.Stacked -> textOf(n.base)
    }

    private fun renderText(tex: String): String = textOf(TexParser.parse(tex))

    private fun findFence(n: TexNode): TexNode.Fence? = when (n) {
        is TexNode.Fence -> n
        is TexNode.Group -> n.nodes.firstNotNullOfOrNull { findFence(it) }
        is TexNode.Scripted -> findFence(n.base)
        is TexNode.Boxed -> findFence(n.body)
        is TexNode.Stacked -> findFence(n.base)
        else -> null
    }

    // —— 1) 零残留扫描 ——

    @Test
    fun noResidueOnCommonCorpus() {
        val corpus = listOf(
            "\\frac{a}{b}", "\\sqrt[n]{x}", "x_i^2", "\\sum_{i=1}^{n} a_i", "\\sum\\limits_{i=1}^{n} a_i",
            "\\int_0^1 x dx", "\\int\\limits_0^1 x dx", "\\lim_{x\\to0} f(x)",
            "\\left(\\frac{a}{b}\\right)^2", "\\begin{cases}a&b\\\\c&d\\end{cases}",
            "\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}", "\\begin{array}{c|c}1&2\\end{array}",
            "\\overline{AB}", "\\vec{v}", "\\hat{x}", "\\widehat{AB}", "\\widetilde{AB}",
            "\\boxed{E=mc^2}", "\\boldsymbol{\\alpha}", "\\bm{v}", "\\mathscr{F}", "\\mathfrak{g}",
            "\\underbrace{a+b}_{n}", "\\overbrace{a+b}^{n}", "\\overset{\\text{def}}{=}", "\\underset{x}{\\min}",
            "\\textcolor{red}{x}+y", "\\color{blue}x", "{\\bf v}", "{\\it u}", "\\big[x\\big]", "\\Bigg\\{",
            "a\\mid b", "a\\parallel b", "a\\perp b", "A\\implies B", "A\\iff B", "A\\impliedby B",
            "\\lfloor x\\rfloor", "\\lceil y\\rceil", "\\bigcup_{i} A_i", "\\bigcap_i B_i", "\\coprod_j",
            "\\Pr(A)", "\\sgn(x)", "\\arg\\max", "\\deg f", "\\partial f", "\\nabla f", "\\oint_C",
            "\\angle ABC", "\\triangle ABC", "\\pm1", "\\mp2", "\\cdot", "\\cdots", "\\ddots", "\\vdots",
            "\\ddagger", "\\ominus", "\\diamond", "\\varkappa", "\\wp", "\\mathbb{R}", "\\mathcal{L}",
            "\\varnothing", "\\because", "\\therefore", "\\gets", "\\mapsto", "\\nearrow", "\\searrow",
            "\\wedge", "\\vee", "\\lnot", "\\top", "\\bot", "\\vdash", "\\dashv", "\\preceq", "\\succeq",
            "\\sqcup", "\\sqcap", "\\bigcirc", "\\bigoplus", "x\\hspace{1em}y", "\\mathbf{Q}",
            "\\left\\{ x \\right.", "\\left. y \\right|",
        )
        val residues = corpus.mapNotNull { tex ->
            val t = runCatching { renderText(tex) }.getOrElse { "THROW: $it" }
            if (t.contains("\\") || t.startsWith("THROW")) "$tex => $t" else null
        }
        assertTrue("含未识别命令残留：$residues", residues.isEmpty())
    }

    // —— 2) 结构断言（关键修复点） ——

    @Test
    fun leftRightDotNoDanglingDot() {
        val fence = findFence(TexParser.parse("\\left.\\frac{1}{x}\\right|_{x=1}"))
        assertTrue("expect Fence", fence != null)
        assertEquals(".", fence!!.open)
        assertEquals("|", fence.close)
    }

    @Test
    fun limitsAbsorbedIntoBigOp() {
        val node = TexParser.parse("\\sum\\limits_{i=1}^{n} a_i")
        val t = textOf(node)
        assertTrue("no residue: $t", !t.contains("limits"))
        assertTrue("sum kept: $t", t.contains("∑"))
    }

    @Test
    fun boxedNodePresent() {
        val node = TexParser.parse("\\boxed{x=1}")
        val boxed = (node as? TexNode.Group)?.nodes?.filterIsInstance<TexNode.Boxed>()?.firstOrNull()
        assertTrue("expect Boxed node", boxed != null)
        assertEquals("x=1", textOf(boxed!!))
    }

    @Test
    fun oversetStackedPresent() {
        val node = TexParser.parse("\\overset{\\text{def}}{=}")
        val stacked = (node as? TexNode.Group)?.nodes?.filterIsInstance<TexNode.Stacked>()?.firstOrNull()
        assertTrue("expect Stacked node", stacked != null)
        assertEquals("def", textOf(stacked!!.top!!))
        assertEquals("=", textOf(stacked.base))
    }

    @Test
    fun textcolorKeepsBodyOnly() {
        assertEquals("x", renderText("\\textcolor{red}{x}"))
        assertEquals("x", renderText("\\color{red}x"))
    }

    @Test
    fun oldFontDeclarationIgnored() {
        // 声明命令本身被丢弃；控制词后的空格在 v1 引擎中按普通空格保留（记录行为）
        assertEquals(" x", renderText("{\\bf x}"))
        assertEquals("x", renderText("{\\bf{x}}"))
    }

    @Test
    fun hspaceDropped() {
        assertEquals("ab", renderText("a\\hspace{1em}b"))
    }

    @Test
    fun bigDelimModifierIgnored() {
        val t = renderText("\\big( x \\big)")
        assertTrue("no residue: $t", !t.contains("big"))
        assertEquals("( x )", t)
    }

    // —— 3) 行内 Unicode 降级 ——

    @Test
    fun unicodeNewCommands() {
        assertEquals("x", TexUnicode.toUnicode("\\boxed{x}"))
        assertEquals("a b", TexUnicode.toUnicode("\\overset{a}{b}"))
        assertEquals("v", TexUnicode.toUnicode("\\boldsymbol{v}"))
        assertEquals("1/2", TexUnicode.toUnicode("\\cfrac{1}{2}"))
        assertEquals("x", TexUnicode.toUnicode("\\textcolor{red}{x}"))
        assertEquals("a+bₙ", TexUnicode.toUnicode("\\underbrace{a+b}_{n}"))
        assertEquals("[", TexUnicode.toUnicode("\\Big["))
        assertEquals(" x", TexUnicode.toUnicode("{\\bf x}"))
        assertEquals("x", TexUnicode.toUnicode("{\\bf{x}}"))
    }

    @Test
    fun unicodeNewSymbols() {
        assertTrue(TexUnicode.toUnicode("a\\mid b").contains("|"))
        assertTrue(TexUnicode.toUnicode("A\\implies B").contains("⟹"))
        assertTrue(TexUnicode.toUnicode("a\\perp b").contains("⊥"))
        assertTrue(TexUnicode.toUnicode("\\lfloor x \\rfloor").contains("⌊"))
        assertTrue(TexUnicode.toUnicode("\\lfloor x \\rfloor").contains("⌋"))
        assertTrue(TexUnicode.toUnicode("A\\iff B").contains("⟺"))
    }

    @Test
    fun unicodeCombiningMarks() {
        assertEquals("x\u0302", TexUnicode.toUnicode("\\widehat{x}"))
        assertEquals("y\u0303", TexUnicode.toUnicode("\\widetilde{y}"))
        assertEquals("A\u20D7B\u20D7", TexUnicode.toUnicode("\\overrightarrow{AB}"))
        assertEquals("x\u0301", TexUnicode.toUnicode("\\acute{x}"))
        assertEquals("x\u030C", TexUnicode.toUnicode("\\check{x}"))
    }
}
