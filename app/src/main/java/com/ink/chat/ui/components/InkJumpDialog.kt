package com.ink.chat.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ink.chat.domain.model.Message
import com.ink.chat.domain.model.MessageRole
import com.ink.chat.ui.theme.Ink
import com.ink.chat.ui.theme.InkType

/**
 * 消息跳转弹窗（§3.4-⑥ / §2.1 B7）：
 * 列出会话全部消息摘要，点击 → 无动画跳到目标；当前视口第一条以「●」标注。
 */
@Composable
fun InkJumpDialog(
    messages: List<Message>,
    currentIndex: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    InkDialog(onDismiss = onDismiss) {
        Text(
            text = "跳转到消息",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            fontSize = InkType.Title,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Ink
        )
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 400.dp),
            flingBehavior = rememberNoFlingBehavior()
        ) {
            itemsIndexed(messages, key = { _, m -> m.id }) { index, m ->
                val who = if (m.role == MessageRole.USER) "你" else "AI"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .inkClickable { onJump(index) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1} $who：${summary(m.content)}",
                        modifier = Modifier.weight(1f),
                        fontSize = InkType.Alt,
                        color = Ink.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (index == currentIndex) {
                        Text(
                            "●",
                            modifier = Modifier.padding(start = 8.dp),
                            fontSize = InkType.Alt,
                            color = Ink.Ink,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            InkDialogTextButton("取消", onDismiss)
        }
    }
}

/** 单行摘要：压平换行，超长省略 */
private fun summary(text: String): String {
    val flat = text.replace('\n', ' ').trim()
    if (flat.isEmpty()) return "…"
    return if (flat.length <= 24) flat else flat.take(24) + "…"
}