package com.ink.chat.ui.markdown

/**
 * TeX → Unicode 精排转换（M5.5）。
 * 用途：「行内公式」在当前 Compose 版本（1.6，无 InlineContent API）下的渲染方案——
 * 转成 Unicode 上/下标 + 数学符号文本；块级公式仍走 [TexRender] 的完整几何排版。
 * 失败安全：无法映射的片段按「^(…) / _(…)」降级，绝不丢失内容。
 */
internal object TexUnicode {

    private val SUP = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵',
        '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'f' to 'ᶠ',
        'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ', 'k' to 'ᵏ', 'l' to 'ˡ',
        'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ', 'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ',
        't' to 'ᵗ', 'u' to 'ᵘ', 'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
    )

    private val SUB = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅',
        '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ',
        'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ', 'p' to 'ₚ', 'r' to 'ᵣ',
        's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ',
    )

    fun toUnicode(tex: String): String = try {
        Conv(tex).run()
    } catch (t: Throwable) {
        tex
    }

    private class Conv(private val s: String) {
        private var i = 0

        fun run(stopAtBrace: Boolean = false): String {
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    stopAtBrace && c == '}' -> return out.toString()
                    c == '\\' -> out.append(cmd())
                    c == '{' -> {
                        i++
                        val inner = run(stopAtBrace = true)
                        if (i < s.length && s[i] == '}') i++
                        out.append(inner)
                    }
                    c == '^' -> { i++; out.append(script(sup = true)) }
                    c == '_' -> { i++; out.append(script(sup = false)) }
                    c == '&' -> i++ // 环境对齐符：行内近似下忽略（M5.6）
                    else -> { out.append(c); i++ }
                }
            }
            return out.toString()
        }

        private fun cmd(): String {
            // s[i] == '\\'
            val j = i + 1
            if (j >= s.length) { i++; return "\\" }
            if (!s[j].isLetter()) {
                val ch = s[j]
                i += 2
                return when (ch) {
                    '{', '}' -> ch.toString()
                    '\\', ',', ';', ':', ' ' -> " "
                    '!' -> ""
                    else -> ch.toString()
                }
            }
            var k = j
            while (k < s.length && s[k].isLetter()) k++
            val name = s.substring(j, k)
            i = k
            return when (name) {
                "frac", "dfrac", "tfrac" -> {
                    val a = group()
                    val b = group()
                    if (simpleAtom(a) && simpleAtom(b)) "$a/$b" else "($a)/($b)"
                }
                "sqrt" -> {
                    var idx: String? = null
                    if (i < s.length && s[i] == '[') {
                        i++
                        val sb = StringBuilder()
                        while (i < s.length && s[i] != ']') { sb.append(s[i]); i++ }
                        if (i < s.length) i++
                        idx = sb.toString()
                    }
                    val b = group()
                    if (idx.isNullOrBlank()) {
                        if (simpleAtom(b)) "√$b" else "√($b)"
                    } else {
                        "$idx√($b)"
                    }
                }
                "text", "mathrm", "operatorname", "mathbf", "mathit", "mathcal", "mathbb", "mathsf", "mathtt" ->
                    group()
                "left", "right" -> delim()
                "sum" -> "∑"
                "prod" -> "∏"
                "int" -> "∫"
                "iint" -> "∬"
                "oint" -> "∮"
                "lim" -> "lim"
                "displaystyle", "textstyle", "limits", "nolimits" -> ""
                "binom" -> {
                    val a = group()
                    val b = group()
                    "C($a, $b)"
                }
                "overline", "bar" -> combine('\u0305', group())
                "hat" -> combine('\u0302', group())
                "vec" -> combine('\u20D7', group())
                "dot" -> combine('\u0307', group())
                "ddot" -> combine('\u0308', group())
                "tilde" -> combine('\u0303', group())
                "begin" -> env()
                "end" -> { group(); "" }
                else -> TexParser.symbolCharOf(name) ?: ("\\" + name)
            }
        }

        /** 读取一个参数：{…} 组或单字符 */
        private fun group(): String {
            if (i < s.length && s[i] == '{') {
                i++
                val inner = run(stopAtBrace = true)
                if (i < s.length && s[i] == '}') i++
                return inner
            }
            if (i < s.length) {
                if (s[i] == '\\') return cmd()
                val c = s[i]
                i++
                return c.toString()
            }
            return ""
        }

        /** 上/下标：优先 Unicode 映射；无法映射时降级 ^(…) / _(…) */
        private fun script(sup: Boolean): String {
            val inner = group()
            val table = if (sup) SUP else SUB
            val sb = StringBuilder()
            for (ch in inner) {
                val m = table[ch]
                if (m == null) return (if (sup) "^($inner)" else "_($inner)")
                sb.append(m)
            }
            return sb.toString()
        }

        /** \left / \right 后的定界符 */
        private fun delim(): String {
            if (i < s.length) {
                if (s[i] == '\\') return cmd()
                val c = s[i]
                i++
                return if (c == '.') "" else c.toString()
            }
            return ""
        }

        /** 组合标记（上划线 / 帽 / 向量等）：逐字符叠加组合字符（U+0300 区） */
        private fun combine(mark: Char, body: String): String =
            body.map { ch -> if (ch.isWhitespace()) ch.toString() else ch.toString() + mark }
                .joinToString("")

        /** 单原子（1 个字母/数字）：可省去括号，如 a/b、√x */
        private fun simpleAtom(s: String): Boolean = s.length == 1 && s[0].isLetterOrDigit()

        /**
         * 环境（cases / aligned / pmatrix…；M5.6）：
         * 逐行收集（\\ 分行、& 对齐符忽略），行内近似为「{ a；b }」/「(a b)」等。
         * v1 不支持嵌套环境（实践场景极少）。
         */
        private fun env(): String {
            val name = group().trim()
            if (name == "array") group() // 列格式参数（{c|c}），忽略
            val rows = mutableListOf<String>()
            val cur = StringBuilder()
            while (i < s.length) {
                val ch = s[i]
                if (ch == '\\' && i + 1 < s.length) {
                    val n = s[i + 1]
                    if (n == '\\') {
                        rows += cur.toString()
                        cur.setLength(0)
                        i += 2
                        continue
                    }
                    if (s.startsWith("\\end", i)) {
                        i += 4
                        group() // 吃掉 \end{环境名}
                        break
                    }
                }
                cur.append(ch); i++
            }
            rows += cur.toString()
            val body = rows.map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(
                    if (name == "cases" || name.startsWith("align") ||
                        name == "gather" || name == "gathered" || name == "split"
                    ) "；" else " "
                ) { Conv(it).run().trim() }
            return when (name) {
                "cases" -> "{ $body }"
                "pmatrix" -> "($body)"
                "bmatrix" -> "[$body]"
                "Bmatrix" -> "{$body}"
                "vmatrix" -> "|$body|"
                "Vmatrix" -> "‖$body‖"
                else -> body
            }
        }
    }
}