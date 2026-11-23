package com.thatgfsj.qaa

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thatgfsj.qaa.feature.terminal.TerminalConfirmDemo
import com.thatgfsj.qaa.feature.terminal.TerminalScreen
import com.thatgfsj.qaa.feature.terminal.TerminalShellController
import com.thatgfsj.qaa.feature.terminal.TerminalUiGate
import com.thatgfsj.qaa.feature.terminal.TerminalViewModel
import kotlinx.coroutines.launch

/**
 * Terminal 前端装配：三段式视图 + Shell 命令路由。
 *
 * - 命令路由：[TerminalShellController] 解析输入并分发到 [TerminalViewModel] 的既有接线点；
 * - 工具确认：[TerminalUiGate] 在此装配（AgentLoop 的 gate 参数来源，设计文档 3.6）；
 *   真实 Agent 运行链归冒烟验收议题 #11，本期由「确认演示」提供人工验收入口；
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
    val confirmGate = remember(terminalViewModel) { TerminalUiGate(terminalViewModel) }
    val scope = rememberCoroutineScope()
    val controller = remember(terminalViewModel, onStartNormalWindow, confirmGate) {
        TerminalShellController(
            viewModel = terminalViewModel,
            onStartNormalWindow = onStartNormalWindow,
            onPrompt = { text, _ ->
                // 会话态输入演示触发词：挂起一次模拟 write 调用，验收 Allow / Deny 交互。
                if (text == TerminalConfirmDemo.TRIGGER) {
                    scope.launch { TerminalConfirmDemo.run(confirmGate, terminalViewModel) }
                }
            },
        )
    }
    TerminalScreen(
        viewModel = terminalViewModel,
        onSubmit = controller::onInput,
        modifier = modifier,
    )
}
