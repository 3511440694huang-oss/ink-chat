package com.ink.chat.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「纸面语言」设计令牌（软件框架 §3.2）。
 * 墨水屏原则：5 级灰封顶、全局直角、1dp 线条、48dp 触区。
 *
 * M4（§2.1 D6）：
 * - 颜色令牌改为组合局部（CompositionLocal）读取——反色主题切换时，
 *   全应用 `Ink.Paper` 等引用零改动获得对偶色；
 * - 字号缩放由 LocalDensity.fontScale 承担（InkTheme 覆盖）；
 * - 行距由 [inkLh] + [LocalInkLineFactor] 承担。
 */
data class InkPalette(
    val paper: Color, // 页面底色
    val mist: Color,  // 极淡填充（大面积慎用）
    val line: Color,  // 1dp 分割线、次级边框
    val mid: Color,   // 辅助文字
    val deep: Color,  // 次级正文
    val ink: Color,   // 主文字
) {
    companion object {
        /** 纸白（默认）：5 级灰原色 */
        val Paper = InkPalette(
            paper = Color(0xFFFFFFFF),
            mist = Color(0xFFF2F2F2),
            line = Color(0xFFB8B8B8),
            mid = Color(0xFF6E6E6E),
            deep = Color(0xFF2B2B2B),
            ink = Color(0xFF000000),
        )

        /** 反色：五级灰数学对偶（255 - c），深浅对比关系完全对称 */
        val Inverse = InkPalette(
            paper = Color(0xFF000000),
            mist = Color(0xFF0D0D0D),
            line = Color(0xFF474747),
            mid = Color(0xFF919191),
            deep = Color(0xFFD4D4D4),
            ink = Color(0xFFFFFFFF),
        )
    }
}

/** 当前调色板（InkTheme 提供；默认纸白，未包裹时也安全） */
val LocalInkPalette = staticCompositionLocalOf { InkPalette.Paper }

/** 行距因子：标准 = 1.0、紧密 = 0.85（M4 §2.1 D6） */
val LocalInkLineFactor = staticCompositionLocalOf { 1.0f }

/** 行高 = 基础值 × 行距因子（字号缩放由 LocalDensity.fontScale 统一承担） */
@Composable
@ReadOnlyComposable
fun inkLh(baseSp: Float): TextUnit = (baseSp * LocalInkLineFactor.current).sp

/** 设计令牌：颜色随主题（组合局部读取），间距 / 线宽恒定 */
object Ink {

    // —— 色彩（5 级灰；M4 起随「纸白 / 反色」对偶切换）——
    val Paper: Color @Composable @ReadOnlyComposable get() = LocalInkPalette.current.paper
    val Mist: Color @Composable @ReadOnlyComposable get() = LocalInkPalette.current.mist
    val Line: Color @Composable @ReadOnlyComposable get() = LocalInkPalette.current.line
    val InkMid: Color @Composable @ReadOnlyComposable get() = LocalInkPalette.current.mid
    val InkDeep: Color @Composable @ReadOnlyComposable get() = LocalInkPalette.current.deep
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalInkPalette.current.ink

    // —— 间距（8dp 网格）——
    val PadPage = 20.dp
    val PadBlock = 16.dp
    val PadLine = 12.dp
    val PadTight = 8.dp
    val Touch = 48.dp

    // —— 线与角 ——
    val Hairline = 1.dp
    val StrongLine = 1.5.dp
    val FocusLine = 2.dp
}

/** 字号令牌（基础值；运行时由 LocalDensity.fontScale 全局缩放——0.85 / 0.9 / 1.0） */
object InkType {
    val Display = 22.sp
    val Title = 19.sp
    val Body = 19.sp
    val Alt = 15.sp
    val Caption = 13.sp
    val Mono = 15.sp
}