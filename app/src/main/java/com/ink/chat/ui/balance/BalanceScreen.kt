package com.ink.chat.ui.balance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ink.chat.domain.model.BalanceSnapshot
import com.ink.chat.ui.components.InkNotice
import com.ink.chat.ui.components.InkPrimaryButton
import com.ink.chat.ui.components.InkTopBar
import com.ink.chat.ui.theme.Ink
import com.ink.chat.ui.theme.InkType
import com.ink.chat.util.TimeFmt
import org.koin.androidx.compose.koinViewModel
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 余额与用量页（§3.4-⑤，从设置进入；M4 §2.1 D2/D3）。
 * 大数字余额 + 赠送/充值构成 + 今日/本月用量 + 10 段静态字符刻度 + [刷新]。
 * 墨屏：无动画；数字统一等宽特性（tnum）；刻度仅在数据变化时重绘。
 */
@Composable
fun BalanceScreen(
    onBack: () -> Unit,
    vm: BalanceViewModel = koinViewModel(),
) {
    val balance by vm.balance.collectAsState()
    val usage by vm.usage.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val notice by vm.notice.collectAsState()

    val b = balance

    Column(Modifier.fillMaxSize().background(Ink.Paper)) {
        InkTopBar(
            title = "余额与用量",
            leftIcon = Icons.AutoMirrored.Outlined.ArrowBack,
            onLeft = onBack
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Ink.PadPage, vertical = Ink.PadBlock),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(Ink.PadBlock))

            // —— 可用余额 · 大数字 ——
            Text(
                text = fmtTotal(b),
                fontSize = InkType.Display,
                fontWeight = FontWeight.SemiBold,
                color = Ink.Ink,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "（可用余额 · ${b?.currency ?: "CNY"}）",
                fontSize = InkType.Caption,
                color = Ink.InkMid
            )

            // —— 赠送 / 充值构成 ——
            val granted = b?.grantedBalance
            val topped = b?.toppedUpBalance
            if (granted != null || topped != null) {
                Spacer(Modifier.height(Ink.PadLine))
                Row {
                    if (granted != null) {
                        Text("赠送 ￥$granted", fontSize = InkType.Alt, color = Ink.InkDeep)
                    }
                    if (granted != null && topped != null) {
                        Text("　｜　", fontSize = InkType.Alt, color = Ink.InkMid)
                    }
                    if (topped != null) {
                        Text("充值 ￥$topped", fontSize = InkType.Alt, color = Ink.InkDeep)
                    }
                }
            }

            // —— 余额不足提示（D2：is_available:false 明确提示；符号 + 加粗 + 框线）——
            if (b != null && !b.available) {
                Spacer(Modifier.height(Ink.PadBlock))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .border(Ink.StrongLine, Ink.Ink)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "！余额不足，请充值。",
                        fontSize = InkType.Alt,
                        fontWeight = FontWeight.Bold,
                        color = Ink.Ink
                    )
                }
            }

            Spacer(Modifier.height(Ink.PadBlock))
            Box(Modifier.fillMaxWidth().height(Ink.Hairline).background(Ink.Line))
            Spacer(Modifier.height(Ink.PadBlock))

            // —— 今日用量 ——
            UsageLine(
                label = "今日用量",
                value = "↑${fmtCount(usage.todayIn)}　↓${fmtCount(usage.todayOut)}"
            )

            Spacer(Modifier.height(Ink.PadBlock))

            // —— 本月用量 + 10 段静态字符刻度（较上月；无上月数据时显示空刻度）——
            UsageLine(
                label = "本月用量",
                value = "↑${fmtCount(usage.monthIn)}　↓${fmtCount(usage.monthOut)}"
            )
            Spacer(Modifier.height(Ink.PadTight))
            val ratio = if (usage.lastMonthTotal > 0) {
                (usage.monthTotal.toFloat() / usage.lastMonthTotal).coerceIn(0f, 1f)
            } else null
            val filled = ratio?.let { (it * 10).roundToInt() } ?: 0
            val bar = "━".repeat(filled) + "─".repeat(10 - filled)
            Text(
                text = if (ratio != null) {
                    "$bar　约${(ratio * 100).roundToInt()}%（较上月）"
                } else {
                    "$bar　—"
                },
                fontSize = InkType.Caption,
                color = Ink.InkMid,
                style = TextStyle(fontFeatureSettings = "tnum")
            )

            Spacer(Modifier.height(Ink.PadBlock * 2))

            InkPrimaryButton(
                text = if (refreshing) "正在刷新…" else "刷新",
                enabled = !refreshing,
                onClick = { vm.refresh() }
            )

            val fetchedAt = b?.fetchedAt ?: 0L
            if (fetchedAt > 0) {
                Spacer(Modifier.height(Ink.PadTight))
                Text(
                    text = "更新于 ${TimeFmt.hhmm(fetchedAt)}",
                    fontSize = InkType.Caption,
                    color = Ink.InkMid
                )
            }

            Spacer(Modifier.height(Ink.PadBlock))
        }

        notice?.let { msg ->
            InkNotice(msg, onDismiss = vm::dismissNotice, isError = msg.startsWith("！"))
        }
    }
}

@Composable
private fun UsageLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = InkType.Body,
            color = Ink.Ink
        )
        Text(
            text = value,
            fontSize = InkType.Body,
            color = Ink.Ink,
            style = TextStyle(fontFeatureSettings = "tnum")
        )
    }
}

private fun fmtTotal(b: BalanceSnapshot?): String {
    if (b == null) return "—"
    val prefix = if (b.currency == "CNY") "￥" else b.currency + " "
    return prefix + b.totalBalance
}

/** 12400 → "12,400" */
private fun fmtCount(n: Int): String = String.format(Locale.US, "%,d", n)