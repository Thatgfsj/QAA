package com.thatgfsj.qaa.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 底部常驻输入行 + 统计栏 + 快捷键提示（Issue #9 骨架 / #10 接线）。
 *
 * 输入行：回车（软键盘「完成」或物理回车）提交一行，交给命令路由解析；
 * 输入为空时展示占位提示，提交后立即清空。
 * 统计行：TTFT: 142ms | 速度: 38.4 tok/s | Token: 412 / 4096
 * （骨架期由 FakeMetricsTicker 心跳驱动；真实 metrics 流随 Issue #5 装配层接线）。
 */
@Composable
fun TerminalBottomBar(
    state: TerminalUiState,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val submit = {
        val text = input.trim()
        if (text.isNotEmpty()) {
            onSubmit(text)
            input = ""
        }
    }
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "❯ ",
                color = TerminalColors.UserPrefix,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
            )
            Box(modifier = Modifier.weight(1f)) {
                if (input.isEmpty()) {
                    Text(
                        text = "输入命令或消息，回车执行",
                        color = TerminalColors.HintText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TerminalColors.TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    cursorBrush = SolidColor(TerminalColors.UserPrefix),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text(
            text = state.metrics.statsBarText(),
            color = TerminalColors.TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
        )
        // 快捷键区两行占位：后续按键联动随对应议题补齐。
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
