package com.thatgfsj.qaa

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thatgfsj.qaa.feature.terminal.TerminalScreen
import com.thatgfsj.qaa.feature.terminal.TerminalShellController
import com.thatgfsj.qaa.feature.terminal.TerminalViewModel

/**
 * Terminal 前端装配：三段式视图 + Shell 命令路由。
 *
 * - 命令路由：[TerminalShellController] 解析输入并分发到 [TerminalViewModel] 的既有接线点；
 * - 统计栏：[TerminalViewModel] 默认绑定模块内 fake metrics（真实引擎流随冒烟验收议题接线）；
 * - start window：回调由宿主 Activity 注入（写 ui_mode = NORMAL 后立即重启 App），
 *   协程作用域归 Activity，避免组合销毁导致重启动作被取消。
 */
@Composable
fun TerminalHost(
    onStartNormalWindow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val terminalViewModel: TerminalViewModel = viewModel()
    val controller = remember(terminalViewModel, onStartNormalWindow) {
        TerminalShellController(
            viewModel = terminalViewModel,
            onStartNormalWindow = onStartNormalWindow,
        )
    }
    TerminalScreen(
        viewModel = terminalViewModel,
        onSubmit = controller::onInput,
        modifier = modifier,
    )
}
