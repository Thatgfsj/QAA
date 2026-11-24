package com.thatgfsj.qaa.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Terminal 终端模式入口（Issue #9 三段式骨架）。
 *
 * 固定三段布局：顶部常驻栏（不滚动）-> 中部流式滚动区（占满剩余高度）->
 * 底部常驻统计栏 + 快捷键区（不滚动）。
 *
 * 安全区：三段式整体接入 WindowInsets（状态栏 / 刘海 / 手势条 / 键盘），
 * 顶栏顶部与底部统计栏、输入行都不再贴边；黑色背景仍在 inset 区域铺满。
 *
 * 会话进入策略：进入即空会话；「清屏再渲染」由命令层（Issue #10）调用
 * [TerminalViewModel.resetSession] 驱动，本层只提供能力。
 */
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel = viewModel(),
    onSubmit: (String) -> Unit = viewModel::onUserInput,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    TerminalTheme {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(TerminalColors.Background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            TerminalTopBar(state = state)
            TerminalConversation(
                state = state,
                onToolAllow = viewModel::onToolAllow,
                onToolDeny = viewModel::onToolDeny,
                onFollowChanged = viewModel::setFollowOutput,
                modifier = Modifier.weight(1f),
            )
            TerminalBottomBar(
                state = state,
                onSubmit = onSubmit,
                onToolAllow = viewModel::onToolAllow,
                onToolDeny = viewModel::onToolDeny,
                onNotice = viewModel::appendOutput,
            )
        }
    }
}
