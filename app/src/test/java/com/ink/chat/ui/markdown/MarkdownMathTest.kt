package com.ink.chat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5.6 修订验证：行内 / 块级公式解析与 Unicode 转换（纯 JVM，无 Android 依赖） */
class MarkdownMathTest {

    @Test
    fun dollarInline() {
        val spans = MdParser.parseInline("勾股定理 \$a^2+b^2=c^2\$ 成立")
        val math = spans.filterIsInstance<MdInline.Math>()
        assertEquals(1, math.size)
        assertEquals("a^2+b^2=c^2", math[0].tex)
        assertEquals("a²+b²=c²", TexUnicode.toUnicode(math[0].tex))
    }

    @Test
    fun currencyNotMath() {
        val spans = MdParser.parseInline("价格是 \$100 到 \$200 之间")
        assertTrue(spans.none { it is MdInline.Math })
    }

    @Test
    fun parenInline() {
        val spans = MdParser.parseInline("解得 \\(x=1\\) 与 \\[y=2\\]")
        val math = spans.filterIsInstance<MdInline.Math>()
        assertEquals(2, math.size)
        assertEquals(listOf("x=1", "y=2"), math.map { it.tex })
    }

    @Test
    fun bracketBlock() {
        val blocks = MdParser.parse("\\[\n\\frac{a}{b}\n\\]")
        val mb = blocks.filterIsInstance<MdBlock.MathBlock>()
        assertEquals(1, mb.size)
        assertEquals("\\frac{a}{b}", mb[0].tex)
    }

    @Test
    fun casesUnicode() {
        val out = TexUnicode.toUnicode("\\begin{cases}x=1\\\\y=2\\end{cases}")
        assertTrue(out.contains("x=1"))
        assertTrue(out.contains("y=2"))
        assertTrue(out.startsWith("{"))
    }

    @Test
    fun matrixParse() {
        val node = TexParser.parse("\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}")
        val env = (node as? TexNode.Group)?.nodes?.filterIsInstance<TexNode.Env>()?.firstOrNull()
        assertTrue("expect Env node, got: $node", env != null)
        assertEquals("pmatrix", env!!.name)
        assertEquals(2, env.rows.size)
        assertEquals(2, env.rows[0].size)
    }

    @Test
    fun fracInline() {
        assertEquals("a/b", TexUnicode.toUnicode("\\frac{a}{b}"))
        assertEquals("1/2", TexUnicode.toUnicode("\\frac12"))
        assertEquals("√x", TexUnicode.toUnicode("\\sqrt{x}"))
    }
}