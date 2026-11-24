package com.thatgfsj.qaa.feature.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 底部常驻输入行 + 统计栏 + 快捷键按钮（骨架 #9 / 接线 #10 / 快捷键 #79）。
 *
 * 输入行：回车（软键盘「完成」或物理回车）提交一行交给命令路由，提交成功后记入历史。
 * 快捷键：↑ / ↓ 把历史行填回输入行；yes / no 在确认挂起时等效 Allow / Deny，
 * 无挂起时向对话区写入中文提示。
 * 统计行：TTFT / 速度 / Token（真实指标由装配层注入）。
 */
@Composable
fun TerminalBottomBar(
    state: TerminalUiState,
    onSubmit: (String) -> Unit,
    onToolAllow: () -> Unit = {},
    onToolDeny: () -> Unit = {},
    onNotice: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val history = remember { TerminalInputHistory() }

    val submit = {
        val text = input.trim()
        if (text.isNotEmpty()) {
            onSubmit(text)
            history.record(text)
            input = ""
        }
    }
    val onShortcut: (TerminalShortcut) -> Unit = { shortcut ->
        when (val action = shortcut.actionFor(state)) {
            TerminalShortcutAction.HistoryPrev -> history.previous(input)?.let { input = it }
            TerminalShortcutAction.HistoryNext -> history.next()?.let { input = it }
            TerminalShortcutAction.AllowTool -> onToolAllow()
            TerminalShortcutAction.DenyTool -> onToolDeny()
            is TerminalShortcutAction.Notice -> onNotice(action.text)
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
        ShortcutRow(onShortcut = onShortcut)
    }
}

/** 快捷键行：四个真实按钮 + 分组说明。 */
@Composable
private fun ShortcutRow(onShortcut: (TerminalShortcut) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShortcutButton(TerminalShortcut.HISTORY_PREV) { onShortcut(TerminalShortcut.HISTORY_PREV) }
        ShortcutButton(TerminalShortcut.HISTORY_NEXT) { onShortcut(TerminalShortcut.HISTORY_NEXT) }
        ShortcutLabel(text = "历史")
        Spacer(Modifier.width(6.dp))
        ShortcutButton(TerminalShortcut.CONFIRM_ALLOW) { onShortcut(TerminalShortcut.CONFIRM_ALLOW) }
        ShortcutButton(TerminalShortcut.CONFIRM_DENY) { onShortcut(TerminalShortcut.CONFIRM_DENY) }
        ShortcutLabel(text = "确认")
    }
}

/** 终端风按钮：直角描边 + 等宽字 + Material 按下反馈。 */
@Composable
private fun ShortcutButton(shortcut: TerminalShortcut, onClick: () -> Unit) {
    val contentColor = when (shortcut) {
        TerminalShortcut.CONFIRM_ALLOW -> TerminalColors.AiPrefix
        TerminalShortcut.CONFIRM_DENY -> TerminalColors.DenyText
        TerminalShortcut.HISTORY_PREV, TerminalShortcut.HISTORY_NEXT -> TerminalColors.TextPrimary
    }
    OutlinedButton(
        onClick = onClick,
        shape = RectangleShape,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.65f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Text(text = shortcut.label, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

/** 快捷键分组说明（等宽小字）。 */
@Composable
private fun ShortcutLabel(text: String) {
    Text(
        text = text,
        color = TerminalColors.HintText,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        maxLines = 1,
    )
}
