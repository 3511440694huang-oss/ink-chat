package com.ink.chat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import java.io.File

/** 主题取值（与 DataStore `theme` 键一致，§4.4） */
const val THEME_PAPER = "paper"
const val THEME_INVERSE = "inverse"

/**
 * 纸面主题（M4 §2.1 D6；M5.7 自定义字体）：
 * - 主题：纸白 / 反色（五级灰对偶，显式切换，不跟随系统深色）；
 * - 字号：0.85 / 0.9 / 1.0 三档——覆盖 LocalDensity.fontScale，全应用 sp 文本统一缩放；
 * - 行距：紧密 / 标准——通过 LocalInkLineFactor 影响 inkLh 与 Typography 行高；
 * - 字体：fontFamily 非空时全局应用（Typography + LocalInkFontFamily 双通道）。
 */
@Composable
fun InkTheme(
    theme: String = THEME_PAPER,
    fontScale: Float = 0.9f,
    lineSpacing: String = "normal",
    fontFamily: FontFamily? = null,
    content: @Composable () -> Unit,
) {
    val palette = if (theme == THEME_INVERSE) InkPalette.Inverse else InkPalette.Paper
    val lineFactor = if (lineSpacing == "tight") 0.85f else 1f

    // 字号缩放：仅放大/缩小 sp→px 换算的 fontScale，dp 布局不变
    val base = LocalDensity.current
    val density = remember(base, fontScale) { Density(base.density, base.fontScale * fontScale) }

    val scheme = remember(palette) { schemeOf(palette) }
    val typography = remember(lineFactor, fontFamily) {
        Typography(
            titleLarge = TextStyle(fontSize = 19.sp, lineHeight = (28 * lineFactor).sp, fontWeight = FontWeight.SemiBold, fontFamily = fontFamily),
            bodyLarge = TextStyle(fontSize = 19.sp, lineHeight = (32 * lineFactor).sp, fontFamily = fontFamily),
            labelLarge = TextStyle(fontSize = 15.sp, lineHeight = (26 * lineFactor).sp, fontFamily = fontFamily),
            labelMedium = TextStyle(fontSize = 13.sp, lineHeight = (22 * lineFactor).sp, fontFamily = fontFamily)
        )
    }

    CompositionLocalProvider(
        LocalDensity provides density,
        LocalInkPalette provides palette,
        LocalInkLineFactor provides lineFactor,
        LocalInkFontFamily provides fontFamily,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            content = content
        )
    }
}

/**
 * 自定义字体文件 → Compose FontFamily（M5.7）。
 * 失败返回 null（调用方回退系统默认字体）；加载本身是惰性的（首次排版时解析字形）。
 */
@OptIn(ExperimentalTextApi::class)
fun inkFontFamilyOf(file: File): FontFamily? = runCatching {
    FontFamily(Font(file))
}.getOrNull()

private fun schemeOf(p: InkPalette) = lightColorScheme(
    primary = p.ink,
    onPrimary = p.paper,
    background = p.paper,
    onBackground = p.ink,
    surface = p.paper,
    onSurface = p.ink,
    surfaceVariant = p.mist,
    onSurfaceVariant = p.deep,
    outline = p.line,
    error = p.ink,   // 墨屏无彩色：错误用「反色 + 加粗 + 框线」表达
    onError = p.paper
)