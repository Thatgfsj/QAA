package com.thatgfsj.qaa

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thatgfsj.qaa.assembly.AgentEventKind
import com.thatgfsj.qaa.assembly.AgentRuntime
import com.thatgfsj.qaa.assembly.AssistantStreamEvent
import com.thatgfsj.qaa.feature.terminal.TerminalScreen
import com.thatgfsj.qaa.feature.terminal.TerminalShellController
import com.thatgfsj.qaa.feature.terminal.TerminalUiGate
import com.thatgfsj.qaa.feature.terminal.TerminalViewModel
import kotlinx.coroutines.launch

/**
 * Terminal 前端装配：三段式视图 + Shell 命令路由 + 真实 Agent 运行链。
 *
 * - 命令路由：[TerminalShellController] 解析输入并分发到 [TerminalViewModel] 与运行链端口
 *   （prompt 轮次、/tree、/fork、/compact、/skills、/steer、/status 明细、model pull）；
 * - 系统选择器：model import 走 ACTION_OPEN_DOCUMENT、storage grant 走
 *   ACTION_OPEN_DOCUMENT_TREE，选中结果交给运行链复制模型或挂载沙盒根，回显为中文；
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
    val scope = rememberCoroutineScope()
    // 会话级自动决策（#93）生效时回显一行中文提示：自动放行 / 拒绝不留静默痕迹。
    val confirmGate = remember(terminalViewModel, scope) {
        TerminalUiGate(terminalViewModel) { description, policy ->
            scope.launch {
                terminalViewModel.appendOutput("已按会话级${policy.displayLabel}执行：$description")
            }
        }
    }
    val importPicker = remember { DeferredAction() }
    val storagePicker = remember { DeferredAction() }

    // 确认门与指标体系一次性接到运行链；组合销毁只解绑 UI，不销毁运行时。
    DisposableEffect(runtime, confirmGate) {
        runtime.bindConfirmationGate(confirmGate)
        onDispose { }
    }
    LaunchedEffect(runtime, terminalViewModel) {
        terminalViewModel.bindEngineMetrics(runtime.metrics)
        // 模型载入占位行同样订阅运行链状态流：切换全程有反馈，完成即静默移除。
        terminalViewModel.bindModelLoad(runtime.modelLoad)
    }
    LaunchedEffect(runtime, terminalViewModel) {
        runtime.events.collect { event ->
            when (event.kind) {
                // 已流式上屏的生成步不再整段重放（适配层已按步去重），此处只兜底无流式引擎。
                AgentEventKind.ASSISTANT -> terminalViewModel.onAiChunk(event.text + "\n")
                AgentEventKind.TOOL, AgentEventKind.NOTICE -> terminalViewModel.appendOutput(event.text)
            }
        }
    }
    LaunchedEffect(runtime, terminalViewModel) {
        runtime.assistantStream.collect { event ->
            when (event) {
                is AssistantStreamEvent.Started -> terminalViewModel.beginAssistantTurn(event.streamId)
                is AssistantStreamEvent.Delta -> terminalViewModel.appendToken(event.text, event.streamId)
                is AssistantStreamEvent.Finished ->
                    terminalViewModel.endAssistantTurn(event.streamId, event.text)
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
            onRequestModelImport = importPicker::run,
            onRequestStorageGrant = storagePicker::run,
            scope = scope,
        )
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            terminalViewModel.appendOutput(MODEL_IMPORT_CANCELLED)
        } else {
            scope.launch {
                terminalViewModel.appendOutput(runtime.importModel(uri))
                controller.refreshModels()
            }
        }
    }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            terminalViewModel.appendOutput(STORAGE_GRANT_CANCELLED)
        } else {
            scope.launch { terminalViewModel.appendOutput(runtime.mountStorageGrant(uri)) }
        }
    }

    // 启动器在组合期创建后回填，命令回调只持有稳定包装。
    SideEffect {
        importPicker.bind { importLauncher.launch(MODEL_IMPORT_MIME_TYPES) }
        storagePicker.bind { storageLauncher.launch(null) }
    }

    TerminalScreen(
        viewModel = terminalViewModel,
        onSubmit = controller::onInput,
        modifier = modifier,
    )
}

/** 系统选择器启动器与命令回调的解耦包装：回调在组合完成后才被触发。 */
private class DeferredAction {
    private var action: (() -> Unit)? = null

    fun bind(block: () -> Unit) {
        action = block
    }

    fun run() {
        action?.invoke()
    }
}

/** GGUF 常被提供者标记为二进制流；同时附带 GGUF 专用 MIME 作为兜底。 */
private val MODEL_IMPORT_MIME_TYPES = arrayOf(
    "application/octet-stream",
    "application/gguf",
    "application/x-gguf",
)

private const val MODEL_IMPORT_CANCELLED = "已取消模型导入。"
private const val STORAGE_GRANT_CANCELLED = "已取消存储授权。"
