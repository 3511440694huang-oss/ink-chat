package com.ink.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ink.chat.ui.theme.Ink
import com.ink.chat.ui.theme.InkType
import com.ink.chat.ui.theme.LocalInkFontFamily
import com.ink.chat.ui.theme.inkLh

/**
 * 无涟漪点击（纸面六则 · 静止之美）。
 * 全局禁用 indication 与按压动画；状态变化瞬时完成。
 */
@Composable
fun Modifier.inkClickable(onClick: () -> Unit): Modifier = inkClickable(enabled = true, onClick = onClick)

/** 带禁用态的无涟漪点击（禁用时不响应，样式由调用方控制） */
@Composable
fun Modifier.inkClickable(enabled: Boolean, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return clickable(
        interactionSource = source,
        indication = null,
        enabled = enabled,
        onClick = onClick
    )
}

/** 顶栏（高 56dp + 1dp 底线）：左 1 个图标位；右侧 文字按钮 + 图标按钮（§3.3-①；M5.5 加文字位） */
@Composable
fun InkTopBar(
    title: String,
    leftIcon: ImageVector? = null,
    onLeft: (() -> Unit)? = null,
    rightTextActions: List<Pair<String, () -> Unit>> = emptyList(),
    rightIcons: List<Pair<ImageVector, () -> Unit>> = emptyList(),
) {
    Column(Modifier.fillMaxWidth().background(Ink.Paper)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leftIcon != null && onLeft != null) InkIconButton(leftIcon, onLeft)
            Text(
                text = title,
                modifier = Modifier.weight(1f).padding(start = if (leftIcon != null) 8.dp else 16.dp),
                fontSize = InkType.Title,
                fontWeight = FontWeight.SemiBold,
                color = Ink.Ink,
                maxLines = 1
            )
            rightTextActions.forEach { (label, action) -> InkTextButton(label, action) }
            rightIcons.forEach { (icon, action) -> InkIconButton(icon, action) }
        }
        Box(Modifier.fillMaxWidth().height(Ink.Hairline).background(Ink.Line))
    }
}

/** 顶栏文字按钮（48dp 触区；纸面直点直达；M5.5 批量管理「管理 / 全选」） */
@Composable
fun InkTextButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(Ink.Touch).inkClickable(onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = InkType.Alt, color = Ink.Ink)
    }
}

/** 图标按钮（48dp 触区、24dp 图标、无涟漪） */
@Composable
fun InkIconButton(icon: ImageVector, onClick: () -> Unit, contentDescription: String? = null) {
    Box(
        Modifier.size(Ink.Touch).inkClickable(onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Ink.Ink,
            modifier = Modifier.size(24.dp)
        )
    }
}

/** 分段开关 Chip（1.5dp 黑框；选中 = 反色） */
@Composable
fun InkChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(Ink.Touch).inkClickable(onClick).padding(vertical = 6.dp)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .border(Ink.StrongLine, Ink.Ink)
                .background(if (selected) Ink.Ink else Ink.Paper)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (selected) Ink.Paper else Ink.Ink,
                fontSize = InkType.Alt
            )
        }
    }
}

/** 主按钮（反色；直角；48dp 高） */
@Composable
fun InkPrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .height(Ink.Touch)
            .widthIn(min = 76.dp)
            .background(if (enabled) Ink.Ink else Ink.Line)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Ink.Paper, fontSize = InkType.Body, fontWeight = FontWeight.SemiBold)
    }
}

/** 内联提示条（替代 Toast/Snackbar；瞬时出现、用户关闭；可选动作按钮） */
@Composable
fun InkNotice(
    text: String,
    onDismiss: () -> Unit,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Ink.PadPage, vertical = Ink.PadTight)
            .border(Ink.StrongLine, Ink.Ink)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isError) "！$text" else text,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            fontSize = InkType.Alt,
            lineHeight = inkLh(24f),
            color = Ink.Ink,
            fontWeight = if (isError) FontWeight.Bold else FontWeight.Normal
        )
        if (actionLabel != null && onAction != null) {
            Box(
                Modifier.height(Ink.Touch).inkClickable(onAction).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(actionLabel, fontSize = InkType.Alt, color = Ink.Ink, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(
            Modifier.height(Ink.Touch).inkClickable(onDismiss).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("知道了", fontSize = InkType.Alt, color = Ink.Ink)
        }
    }
}

/** 输入框（1.5dp 黑框、直角、最小 48dp / 最大 [maxHeight]（默认 132dp），超出内滚） */
@Composable
fun InkInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "输入消息…",
    maxHeight: Dp = 132.dp
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .heightIn(min = 48.dp, max = maxHeight)
            .border(Ink.StrongLine, Ink.Ink)
            .background(Ink.Paper)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        textStyle = TextStyle(
            fontSize = InkType.Body,
            color = Ink.Ink,
            lineHeight = inkLh(28f),
            fontFamily = LocalInkFontFamily.current,
        ),
        cursorBrush = SolidColor(Ink.Ink),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, color = Ink.InkMid, fontSize = InkType.Body)
                }
                innerTextField()
            }
        }
    )
}

/** 48dp 直角方块按钮：filled = 反色黑底；否则线框白底（§3.3-② 发送/停止位） */
@Composable
fun InkSquareButton(
    filled: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(Ink.Touch)
            .then(
                if (filled) {
                    Modifier.background(if (enabled) Ink.Ink else Ink.Line)
                } else {
                    Modifier
                        .border(Ink.StrongLine, if (enabled) Ink.Ink else Ink.Line)
                        .background(Ink.Paper)
                }
            )
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 墨水屏：关闭惯性 fling（松手即停，§3.6-C） */
@Composable
fun rememberNoFlingBehavior(): FlingBehavior = remember {
    object : FlingBehavior {
        override suspend fun ScrollScope.performFling(initialVelocity: Float): Float = 0f
    }
}