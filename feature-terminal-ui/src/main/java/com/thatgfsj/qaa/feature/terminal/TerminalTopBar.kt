package com.thatgfsj.qaa.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 顶部常驻栏（Issue #9）：单行、高度自适应、不随对话滚动。
 *
 * 内容：[MODEL] 模型名(量化) | [CHAT/AGENT] | [实际后端 NPU/HYBRID/CPU]；
 * 模型段允许以省略号压缩，模式 / 后端标签保持完整。
 */
@Composable
fun TerminalTopBar(state: TerminalUiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TerminalColors.Background)
            .drawBehind {
                drawLine(
                    color = TerminalColors.BarDivider,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        val colors = listOf(
            TerminalColors.TextPrimary,
            TerminalColors.TagText,
            backendTagColor(state.backend),
        )
        state.topBarSegments().forEachIndexed { index, segment ->
            if (index > 0) {
                Text(
                    text = " | ",
                    color = TerminalColors.BarDividerText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            Text(
                text = segment,
                color = colors[index],
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (index == 0) Modifier.weight(1f, fill = false) else Modifier,
            )
        }
    }
}

/** 后端标签配色：NPU 绿 / HYBRID 黄 / CPU 红，直观呈现降级链命中层级。 */
private fun backendTagColor(backend: TerminalBackend): Color = when (backend) {
    TerminalBackend.NPU -> TerminalColors.AiPrefix
    TerminalBackend.HYBRID -> TerminalColors.TagText
    TerminalBackend.CPU -> TerminalColors.DenyText
}
