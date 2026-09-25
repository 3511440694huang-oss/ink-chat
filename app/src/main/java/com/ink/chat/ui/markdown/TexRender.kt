package com.ink.chat.ui.markdown

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/**
 * TeX 子集渲染引擎（M5.5「数学公式支持」；自研，零依赖）。
 * 解析：分数 / 根号 / 上下标 / 大运算符（sum/int/lim…）/ 定界符（left-right）/
 *       环境（cases/aligned/matrix 系）/ 组合标记（bar/hat/vec…）/
 *       希腊字母 / 运算符 / 函数名 / text；未知命令降级为原文。
 * 布局：盒模型（width / ascent / descent），基线对齐；绘制：Canvas + drawText。
 * 失败安全：任何异常 → 返回 null，由调用方降级为纯文本。
 */

// —— 节点树 ——

internal sealed class TexNode {
    /** 单段文本符号（italic = 数学斜体） */
    data class Sym(val text: String, val italic: Boolean = false) : TexNode()
    data class Group(val nodes: List<TexNode>) : TexNode()
    data class Frac(val num: TexNode, val den: TexNode) : TexNode()
    data class Sqrt(val body: TexNode, val index: TexNode? = null) : TexNode()
    data class Scripted(val base: TexNode, val sup: TexNode? = null, val sub: TexNode? = null) : TexNode()
    data class BigOp(val sym: String, val sup: TexNode? = null, val sub: TexNode? = null) : TexNode()
    data class Fence(val open: String, val close: String, val body: TexNode) : TexNode()
    data class Sp(val em: Float) : TexNode()

    /** 环境（cases / aligned / matrix 系；M5.6）：rows = 行 → 单元格 */
    data class Env(val name: String, val rows: List<List<TexNode>>) : TexNode()

    /** 组合标记（上划线 / 帽 / 向量等；M5.6） */
    data class Accent(val mark: String, val body: TexNode) : TexNode()

    /** 方框（\boxed；M5.7）：内容外包一圈细框 */
    data class Boxed(val body: TexNode) : TexNode()

    /** 上下叠标记（\\overset / \\underset / \\stackrel；M5.7）：小字叠在基体上 / 下 */
    data class Stacked(val base: TexNode, val top: TexNode? = null, val bottom: TexNode? = null) : TexNode()
}

// —— 解析器 ——

internal object TexParser {

    private sealed class Tok {
        data class Cmd(val name: String) : Tok()
        data class Ch(val c: Char) : Tok()
        object LBrace : Tok()
        object RBrace : Tok()
        object Caret : Tok()
        object Under : Tok()
    }

    private val SYMBOLS: Map<String, Pair<String, Boolean>> = mapOf(
        // 希腊字母（小写斜体）
        "alpha" to ("α" to true), "beta" to ("β" to true), "gamma" to ("γ" to true),
        "delta" to ("δ" to true), "epsilon" to ("ε" to true), "varepsilon" to ("ε" to true),
        "zeta" to ("ζ" to true), "eta" to ("η" to true), "theta" to ("θ" to true),
        "vartheta" to ("ϑ" to true), "iota" to ("ι" to true), "kappa" to ("κ" to true),
        "lambda" to ("λ" to true), "mu" to ("μ" to true), "nu" to ("ν" to true),
        "xi" to ("ξ" to true), "pi" to ("π" to true), "varpi" to ("ϖ" to true),
        "rho" to ("ρ" to true), "sigma" to ("σ" to true), "varsigma" to ("ς" to true),
        "tau" to ("τ" to true), "upsilon" to ("υ" to true), "phi" to ("φ" to true),
        "varphi" to ("ϕ" to true), "chi" to ("χ" to true), "psi" to ("ψ" to true),
        "omega" to ("ω" to true),
        "Gamma" to ("Γ" to false), "Delta" to ("Δ" to false), "Theta" to ("Θ" to false),
        "Lambda" to ("Λ" to false), "Xi" to ("Ξ" to false), "Pi" to ("Π" to false),
        "Sigma" to ("Σ" to false), "Upsilon" to ("Υ" to false), "Phi" to ("Φ" to false),
        "Psi" to ("Ψ" to false), "Omega" to ("Ω" to false),
        // 运算符
        "times" to ("×" to false), "div" to ("÷" to false), "pm" to ("±" to false),
        "mp" to ("∓" to false), "cdot" to ("·" to false), "ast" to ("∗" to false),
        "star" to ("⋆" to false), "circ" to ("∘" to false), "bullet" to ("•" to false),
        "oplus" to ("⊕" to false), "otimes" to ("⊗" to false), "odot" to ("⊙" to false),
        // 关系
        "le" to ("≤" to false), "leq" to ("≤" to false), "ge" to ("≥" to false),
        "geq" to ("≥" to false), "ne" to ("≠" to false), "neq" to ("≠" to false),
        "approx" to ("≈" to false), "equiv" to ("≡" to false), "sim" to ("∼" to false),
        "simeq" to ("≃" to false), "cong" to ("≅" to false), "propto" to ("∝" to false),
        "ll" to ("≪" to false), "gg" to ("≫" to false), "doteq" to ("≐" to false),
        "asymp" to ("≍" to false), "prec" to ("≺" to false), "succ" to ("≻" to false),
        "preceq" to ("⪯" to false), "succeq" to ("⪰" to false),
        "mid" to ("|" to false), "parallel" to ("∥" to false), "perp" to ("⊥" to false),
        "vdash" to ("⊢" to false), "dashv" to ("⊣" to false),
        // 集合 / 逻辑
        "infty" to ("∞" to false), "partial" to ("∂" to true), "nabla" to ("∇" to false),
        "forall" to ("∀" to false), "exists" to ("∃" to false), "in" to ("∈" to false),
        "notin" to ("∉" to false), "ni" to ("∋" to false), "subset" to ("⊂" to false),
        "subseteq" to ("⊆" to false), "supset" to ("⊃" to false), "supseteq" to ("⊇" to false),
        "cup" to ("∪" to false), "cap" to ("∩" to false), "setminus" to ("∖" to false),
        "sqcup" to ("⊔" to false), "sqcap" to ("⊓" to false),
        "emptyset" to ("∅" to false), "varnothing" to ("∅" to false), "neg" to ("¬" to false),
        "lnot" to ("¬" to false), "top" to ("⊤" to false), "bot" to ("⊥" to false),
        "land" to ("∧" to false), "lor" to ("∨" to false), "wedge" to ("∧" to false),
        "vee" to ("∨" to false), "therefore" to ("∴" to false), "because" to ("∵" to false),
        // 箭头
        "to" to ("→" to false), "rightarrow" to ("→" to false), "leftarrow" to ("←" to false),
        "gets" to ("←" to false),
        "Rightarrow" to ("⇒" to false), "Leftarrow" to ("⇐" to false),
        "implies" to ("⟹" to false), "impliedby" to ("⟸" to false), "iff" to ("⟺" to false),
        "Longrightarrow" to ("⟹" to false), "Longleftarrow" to ("⟸" to false),
        "Longleftrightarrow" to ("⟺" to false),
        "leftrightarrow" to ("↔" to false), "mapsto" to ("↦" to false),
        "uparrow" to ("↑" to false), "downarrow" to ("↓" to false),
        "nearrow" to ("↗" to false), "searrow" to ("↘" to false),
        "swarrow" to ("↙" to false), "nwarrow" to ("↖" to false),
        "longrightarrow" to ("⟶" to false), "longleftarrow" to ("⟵" to false),
        // 大运算符
        "sum" to ("∑" to false), "prod" to ("∏" to false), "int" to ("∫" to false),
        "iint" to ("∬" to false), "oint" to ("∮" to false), "lim" to ("lim" to false),
        "bigcup" to ("⋃" to false), "bigcap" to ("⋂" to false), "coprod" to ("∐" to false),
        "bigcirc" to ("◯" to false), "bigoplus" to ("⨁" to false), "bigotimes" to ("⨂" to false),
        "bigodot" to ("⨀" to false),
        // 杂项
        "cdots" to ("⋯" to false), "ldots" to ("…" to false), "dots" to ("…" to false),
        "vdots" to ("⋮" to false), "ddots" to ("⋱" to false), "angle" to ("∠" to false),
        "degree" to ("°" to false), "prime" to ("′" to false), "dagger" to ("†" to false),
        "ddagger" to ("‡" to false), "diamond" to ("⋄" to false), "ominus" to ("⊖" to false),
        "square" to ("□" to false), "triangle" to ("△" to false), "checkmark" to ("✓" to false),
        "hbar" to ("ℏ" to false), "ell" to ("ℓ" to true), "aleph" to ("ℵ" to false),
        "varkappa" to ("ϰ" to true), "wp" to ("℘" to false),
        "Re" to ("ℜ" to false), "Im" to ("ℑ" to false),
        // 函数名（直立）
        "sin" to ("sin" to false), "cos" to ("cos" to false), "tan" to ("tan" to false),
        "cot" to ("cot" to false), "sec" to ("sec" to false), "csc" to ("csc" to false),
        "arcsin" to ("arcsin" to false), "arccos" to ("arccos" to false), "arctan" to ("arctan" to false),
        "sinh" to ("sinh" to false), "cosh" to ("cosh" to false), "tanh" to ("tanh" to false),
        "log" to ("log" to false), "ln" to ("ln" to false), "lg" to ("lg" to false),
        "exp" to ("exp" to false), "max" to ("max" to false), "min" to ("min" to false),
        "sup" to ("sup" to false), "inf" to ("inf" to false), "det" to ("det" to false),
        "dim" to ("dim" to false), "gcd" to ("gcd" to false), "mod" to ("mod" to false),
        "bmod" to ("mod" to false), "ker" to ("ker" to false), "arg" to ("arg" to false),
        "deg" to ("deg" to false), "sgn" to ("sgn" to false), "Pr" to ("Pr" to false),
        // 定界符
        "{" to ("{" to false), "}" to ("}" to false), "lbrace" to ("{" to false),
        "rbrace" to ("}" to false), "langle" to ("⟨" to false), "rangle" to ("⟩" to false),
        "lvert" to ("|" to false), "rvert" to ("|" to false), "vert" to ("|" to false),
        "Vert" to ("‖" to false), "lVert" to ("‖" to false), "rVert" to ("‖" to false),
        "lfloor" to ("⌊" to false), "rfloor" to ("⌋" to false),
        "lceil" to ("⌈" to false), "rceil" to ("⌉" to false),
    )

    private val BIG_OPS = setOf("sum", "prod", "int", "iint", "oint", "lim")

    /** 符号表查询（供 TexUnicode 复用） */
    internal fun symbolCharOf(name: String): String? = SYMBOLS[name]?.first

    fun parse(src: String): TexNode = Parser(tokenize(src)).parseTop()

    private fun tokenize(src: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\\' -> {
                    if (i + 1 >= src.length) {
                        out += Tok.Ch('\\'); i++
                    } else {
                        val nx = src[i + 1]
                        if (nx.isLetter()) {
                            var j = i + 1
                            while (j < src.length && src[j].isLetter()) j++
                            out += Tok.Cmd(src.substring(i + 1, j))
                            i = j
                        } else {
                            out += Tok.Cmd(nx.toString())
                            i += 2
                        }
                    }
                }
                c == '{' -> { out += Tok.LBrace; i++ }
                c == '}' -> { out += Tok.RBrace; i++ }
                c == '^' -> { out += Tok.Caret; i++ }
                c == '_' -> { out += Tok.Under; i++ }
                else -> { out += Tok.Ch(c); i++ }
            }
        }
        return out
    }

    private class Parser(private val toks: List<Tok>) {
        private var pos = 0

        private fun peek(): Tok? = toks.getOrNull(pos)
        private fun next(): Tok? = toks.getOrNull(pos).also { if (it != null) pos++ }

        fun parseTop(): TexNode {
            val node = parseSeq(stopAtRight = false)
            return node
        }

        private fun parseSeq(stopAtRight: Boolean): TexNode {
            val list = mutableListOf<TexNode>()
            while (true) {
                val t = peek() ?: break
                if (t is Tok.RBrace) break
                if (stopAtRight && t is Tok.Cmd && t.name == "right") break
                val atom = parseAtom() ?: continue
                list += atom
            }
            return TexNode.Group(list)
        }

        /** 原子 + 附着上下标 */
        private fun parseAtom(): TexNode? {
            val t = next() ?: return null
            val base = when (t) {
                is Tok.Ch -> TexNode.Sym(t.c.toString(), italicChar(t.c))
                is Tok.Cmd -> cmdToNode(t.name)
                is Tok.LBrace -> {
                    val inner = parseSeq(stopAtRight = false)
                    if (peek() is Tok.RBrace) next()
                    inner
                }
                is Tok.Caret, is Tok.Under, is Tok.RBrace -> null
            } ?: return null
            return attachScripts(base)
        }

        private fun attachScripts(base: TexNode): TexNode {
            var sup: TexNode? = null
            var sub: TexNode? = null
            while (true) {
                when (peek()) {
                    is Tok.Caret -> { next(); sup = parseArg() }
                    is Tok.Under -> { next(); sub = parseArg() }
                    else -> return if (sup == null && sub == null) base else TexNode.Scripted(base, sup, sub)
                }
            }
        }

        /** 上下标参数：单原子或 {…} 组 */
        private fun parseArg(): TexNode {
            if (peek() is Tok.LBrace) {
                next()
                val inner = parseSeq(stopAtRight = false)
                if (peek() is Tok.RBrace) next()
                return inner
            }
            return parseAtom() ?: TexNode.Group(emptyList())
        }

        /** 读取 {…} 组为纯文本（环境名 / 列格式等）；未命中返回 null 且不消费 */
        private fun readBraceName(): String? {
            if (peek() !is Tok.LBrace) return null
            next()
            val sb = StringBuilder()
            while (true) {
                val t = peek() ?: break
                if (t is Tok.RBrace) { next(); break }
                next()
                when (t) {
                    is Tok.Ch -> sb.append(t.c)
                    is Tok.Cmd -> sb.append(t.name)
                    else -> Unit
                }
            }
            return sb.toString().trim()
        }

        private fun cmdToNode(name: String): TexNode? {
            // 大运算符（自带上下限，display 风格：限叠放在符号上下）
            if (name in BIG_OPS) {
                val sym = SYMBOLS[name]?.first ?: name
                var sup: TexNode? = null
                var sub: TexNode? = null
                while (true) {
                    when (val pk = peek()) {
                        is Tok.Caret -> { next(); sup = parseArg() }
                        is Tok.Under -> { next(); sub = parseArg() }
                        // \limits / \nolimits（M5.7）：吸收——保证随后的 ^/_ 仍归属本运算符
                        is Tok.Cmd -> {
                            if (pk.name == "limits" || pk.name == "nolimits") {
                                next()
                                continue
                            }
                            return TexNode.BigOp(sym, sup, sub)
                        }
                        else -> return TexNode.BigOp(sym, sup, sub)
                    }
                }
            }
            when (name) {
                "frac", "dfrac", "tfrac", "cfrac" -> {
                    val a = parseArg()
                    val b = parseArg()
                    return TexNode.Frac(a, b)
                }
                "sqrt" -> {
                    var index: TexNode? = null
                    if (peek() is Tok.Ch && (peek() as Tok.Ch).c == '[') {
                        next()
                        val buf = StringBuilder()
                        while (true) {
                            val tk = peek() ?: break
                            if (tk is Tok.Ch && tk.c == ']') { next(); break }
                            next()
                            when (tk) {
                                is Tok.Ch -> buf.append(tk.c)
                                is Tok.Cmd -> buf.append(SYMBOLS[tk.name]?.first ?: tk.name)
                                else -> Unit
                            }
                        }
                        if (buf.isNotEmpty()) index = TexNode.Sym(buf.toString())
                    }
                    return TexNode.Sqrt(parseArg(), index)
                }
                "text", "mathrm", "operatorname" -> {
                    return TexNode.Sym(toPlainText(parseArg()), italic = false)
                }
                "mathbf", "mathit", "mathcal", "mathbb", "mathsf", "mathtt" -> {
                    // 样式透传（v1：内容原样渲染）
                    return parseArg()
                }
                // 字体声明 / 样式声明（M5.7）：透传内容（墨屏无彩色与字体族，降级不丢内容）
                "boldsymbol", "bm", "mathscr", "mathfrak", "mathnormal",
                "mathop", "mathrel", "mathbin", "mathord", "mathpunct",
                "mathopen", "mathclose" -> return parseArg()
                // 声明式旧字体命令（\bf x 等）：忽略声明本身，后续内容继续渲染
                "bf", "rm", "it", "sf", "tt", "cal", "frak" -> return null
                // 颜色命令（M5.7）：墨屏无彩色——丢弃颜色参数、保留内容
                "color" -> {
                    parseArg()
                    return null
                }
                "textcolor" -> {
                    parseArg()
                    return parseArg()
                }
                // 空白 / 尺寸 / 线命令（M5.7）：忽略（不残留 \"\\cmd" 文本）
                "hspace", "vspace", "kern", "mkern", "phantom", "hphantom", "vphantom" -> {
                    parseArg()
                    return null
                }
                "hline" -> return null
                "cline" -> {
                    readBraceName()
                    return null
                }
                // 定界符尺寸修饰（\big( 等）：忽略修饰，括号本体照常渲染
                "big", "Big", "bigg", "Bigg", "bigm",
                "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr" -> return null
                // 方框 / 上下叠标记 / 花括号标注（M5.7）
                "boxed" -> return TexNode.Boxed(parseArg())
                "overset" -> {
                    val top = parseArg()
                    return TexNode.Stacked(parseArg(), top = top)
                }
                "underset" -> {
                    val bottom = parseArg()
                    return TexNode.Stacked(parseArg(), bottom = bottom)
                }
                "stackrel" -> {
                    val top = parseArg()
                    return TexNode.Stacked(parseArg(), top = top)
                }
                "underbrace", "overbrace" -> return parseArg()
                "begin" -> {
                    // 环境（M5.6）：cases / aligned / matrix 系；& 分列、\\ 分行
                    val env = readBraceName() ?: return TexNode.Group(emptyList())
                    if (env == "array") readBraceName() // 列格式参数，忽略
                    val rows = mutableListOf<List<TexNode>>()
                    var cells = mutableListOf<TexNode>()
                    while (true) {
                        val t = peek() ?: break
                        if (t is Tok.Cmd && t.name == "end") {
                            next()
                            readBraceName() // 吃掉 {环境名}
                            break
                        }
                        if (t is Tok.Cmd && t.name == "\\") {
                            next()
                            rows += cells
                            cells = mutableListOf()
                            continue
                        }
                        val atom = parseAtom() ?: continue
                        if (atom is TexNode.Sym && atom.text == "&") continue // 对齐符（列宽由布局统一对齐）
                        cells += atom
                    }
                    rows += cells
                    return TexNode.Env(env, rows.filter { it.isNotEmpty() })
                }
                "end" -> {
                    readBraceName()
                    return TexNode.Group(emptyList())
                }
                "binom" -> return TexNode.Fence("(", ")", TexNode.Frac(parseArg(), parseArg()))
                "overline", "bar" -> return TexNode.Accent("‾", parseArg())
                "hat", "widehat" -> return TexNode.Accent("^", parseArg())
                "vec", "overrightarrow" -> return TexNode.Accent("→", parseArg())
                "overleftarrow" -> return TexNode.Accent("←", parseArg())
                "dot" -> return TexNode.Accent("·", parseArg())
                "ddot" -> return TexNode.Accent("··", parseArg())
                "tilde", "widetilde" -> return TexNode.Accent("~", parseArg())
                "acute" -> return TexNode.Accent("´", parseArg())
                "grave" -> return TexNode.Accent("`", parseArg())
                "breve" -> return TexNode.Accent("˘", parseArg())
                "check" -> return TexNode.Accent("ˇ", parseArg())
                "mathring" -> return TexNode.Accent("˚", parseArg())
                "left" -> {
                    val open = delimOf(next())
                    val body = parseSeq(stopAtRight = true)
                    var close = ")"
                    if (peek() is Tok.Cmd && (peek() as Tok.Cmd).name == "right") {
                        next()
                        close = delimOf(next())
                    }
                    return TexNode.Fence(open, close, body)
                }
                "right" -> return null // 由 parseSeq 截停，保险
                "displaystyle", "textstyle", "limits", "nolimits" -> return null
                ",", ";", ":", " ", "quad", "qquad", "!" -> {
                    val em = when (name) {
                        "," -> 0.17f
                        ";" -> 0.28f
                        ":" -> 0.22f
                        " " -> 0.33f
                        "quad" -> 1.0f
                        "qquad" -> 2.0f
                        else -> 0f // \! 负空格 v1 忽略
                    }
                    return TexNode.Sp(em)
                }
                "\\" -> return TexNode.Sp(0.2f) // 换行命令：v1 视作空隙
                "%", "$", "#", "_" -> return TexNode.Sym(name, italic = false)
                "~" -> return TexNode.Sp(0.33f)
                "&" -> return null // 环境对齐符误现：忽略
                else -> {
                    val hit = SYMBOLS[name]
                    if (hit != null) return TexNode.Sym(hit.first, hit.second)
                    // 未知命令：原样显示（降级不崩溃）
                    return TexNode.Sym("\\" + name, italic = false)
                }
            }
        }

        private fun delimOf(t: Tok?): String = when (t) {
            is Tok.Ch -> t.c.toString()
            is Tok.Cmd -> SYMBOLS[t.name]?.first ?: t.name
            else -> "."
        }
    }

    private fun italicChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'α'..'ω' || c == 'ϑ' || c == 'ϕ' || c == 'ϖ' || c == 'ς'

    private fun toPlainText(n: TexNode): String = when (n) {
        is TexNode.Sym -> n.text
        is TexNode.Group -> n.nodes.joinToString("") { toPlainText(it) }
        is TexNode.Sp -> if (n.em > 0f) " " else ""
        is TexNode.Scripted -> toPlainText(n.base) +
            (n.sup?.let { "^" + toPlainText(it) } ?: "") +
            (n.sub?.let { "_" + toPlainText(it) } ?: "")
        is TexNode.Env -> n.rows.joinToString(" ") { row -> row.joinToString(" ") { toPlainText(it) } }
        is TexNode.Accent -> toPlainText(n.body)
        is TexNode.Boxed -> toPlainText(n.body)
        is TexNode.Stacked -> toPlainText(n.base)
        else -> ""
    }
}

// —— 盒模型与布局 ——

/** 布局盒：宽 / 基线上高 / 基线下高；draw(x, baseline) 绘制 */
internal class TexBox(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val draw: (DrawScope, Float, Float) -> Unit,
) {
    val height: Float get() = ascent + descent
    fun drawAt(scope: DrawScope, x: Float, baseline: Float) = draw(scope, x, baseline)
}

internal class TexContext(
    val measurer: TextMeasurer,
    val baseSp: Float,
    val color: Color,
    val density: Density,
) {
    fun px(scale: Float): Float = with(density) { (baseSp * scale).sp.toPx() }
}

internal object TexLayout {

    fun measure(node: TexNode, ctx: TexContext, scale: Float): TexBox = when (node) {
        is TexNode.Sym -> measureSym(node, ctx, scale)
        is TexNode.Group -> measureGroup(node, ctx, scale)
        is TexNode.Frac -> measureFrac(node, ctx, scale)
        is TexNode.Sqrt -> measureSqrt(node, ctx, scale)
        is TexNode.Scripted -> measureScripted(node, ctx, scale)
        is TexNode.BigOp -> measureBigOp(node, ctx, scale)
        is TexNode.Fence -> measureFence(node, ctx, scale)
        is TexNode.Env -> measureEnv(node, ctx, scale)
        is TexNode.Accent -> measureAccent(node, ctx, scale)
        is TexNode.Boxed -> measureBoxed(node, ctx, scale)
        is TexNode.Stacked -> measureStacked(node, ctx, scale)
        is TexNode.Sp -> TexBox(node.em * ctx.px(scale), 0f, 0f) { _, _, _ -> }
    }

    private fun layout(text: String, ctx: TexContext, scale: Float, italic: Boolean): TextLayoutResult {
        val style = TextStyle(
            fontSize = (ctx.baseSp * scale).sp,
            color = ctx.color,
            fontFamily = FontFamily.Serif,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        )
        return ctx.measurer.measure(AnnotatedString(text), style)
    }

    private fun measureSym(n: TexNode.Sym, ctx: TexContext, scale: Float): TexBox {
        val l = layout(n.text, ctx, scale, n.italic)
        val w = l.size.width.toFloat()
        val a = l.firstBaseline
        val h = l.size.height.toFloat()
        return TexBox(w, a, (h - a).coerceAtLeast(0f)) { scope, x, baseline ->
            scope.drawText(l, topLeft = Offset(x, baseline - a))
        }
    }

    private fun measureGroup(n: TexNode.Group, ctx: TexContext, scale: Float): TexBox {
        val boxes = n.nodes.map { measure(it, ctx, scale) }
        var width = 0f
        var ascent = 0f
        var descent = 0f
        boxes.forEach {
            width += it.width
            if (it.ascent > ascent) ascent = it.ascent
            if (it.descent > descent) descent = it.descent
        }
        return TexBox(width, ascent, descent) { scope, x, baseline ->
            var cx = x
            boxes.forEach { b ->
                b.drawAt(scope, cx, baseline)
                cx += b.width
            }
        }
    }

    private fun measureFrac(n: TexNode.Frac, ctx: TexContext, scale: Float): TexBox {
        val num = measure(n.num, ctx, scale)
        val den = measure(n.den, ctx, scale)
        val basePx = ctx.px(scale)
        val gap = basePx * 0.18f
        val width = maxOf(num.width, den.width) + basePx * 0.28f
        val ascent = num.height + gap
        val descent = den.height + gap
        val barW = maxOf(1f, basePx * 0.05f)
        return TexBox(width, ascent, descent) { scope, x, baseline ->
            val numX = x + (width - num.width) / 2f
            val denX = x + (width - den.width) / 2f
            num.drawAt(scope, numX, baseline - gap)
            den.drawAt(scope, denX, baseline + gap + den.ascent)
            scope.drawLine(
                ctx.color,
                Offset(x + basePx * 0.05f, baseline),
                Offset(x + width - basePx * 0.05f, baseline),
                barW,
            )
        }
    }

    private fun measureSqrt(n: TexNode.Sqrt, ctx: TexContext, scale: Float): TexBox {
        val body = measure(n.body, ctx, scale)
        val basePx = ctx.px(scale)
        val lineW = maxOf(1f, basePx * 0.05f)
        val index = n.index?.let { measure(it, ctx, scale * 0.55f) }
        val indexW = (index?.width ?: 0f) + if (index != null) basePx * 0.08f else 0f

        val radW = basePx * 0.5f
        val margin = basePx * 0.12f
        val totalH = body.height + margin
        val topY = body.ascent + margin // 相对基线向上
        val width = indexW + radW + body.width + basePx * 0.06f

        val ascent = topY
        val descent = body.descent
        return TexBox(width, ascent, descent) { scope, x, baseline ->
            // √ 路径（几何折线）：从左上勾到右下
            val rTop = baseline - topY
            val rBot = baseline + body.descent * 0.15f
            val rh = rBot - rTop
            val rx = x + indexW
            val path = Path().apply {
                moveTo(rx + radW * 0.05f, rTop + rh * 0.62f)
                lineTo(rx + radW * 0.33f, rTop + rh * 0.78f)
                lineTo(rx + radW * 0.80f, rTop + lineW * 0.5f)
            }
            scope.drawPath(path, ctx.color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = lineW))
            // 顶部横线（覆盖被开方部分）
            scope.drawLine(
                ctx.color,
                Offset(rx + radW * 0.74f, rTop + lineW * 0.5f),
                Offset(x + width, rTop + lineW * 0.5f),
                lineW,
            )
            // 开方次数（左上角小字）
            index?.drawAt(scope, x, rTop + index.ascent * 0.9f)
            // 被开方内容
            body.drawAt(scope, x + indexW + radW, baseline)
        }
    }

    private fun measureScripted(n: TexNode.Scripted, ctx: TexContext, scale: Float): TexBox {
        val base = measure(n.base, ctx, scale)
        val subScale = scale * 0.68f
        val sup = n.sup?.let { measure(it, ctx, subScale) }
        val sub = n.sub?.let { measure(it, ctx, subScale) }
        val basePx = ctx.px(scale)
        val supShift = base.ascent * 0.42f
        val subShift = maxOf(base.descent * 0.5f, basePx * 0.14f)
        val width = base.width + maxOf(sup?.width ?: 0f, sub?.width ?: 0f)
        val ascent = maxOf(base.ascent, supShift + (sup?.ascent ?: 0f))
        val descent = maxOf(base.descent, subShift + (sub?.descent ?: 0f))
        return TexBox(width, ascent, descent) { scope, x, baseline ->
            base.drawAt(scope, x, baseline)
            val sx = x + base.width
            sup?.drawAt(scope, sx, baseline - supShift)
            sub?.drawAt(scope, sx, baseline + subShift)
        }
    }

    private fun measureBigOp(n: TexNode.BigOp, ctx: TexContext, scale: Float): TexBox {
        val op = measure(TexNode.Sym(n.sym, false), ctx, scale * 1.55f)
        val subScale = scale * 0.7f
        val sup = n.sup?.let { measure(it, ctx, subScale) }
        val sub = n.sub?.let { measure(it, ctx, subScale) }
        val width = maxOf(op.width, sup?.width ?: 0f, sub?.width ?: 0f)
        val ascent = (sup?.height ?: 0f) + op.ascent
        val descent = op.descent + (sub?.height ?: 0f)
        return TexBox(width, ascent, descent) { scope, x, baseline ->
            val centerX = { w: Float -> x + (width - w) / 2f }
            sup?.drawAt(scope, centerX(sup.width), baseline - ascent + sup.ascent)
            op.drawAt(scope, centerX(op.width), baseline)
            sub?.drawAt(scope, centerX(sub.width), baseline + op.descent + sub.ascent)
        }
    }

    private fun measureFence(n: TexNode.Fence, ctx: TexContext, scale: Float): TexBox {
        val body = measure(n.body, ctx, scale)
        val basePx = ctx.px(scale)
        val need = body.height + basePx * 0.1f

        // "\left." / "\right."（M5.7）："." 表示无定界符——不测量、不绘制
        val openVisible = n.open != "." && n.open.isNotEmpty()
        val closeVisible = n.close != "." && n.close.isNotEmpty()

        val openAt1 = if (openVisible) {
            layout(n.open, ctx, scale, false).size.height.toFloat().coerceAtLeast(1f)
        } else 1f
        val escale = scale * (need / openAt1).coerceIn(1f, 2.4f)
        val open = if (openVisible) layout(n.open, ctx, escale, false) else null
        val close = if (closeVisible) layout(n.close, ctx, escale, false) else null
        val ow = open?.size?.width?.toFloat() ?: 0f
        val cw = close?.size?.width?.toFloat() ?: 0f
        val oa = open?.firstBaseline ?: 0f
        val oh = open?.size?.height?.toFloat() ?: 0f
        val ca = close?.firstBaseline ?: 0f
        val ch = close?.size?.height?.toFloat() ?: 0f

        // 括号与内容垂直中心对齐
        val bodyCenter = (body.ascent - body.descent) / 2f
        val oShift = bodyCenter - (oa - oh / 2f)
        val cShift = bodyCenter - (ca - ch / 2f)

        val ascent = maxOf(
            body.ascent,
            if (open != null) oa - oShift else 0f,
            if (close != null) ca - cShift else 0f,
        )
        val descent = maxOf(
            body.descent,
            if (open != null) (oh - oa) + oShift else 0f,
            if (close != null) (ch - ca) + cShift else 0f,
        )
        val width = ow + body.width + cw
        return TexBox(width, ascent, descent) { scope, x, baseline ->
            open?.let { scope.drawText(it, topLeft = Offset(x, baseline + oShift - oa)) }
            body.drawAt(scope, x + ow, baseline)
            close?.let { scope.drawText(it, topLeft = Offset(x + ow + body.width, baseline + cShift - ca)) }
        }
    }

    /** 环境布局（M5.6）：列宽统一、行堆叠；cases 补左大括号、matrix 系补成对围栏 */
    private fun measureEnv(n: TexNode.Env, ctx: TexContext, scale: Float): TexBox {
        val rows = n.rows.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return TexBox(0f, 0f, 0f) { _, _, _ -> }
        val basePx = ctx.px(scale)
        val cells = rows.map { r -> r.map { m -> measure(m, ctx, scale) } }
        val cols = cells.maxOf { it.size }
        val colGap = basePx * 0.7f
        val colW = FloatArray(cols) { c -> cells.maxOf { r -> r.getOrNull(c)?.width ?: 0f } }
        val innerW = if (cols == 0) 0f else colW.sum() + colGap * (cols - 1)
        val rowGap = basePx * 0.5f
        val heights = cells.map { r -> r.maxOfOrNull { it.height } ?: 0f }
        val totalH = heights.sum() + rowGap * (heights.size - 1)

        // 围栏（cases 仅左大括号；matrix 系成对；aligned 等无）
        val fence: Pair<String, String>? = when (n.name) {
            "cases" -> "{" to ""
            "pmatrix" -> "(" to ")"
            "bmatrix" -> "[" to "]"
            "Bmatrix" -> "{" to "}"
            "vmatrix" -> "|" to "|"
            "Vmatrix" -> "‖" to "‖"
            else -> null
        }
        var open: TextLayoutResult? = null
        var close: TextLayoutResult? = null
        if (fence != null && totalH > 0f) {
            val natural = layout(fence.first, ctx, scale, false).size.height.toFloat().coerceAtLeast(1f)
            val escale = scale * ((totalH * 0.96f) / natural).coerceIn(0.7f, 2.8f)
            open = layout(fence.first, ctx, escale, false)
            if (fence.second.isNotEmpty()) close = layout(fence.second, ctx, escale, false)
        }
        val ow = open?.size?.width?.toFloat() ?: 0f
        val cw = close?.size?.width?.toFloat() ?: 0f
        val width = ow + innerW + cw
        val ascent = totalH * 0.5f + basePx * 0.1f
        val descent = totalH - ascent
        val tops = FloatArray(heights.size)
        var acc = 0f
        for (idx in heights.indices) {
            tops[idx] = acc
            acc += heights[idx] + rowGap
        }
        return TexBox(width, ascent, descent) { scope, x, baseline ->
            val top = baseline - ascent
            var cx = x
            open?.let {
                scope.drawText(it, topLeft = Offset(cx, top + (totalH - it.size.height) / 2f))
                cx += ow
            }
            cells.forEachIndexed { ri, row ->
                var rcx = cx
                row.forEachIndexed { ci, b ->
                    val w = colW.getOrElse(ci) { 0f }
                    b.drawAt(
                        scope,
                        rcx + (w - b.width) / 2f,
                        top + tops[ri] + (heights[ri] - b.height) / 2f + b.ascent,
                    )
                    rcx += w + colGap
                }
            }
            close?.let {
                scope.drawText(it, topLeft = Offset(x + ow + innerW, top + (totalH - it.size.height) / 2f))
            }
        }
    }

    /** 组合标记布局（M5.6）：标记居中置于被标记体上方 */
    private fun measureAccent(n: TexNode.Accent, ctx: TexContext, scale: Float): TexBox {
        val body = measure(n.body, ctx, scale)
        val mark = measure(TexNode.Sym(n.mark, false), ctx, scale * 0.62f)
        val gap = ctx.px(scale) * 0.12f
        val width = maxOf(body.width, mark.width)
        val ascent = body.ascent + gap + mark.height
        return TexBox(width, ascent, body.descent) { scope, x, baseline ->
            body.drawAt(scope, x + (width - body.width) / 2f, baseline)
            mark.drawAt(
                scope,
                x + (width - mark.width) / 2f,
                baseline - body.ascent - gap - mark.descent,
            )
        }
    }

    /** 方框布局（M5.7 \boxed）：内容外包一圈细框线 */
    private fun measureBoxed(n: TexNode.Boxed, ctx: TexContext, scale: Float): TexBox {
        val body = measure(n.body, ctx, scale)
        val pad = ctx.px(scale) * 0.28f
        val lineW = maxOf(1f, ctx.px(scale) * 0.05f)
        val width = body.width + pad * 2f
        val height = body.height + pad * 2f
        return TexBox(width, body.ascent + pad, body.descent + pad) { scope, x, baseline ->
            body.drawAt(scope, x + pad, baseline)
            scope.drawRect(
                ctx.color,
                topLeft = Offset(x + lineW / 2f, baseline - body.ascent - pad + lineW / 2f),
                size = androidx.compose.ui.geometry.Size(
                    (width - lineW).coerceAtLeast(0f),
                    (height - lineW).coerceAtLeast(0f),
                ),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = lineW),
            )
        }
    }

    /** 上下叠标记布局（M5.7 \\overset / \\underset / \\stackrel）：小字叠放于基体上 / 下 */
    private fun measureStacked(n: TexNode.Stacked, ctx: TexContext, scale: Float): TexBox {
        val base = measure(n.base, ctx, scale)
        val top = n.top?.let { measure(it, ctx, scale * 0.7f) }
        val bottom = n.bottom?.let { measure(it, ctx, scale * 0.7f) }
        val gap = ctx.px(scale) * 0.12f
        val width = maxOf(base.width, top?.width ?: 0f, bottom?.width ?: 0f)
        val ascent = base.ascent + (top?.let { it.height + gap } ?: 0f)
        val descent = base.descent + (bottom?.let { it.height + gap } ?: 0f)
        return TexBox(width, ascent, descent) { scope, x, baseline ->
            base.drawAt(scope, x + (width - base.width) / 2f, baseline)
            top?.drawAt(
                scope,
                x + (width - top.width) / 2f,
                baseline - base.ascent - gap - top.descent,
            )
            bottom?.drawAt(
                scope,
                x + (width - bottom.width) / 2f,
                baseline + base.descent + gap + bottom.ascent,
            )
        }
    }
}

/**
 * 对外出口：测量公式为 [TexBox]；解析/布局失败返回 null（调用方降级）。
 */
internal object TexRender {

    fun measure(
        tex: String,
        baseSp: Float,
        measurer: TextMeasurer,
        color: Color,
        density: Density,
    ): TexBox? = try {
        val node = TexParser.parse(tex)
        TexLayout.measure(node, TexContext(measurer, baseSp, color, density), 1f)
    } catch (t: Throwable) {
        null
    }
}

/** 把公式画到画布（基线对齐盒） —— 供行内 / 块级共用 */
internal fun DrawScope.drawTexBox(box: TexBox) {
    box.drawAt(this, 0f, box.ascent)
}