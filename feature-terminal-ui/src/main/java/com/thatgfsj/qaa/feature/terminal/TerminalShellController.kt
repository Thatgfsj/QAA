package com.thatgfsj.qaa.feature.terminal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Shell 命令路由（Issue #10 / #19）：持有双状态机状态，把 [ShellCommandParser] 的解析结果分发到
 * [TerminalViewModel] 的既有接线点与 [TerminalAgentPort] 的真实执行体。
 *
 * 分发分两类：
 * - 视图副作用（清屏 / 模式 / 模型 / 后端 / start window）同步执行，保证回显顺序稳定；
 * - 命令执行体（prompt 轮次、/tree、/fork、/compact、/skills、/status 明细、model pull）
 *   经 [scope] 异步执行，结果以中文行追加到对话区，不阻塞输入行；
 * - 系统选择器类命令（model import、storage grant）转交宿主回调，选择结果仍由宿主回显。
 *
 * 纯 Kotlin：Android 侧只注入端口实现与回调，便于 JVM 集成单测。
 */
class TerminalShellController(
    private val viewModel: TerminalViewModel,
    private val onPrompt: (text: String, mode: TerminalMode) -> Unit = { _, _ -> },
    private val onStartNormalWindow: () -> Unit = {},
    models: List<LocalModelEntry> = LocalModelCatalog.entries,
    private val agent: TerminalAgentPort? = null,
    private val modelList: TerminalModelListPort? = null,
    private val modelPull: TerminalModelPullPort? = null,
    private val onRequestModelImport: () -> Unit = {},
    private val onRequestStorageGrant: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    /** 当前命令阶段：chat / agent 进入 [ShellStage.SESSION]，/exit 回到 [ShellStage.SHELL]。 */
    var stage: ShellStage = ShellStage.SHELL
        private set

    /** 当前请求后端（backend 命令的锁定值；AUTO 表示自动降级链）。 */
    var requestedBackend: BackendRequest = BackendRequest.AUTO
        private set

    /** 模型清单快照：初值来自构造参数，接线后由 ModelCatalog 刷新。 */
    private var modelsSnapshot: List<LocalModelEntry> = models

    private var promptNoticeShown = false
    private var pullJob: Job? = null

    init {
        refreshModels()
    }

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
            models = modelsSnapshot,
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
            is ShellEffect.SelectModel -> {
                viewModel.setModelInfo(effect.model.name, effect.model.quantization)
                loadModel(effect.model.name)
            }
            ShellEffect.NewSession -> startNewSession()
            is ShellEffect.SetBackend -> {
                requestedBackend = effect.request
                effect.request.toTerminalBackend()?.let(viewModel::setBackend)
                // 请求后端同时交给运行链：引擎已加载时立即按新档位重载。
                val port = agent
                if (port != null) {
                    scope.launch { viewModel.appendOutput(port.lockBackend(effect.request)) }
                }
            }
            is ShellEffect.ForwardPrompt -> forwardPrompt(effect.text)
            ShellEffect.StartNormalWindow -> onStartNormalWindow()
            ShellEffect.ImportModel -> onRequestModelImport()
            ShellEffect.GrantStorage -> onRequestStorageGrant()
            is ShellEffect.PullModel -> pullModel(effect.url)
            ShellEffect.QueryTree -> query { required(it.tree()) }
            is ShellEffect.ForkNode -> query { required(it.fork(effect.nodeId)) }
            ShellEffect.CompactContext -> query { required(it.compact()) }
            ShellEffect.QuerySkills -> query { required(it.skills()) }
            ShellEffect.QueryStatusExtra -> query(silentWhenUnwired = true) { required(it.statusExtra()) }
            is ShellEffect.SteerInstruction -> query { required(it.steer(effect.text)) }
        }
    }

    /** 会话态输入：同步触发 prompt 回调，再按运行状态选择新轮次或 Follow-up 队列。 */
    private fun forwardPrompt(text: String) {
        onPrompt(text, viewModel.uiState.value.mode)
        val port = agent
        if (port == null) {
            // 未接线时只提示一次，避免每条输入都刷屏。
            if (!promptNoticeShown) {
                promptNoticeShown = true
                viewModel.appendOutput(UNWIRED_NOTICE)
            }
            return
        }
        scope.launch {
            if (port.state.value == TerminalAgentState.RUNNING) {
                viewModel.appendOutput(port.followUp(text))
            } else {
                port.runTurn(text)
            }
        }
    }

    /** /new：先清屏，再向运行链申请新会话并回显新会话 id。 */
    private fun startNewSession() {
        val port = agent
        if (port == null) {
            viewModel.appendOutput(UNWIRED_NOTICE)
            return
        }
        viewModel.resetSession()
        promptNoticeShown = false
        scope.launch { viewModel.appendOutput(required(port.newSession())) }
    }

    /** 统一执行一条查询类命令：结果整段回显，异常转中文提示。 */
    private fun query(silentWhenUnwired: Boolean = false, block: suspend (TerminalAgentPort) -> String) {
        val port = agent
        if (port == null) {
            if (!silentWhenUnwired) viewModel.appendOutput(UNWIRED_NOTICE)
            return
        }
        scope.launch {
            val text = try {
                block(port)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "命令执行失败：${error.message ?: error::class.simpleName}"
            }
            viewModel.appendOutput(text)
        }
    }

    /** 选定模型后按需载入引擎（载入失败只回显原因，不改动顶栏回显）。 */
    private fun loadModel(name: String) {
        val port = agent ?: return
        scope.launch {
            viewModel.appendOutput(port.selectModel(name))
        }
    }

    /**
     * model pull：订阅拉取进度流，按 10% 分档回显（避免刷屏），完成 / 失败各回显一行，
     * 成功后刷新模型清单，使 chat <序号> 立即可选。
     */
    private fun pullModel(url: String) {
        val port = modelPull
        if (port == null) {
            viewModel.appendOutput(UNWIRED_NOTICE)
            return
        }
        pullJob?.cancel()
        pullJob = scope.launch {
            var lastBucket = -1
            try {
                port.pull(url).collect { event ->
                    when (event) {
                        is TerminalPullEvent.Progress -> {
                            val bucket = event.percent?.let { (it * PULL_BUCKETS).toInt() } ?: lastBucket
                            if (bucket != lastBucket) {
                                lastBucket = bucket
                                viewModel.appendOutput(pullProgressText(event))
                            }
                        }
                        is TerminalPullEvent.Completed -> {
                            viewModel.appendOutput(pullCompletedText(event))
                            refreshModels()
                        }
                        is TerminalPullEvent.Failed -> viewModel.appendOutput(pullFailedText(event))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                viewModel.appendOutput("模型下载中断：${error.message ?: error::class.simpleName}")
            }
        }
    }

    /** 从模型清单端口刷新快照（失败保持旧快照，不影响命令可用性）。导入完成后由宿主调用。 */
    fun refreshModels() {
        val port = modelList ?: return
        scope.launch {
            val entries = try {
                port.entries()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (entries.isNotEmpty()) modelsSnapshot = entries
        }
    }

    private fun required(text: String): String = text.ifBlank { NO_CONTENT_NOTICE }

    companion object {
        /** 拉取进度分档数：每 10% 回显一行。 */
        const val PULL_BUCKETS = 10

        /** 未接线时的一次性提示（真实装配层不会出现）。 */
        const val UNWIRED_NOTICE = "Agent 运行链路尚未接入：本次输入未执行。"

        /** 执行体返回空串时的兜底文案。 */
        const val NO_CONTENT_NOTICE = "（命令无输出）"

        /** 拉取进度文案：字节与百分比，总长未知时只回显已下载量。 */
        fun pullProgressText(event: TerminalPullEvent.Progress): String {
            val percent = event.percent?.let { "%.0f%%".format(java.util.Locale.ROOT, it * 100) }
            val bytes = "已下载 ${event.bytes} 字节"
            val total = event.totalBytes?.let { " / $it 字节" }.orEmpty()
            return if (percent == null) "模型下载中：$bytes" else "模型下载中：$percent（$bytes$total）"
        }

        /** 完成文案：模型名 + 权威路径（清单登记由端口实现完成）。 */
        fun pullCompletedText(event: TerminalPullEvent.Completed): String =
            "模型下载完成：${event.name}" + (event.path?.let { "，路径：$it" } ?: "；路径待模型管理器返回")

        /** 失败文案：错误码 + 可续传提示。 */
        fun pullFailedText(event: TerminalPullEvent.Failed): String =
            "模型下载失败（code=${event.code}）：${event.message}" +
                if (event.resumable) "；可用相同地址重试续传。" else "；该错误重试不会推进。"
    }
}
