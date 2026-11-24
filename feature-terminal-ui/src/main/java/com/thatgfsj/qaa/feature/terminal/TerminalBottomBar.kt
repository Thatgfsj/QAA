package com.thatgfsj.qaa.feature.terminal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 底部常驻输入行 + 统计栏 + 快捷键按钮（骨架 #9 / 接线 #10 / 快捷键 #79 / 增强 #93）。
 *
 * 输入行：回车（软键盘「完成」或物理回车）提交一行交给命令路由，提交成功后记入历史。
 * 快捷键：↑ / ↓ 把历史行填回输入行；yes / no 在确认挂起时等效 Allow / Deny，
 * 无挂起时向对话区写入中文提示；键盘键收起 / 展开软键盘。
 * 长按：按 [TerminalShortcut.alternates] 弹出终端风格次选浮层，点击条目即执行。
 * 统计行：TTFT / 速度 / Token（真实指标由装配层注入）。
 */
@Composable
fun TerminalBottomBar(
    state: TerminalUiState,
    onSubmit: (String) -> Unit,
    onToolAllow: () -> Unit = {},
    onToolDeny: () -> Unit = {},
    onClearScreen: () -> Unit = {},
    onAutoAllowMode: (Boolean) -> Unit = {},
    onNotice: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val history = remember { TerminalInputHistory() }
    var alternateFor by remember { mutableStateOf<TerminalShortcut?>(null) }

    // 软键盘开关（#93 键盘键）：按当前 IME 可见性决定收起还是展开。
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var imeVisible by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) { imeVisible = imeBottom > 0 }

    val toggleKeyboard: () -> Unit = {
        if (imeVisible) {
            focusManager.clearFocus()
            keyboard?.hide()
        } else {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    val submit: () -> Unit = {
        val text = input.trim()
        if (text.isNotEmpty()) {
            onSubmit(text)
            history.record(text)
            input = ""
        }
    }

    val runAction: (TerminalShortcutAction) -> Unit = { action ->
        when (action) {
            TerminalShortcutAction.HistoryPrev -> history.previous(input)?.let { input = it }
            TerminalShortcutAction.HistoryNext -> history.next()?.let { input = it }
            TerminalShortcutAction.HistoryOldest -> history.oldest(input)?.let { input = it }
            TerminalShortcutAction.ClearScreen -> onClearScreen()
            TerminalShortcutAction.AllowTool -> onToolAllow()
            TerminalShortcutAction.DenyTool -> onToolDeny()
            TerminalShortcutAction.RememberAllow -> {
                onAutoAllowMode(true)
                onNotice(TerminalShortcut.AUTO_ALLOW_NOTICE)
            }
            TerminalShortcutAction.DisableAutoAllow -> {
                onAutoAllowMode(false)
                onNotice(TerminalShortcut.AUTO_ALLOW_OFF_NOTICE)
            }
            TerminalShortcutAction.ToggleKeyboard -> toggleKeyboard()
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
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
        // 长按浮层贴在快捷键行上方：父布局即锚点（Popup 按父布局坐标定位）。
        Box(modifier = Modifier.fillMaxWidth()) {
            ShortcutRow(
                policy = state.confirmPolicy,
                onShortcut = { shortcut -> runAction(shortcut.actionFor(state)) },
                onLongPress = { shortcut ->
                    if (shortcut.alternates.isNotEmpty()) alternateFor = shortcut
                },
            )
            val active = alternateFor
            if (active != null) {
                ShortcutAlternatePopup(
                    keyLabel = active.label,
                    alternates = active.alternates,
                    onDismiss = { alternateFor = null },
                    onPick = { alternate ->
                        alternateFor = null
                        runAction(alternate.action)
                    },
                )
            }
        }
    }
}

/** 快捷键行：五个真实按钮 + 分组说明；窄屏可横向滚动，不挤压输入行。 */
@Composable
private fun ShortcutRow(
    policy: ToolConfirmPolicy,
    onShortcut: (TerminalShortcut) -> Unit,
    onLongPress: (TerminalShortcut) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShortcutButton(TerminalShortcut.HISTORY_PREV, onShortcut, onLongPress)
        ShortcutButton(TerminalShortcut.HISTORY_NEXT, onShortcut, onLongPress)
        ShortcutLabel(text = "历史")
        Spacer(Modifier.width(6.dp))
        ShortcutButton(TerminalShortcut.CONFIRM_ALLOW, onShortcut, onLongPress)
        ShortcutButton(TerminalShortcut.CONFIRM_DENY, onShortcut, onLongPress)
        ShortcutLabel(text = confirmScopeLabel(policy), color = policy.color())
        Spacer(Modifier.width(6.dp))
        ShortcutButton(TerminalShortcut.KEYBOARD, onShortcut, onLongPress)
    }
}

private fun ToolConfirmPolicy.color() = when (this) {
    ToolConfirmPolicy.ASK -> TerminalColors.HintText
    ToolConfirmPolicy.AUTO_ALLOW -> TerminalColors.AiPrefix
}

/** 终端风按钮：直角描边 + 等宽字 + Material 按下反馈；有次选时接收长按。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutButton(
    shortcut: TerminalShortcut,
    onClick: (TerminalShortcut) -> Unit,
    onLongPress: (TerminalShortcut) -> Unit,
) {
    val contentColor = when (shortcut) {
        TerminalShortcut.CONFIRM_ALLOW -> TerminalColors.AiPrefix
        TerminalShortcut.CONFIRM_DENY -> TerminalColors.DenyText
        TerminalShortcut.HISTORY_PREV, TerminalShortcut.HISTORY_NEXT -> TerminalColors.TextPrimary
        TerminalShortcut.KEYBOARD -> TerminalColors.TagText
    }
    Box(
        modifier = Modifier
            .border(1.dp, contentColor.copy(alpha = 0.65f))
            .combinedClickable(
                onClick = { onClick(shortcut) },
                onLongClick = { if (shortcut.alternates.isNotEmpty()) onLongPress(shortcut) },
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = shortcut.label,
            color = contentColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/** 快捷键分组说明（等宽小字）。 */
@Composable
private fun ShortcutLabel(text: String, color: androidx.compose.ui.graphics.Color = TerminalColors.HintText) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        maxLines = 1,
    )
}
