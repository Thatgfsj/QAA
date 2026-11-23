package com.thatgfsj.qaa

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thatgfsj.qaa.assembly.AgentEventKind
import com.thatgfsj.qaa.assembly.AgentRuntime
import com.thatgfsj.qaa.feature.terminal.TerminalScreen
import com.thatgfsj.qaa.feature.terminal.TerminalShellController
import com.thatgfsj.qaa.feature.terminal.TerminalUiGate
import com.thatgfsj.qaa.feature.terminal.TerminalViewModel

/**
 * Terminal 前端装配：三段式视图 + Shell 命令路由 + 真实 Agent 运行链。
 *
 * - 命令路由：[TerminalShellController] 解析输入并分发到 [TerminalViewModel] 与运行链端口
 *   （prompt 轮次、/tree、/fork、/compact、/skills、/steer、/status 明细、model pull）；
 * - 工具确认：[TerminalUiGate] 绑定到运行链的确认门，Agent Loop 在写类工具前挂起；
 * - 统计栏：绑定真实推理指标流（初始化完成前显示冒烟配置的占位值）；
 * - 轮次事件：助手文本逐条并入对话区，工具结果与运行提示以 SYSTEM 行回显。
 *
 * start window 回调由宿主 Activity 注入（写 ui_mode = NORMAL 后立即重启 App）。
 */
@Composable
fun TerminalHost(
    runtime: AgentRuntime,
    onStartNormalWindow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val terminalViewModel: TerminalViewModel = viewModel()
    val confirmGate = remember(terminalViewModel) { TerminalUiGate(terminalViewModel) }
    val scope = rememberCoroutineScope()

    // 确认门与指标体系一次性接到运行链；组合销毁只解绑 UI，不销毁运行时。
    DisposableEffect(runtime, confirmGate) {
        runtime.bindConfirmationGate(confirmGate)
        onDispose { }
    }
    LaunchedEffect(runtime, terminalViewModel) {
        terminalViewModel.bindEngineMetrics(runtime.metrics)
    }
    LaunchedEffect(runtime, terminalViewModel) {
        runtime.events.collect { event ->
            when (event.kind) {
                AgentEventKind.ASSISTANT -> terminalViewModel.onAiChunk(event.text + "\n")
                AgentEventKind.TOOL, AgentEventKind.NOTICE -> terminalViewModel.appendOutput(event.text)
            }
        }
    }

    val controller = remember(terminalViewModel, onStartNormalWindow, runtime) {
        TerminalShellController(
            viewModel = terminalViewModel,
            onStartNormalWindow = onStartNormalWindow,
            agent = runtime,
            modelList = runtime,
            modelPull = runtime,
            scope = scope,
        )
    }
    TerminalScreen(
        viewModel = terminalViewModel,
        onSubmit = controller::onInput,
        modifier = modifier,
    )
}
