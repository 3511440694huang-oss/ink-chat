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
 * 模板短语对话框（M5 §2.1 A13）。
 * 点击一条 → 插入输入框（调用方处理并关闭）；每行常驻「删除」文字按钮（F4 宽容触控）；
 * 底部输入 +「保存」新增；列表超高时内滚（禁惯性）。
 */
@Composable
fun InkPhrasesDialog(
    phrases: List<String>,
    onInsert: (String) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newText by remember { mutableStateOf("") }

    InkDialog(onDismiss = onDismiss) {
        Text(
            text = "短语",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
            fontSize = InkType.Title,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Ink
        )
        Text(
            text = "点击一条插入输入框。",
            modifier = Modifier.padding(horizontal = 16.dp),
            fontSize = InkType.Alt,
            color = Ink.InkMid
        )
        Spacer(Modifier.height(4.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState(), flingBehavior = rememberNoFlingBehavior())
        ) {
            if (phrases.isEmpty()) {
                Text(
                    text = "暂无短语。在下方输入并保存。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    fontSize = InkType.Alt,
                    color = Ink.InkMid
                )
            } else {
                phrases.forEachIndexed { index, phrase ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Ink.Touch),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .inkClickable { onInsert(phrase) }
                                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                        ) {
                            Text(phrase, fontSize = InkType.Body, color = Ink.Ink)
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
            placeholder = "新短语…",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End
        ) {
            InkDialogTextButton("关闭", onDismiss)
            InkDialogTextButton("保存") {
                if (newText.isNotBlank()) {
                    onAdd(newText.trim())
                    newText = ""
                }
            }
        }
    }
}