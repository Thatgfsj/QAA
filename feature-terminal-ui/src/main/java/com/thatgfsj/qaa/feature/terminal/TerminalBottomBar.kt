package com.thatgfsj.qaa.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 底部常驻统计栏 + 快捷键占位区（Issue #9）。
 *
 * 统计行：TTFT: 142ms | 速度: 38.4 tok/s | Token: 412 / 4096
 * （骨架期由 FakeMetricsTicker 心跳驱动；Issue #5 接真实 metrics 流）。
 * 下方两行快捷键提示为占位文案，按键联动属命令层（Issue #10）。
 */
@Composable
fun TerminalBottomBar(state: TerminalUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TerminalColors.Background)
            .drawBehind {
                drawLine(
                    color = TerminalColors.BarDivider,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = state.metrics.statsBarText(),
            color = TerminalColors.TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
        )
        // 快捷键区两行占位：文案为暂定稿，接线归 Issue #10。
        Text(
            text = "←↓→    输入历史翻阅",
            color = TerminalColors.HintText,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
        )
        Text(
            text = "yes↑ no↓    工具确认选择",
            color = TerminalColors.HintText,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}
