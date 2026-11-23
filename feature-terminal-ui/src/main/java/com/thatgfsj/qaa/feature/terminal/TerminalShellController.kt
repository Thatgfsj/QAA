package com.thatgfsj.qaa.feature.terminal

/**
 * Shell 命令路由（Issue #10）：持有双状态机状态，把 [ShellCommandParser] 的解析结果分发到
 * [TerminalViewModel] 的既有接线点（resetSession / setMode / setBackend / setModelInfo /
 * onUserInput），并统一输出中文回显。
 *
 * 纯 Kotlin：Android 侧只注入「切回正常窗口」与对话回调，便于 JVM 集成单测。
 */
class TerminalShellController(
    private val viewModel: TerminalViewModel,
    private val onPrompt: (text: String, mode: TerminalMode) -> Unit = { _, _ -> },
    private val onStartNormalWindow: () -> Unit = {},
    private val models: List<LocalModelEntry> = LocalModelCatalog.entries,
) {

    /** 当前命令阶段：chat / agent 进入 [ShellStage.SESSION]，/exit 回到 [ShellStage.SHELL]。 */
    var stage: ShellStage = ShellStage.SHELL
        private set

    /** 当前请求后端（backend 命令的锁定值；AUTO 表示自动降级链）。 */
    var requestedBackend: BackendRequest = BackendRequest.AUTO
        private set

    private var promptNoticeShown = false

    /** 终端输入行入口：空输入忽略；其余按当前阶段解析后分发。 */
    fun onInput(text: String) {
        val input = text.trim()
        if (input.isEmpty()) return
        val snapshot = viewModel.uiState.value
        val context = ShellContext(
            stage = stage,
            mode = snapshot.mode,
            modelInfo = snapshot.modelInfo,
            requestedBackend = requestedBackend,
            activeBackend = snapshot.backend,
            metrics = snapshot.metrics,
            models = models,
        )
        apply(input, ShellCommandParser.parse(input, context))
    }

    private fun apply(input: String, result: ShellParseResult) {
        // 顺序契约：先回显输入，再执行状态副作用（清屏会丢弃回显），最后追加中文输出。
        viewModel.onUserInput(input)
        result.effects.forEach(::applyEffect)
        stage = result.stage
        result.output.forEach(viewModel::appendOutput)
    }

    private fun applyEffect(effect: ShellEffect) {
        when (effect) {
            ShellEffect.ClearScreen -> {
                viewModel.resetSession()
                promptNoticeShown = false
            }
            is ShellEffect.SetMode -> viewModel.setMode(effect.mode)
            is ShellEffect.SelectModel -> viewModel.setModelInfo(effect.model.name, effect.model.quantization)
            is ShellEffect.SetBackend -> {
                requestedBackend = effect.request
                effect.request.toTerminalBackend()?.let(viewModel::setBackend)
            }
            is ShellEffect.ForwardPrompt -> {
                if (!promptNoticeShown) {
                    promptNoticeShown = true
                    viewModel.appendOutput(PROMPT_NOTICE)
                }
                onPrompt(effect.text, viewModel.uiState.value.mode)
            }
            ShellEffect.StartNormalWindow -> onStartNormalWindow()
        }
    }

    companion object {
        /**
         * 会话态 prompt 的一次性提示：推理引擎接线属于冒烟验收议题；
         * 「确认演示」为人工验收入口（真实工具调用由 AgentLoop 触发）。
         */
        const val PROMPT_NOTICE =
            "（推理引擎接线随冒烟验收议题 #11 落地，本期暂不返回模型输出；" +
                "输入「确认演示」可预览工具确认流）"
    }
}
