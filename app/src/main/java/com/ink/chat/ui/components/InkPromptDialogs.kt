package com.ink.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ink.chat.ui.theme.Ink
import com.ink.chat.ui.theme.InkType

/**
 * 系统提示词编辑对话框（M5.7）。
 * 多行输入（内滚）；「清除」= 保存空串（关闭注入）；「保存」= trim 后写入。
 */
@Composable
fun InkPromptEditDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    InkDialog(onDismiss = onDismiss) {
        Text(
            text = "系统提示词",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
            fontSize = InkType.Title,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Ink
        )
        Text(
            text = "作为 system 消息置于每次请求最前；留空则关闭。",
            modifier = Modifier.padding(horizontal = 16.dp),
            fontSize = InkType.Alt,
            color = Ink.InkMid
        )
        Spacer(Modifier.height(8.dp))
        InkInputField(
            value = text,
            onValueChange = { text = it },
            placeholder = "例如：你是一位严谨的助手，回答简洁…",
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            maxHeight = 220.dp,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            InkDialogTextButton("清除") { onSave("") }
            InkDialogTextButton("取消", onDismiss)
            InkDialogTextButton("保存") { onSave(text.trim()) }
        }
    }
}

/**
 * 提示词模板库对话框（M5.7）。
 * 点击一条 → 应用为系统提示词（调用方处理并关闭）；每行常驻「删除」；
 * 底部输入 +「存为模板」新增；列表超高时内滚（禁惯性）。
 */
@Composable
fun InkPromptsDialog(
    prompts: List<String>,
    onApply: (String) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newText by remember { mutableStateOf("") }

    InkDialog(onDismiss = onDismiss) {
        Text(
            text = "提示词模板",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
            fontSize = InkType.Title,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Ink
        )
        Text(
            text = "点击一条应用为系统提示词。",
            modifier = Modifier.padding(horizontal = 16.dp),
            fontSize = InkType.Alt,
            color = Ink.InkMid
        )
        Spacer(Modifier.height(4.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState(), flingBehavior = rememberNoFlingBehavior())
        ) {
            if (prompts.isEmpty()) {
                Text(
                    text = "暂无模板。在下方输入并保存。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    fontSize = InkType.Alt,
                    color = Ink.InkMid
                )
            } else {
                prompts.forEachIndexed { index, prompt ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Ink.Touch),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .inkClickable { onApply(prompt) }
                                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                        ) {
                            Text(
                                text = previewOf(prompt),
                                fontSize = InkType.Body,
                                color = Ink.Ink,
                                maxLines = 2
                            )
                        }
                        Box(
                            Modifier
                                .height(Ink.Touch)
                                .inkClickable { onDelete(index) }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("删除", fontSize = InkType.Alt, color = Ink.InkMid)
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(Ink.Hairline).background(Ink.Line))

        InkInputField(
            value = newText,
            onValueChange = { newText = it },
            placeholder = "新模板…",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End
        ) {
            InkDialogTextButton("关闭", onDismiss)
            InkDialogTextButton("存为模板") {
                if (newText.isNotBlank()) {
                    onAdd(newText.trim())
                    newText = ""
                }
            }
        }
    }
}

/** 模板预览：单行压缩 + 超长截断（列表阅读友好） */
private fun previewOf(prompt: String): String {
    val t = prompt.trim().replace(Regex("\\s+"), " ")
    return if (t.length <= 48) t else t.take(48) + "…"
}
