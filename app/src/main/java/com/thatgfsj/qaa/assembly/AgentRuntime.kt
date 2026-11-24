package com.thatgfsj.qaa.assembly

import android.content.Context
import android.net.Uri
import com.thatgfsj.qaa.core.harness.AgentConfig
import com.thatgfsj.qaa.core.harness.AgentLoop
import com.thatgfsj.qaa.core.harness.CompactionResult
import com.thatgfsj.qaa.core.harness.ConfirmationDecision
import com.thatgfsj.qaa.core.harness.ConfirmationGate
import com.thatgfsj.qaa.core.harness.ContextCompactor
import com.thatgfsj.qaa.core.harness.ForkResult
import com.thatgfsj.qaa.core.harness.JsonlTurnRecorder
import com.thatgfsj.qaa.core.harness.PromptAssembler
import com.thatgfsj.qaa.core.harness.SessionRole
import com.thatgfsj.qaa.core.harness.SkipReason
import com.thatgfsj.qaa.core.harness.SkillRegistry
import com.thatgfsj.qaa.core.harness.SteerQueue
import com.thatgfsj.qaa.core.harness.SteerTurnDriver
import com.thatgfsj.qaa.core.harness.ToolCall
import com.thatgfsj.qaa.core.harness.TreeSessionStore
import com.thatgfsj.qaa.core.inference.AndroidAssetSource
import com.thatgfsj.qaa.core.inference.AssetModelReleaser
import com.thatgfsj.qaa.core.inference.CatalogEntry
import com.thatgfsj.qaa.core.inference.ComputeBackend
import com.thatgfsj.qaa.core.inference.ContentResolverDocument
import com.thatgfsj.qaa.core.inference.DefaultGenieXModelGateway
import com.thatgfsj.qaa.core.inference.GenieXLlmEngine
import com.thatgfsj.qaa.core.inference.GenieXLlmSessionFactory
import com.thatgfsj.qaa.core.inference.GenieXModelResolver
import com.thatgfsj.qaa.core.inference.GenieXSdkClient
import com.thatgfsj.qaa.core.inference.LogcatBackendProbe
import com.thatgfsj.qaa.core.inference.LlmEnginePort
import com.thatgfsj.qaa.core.inference.LlmLoadConfig
import com.thatgfsj.qaa.core.inference.MemoryProbe
import com.thatgfsj.qaa.core.inference.ModelCatalog
import com.thatgfsj.qaa.core.inference.ModelMemoryCoordinator
import com.thatgfsj.qaa.core.inference.ModelPullClient
import com.thatgfsj.qaa.core.inference.ModelPullEvent
import com.thatgfsj.qaa.core.inference.ModelSource
import com.thatgfsj.qaa.core.inference.RegisteredModel
import com.thatgfsj.qaa.core.tools.DrawImageTool
import com.thatgfsj.qaa.core.tools.LocalSandboxFs
import com.thatgfsj.qaa.core.tools.SandboxRoot
import com.thatgfsj.qaa.core.tools.ToolLimits
import com.thatgfsj.qaa.feature.normal.chat.NormalAgentPort
import com.thatgfsj.qaa.feature.normal.models.NormalDownloadState
import com.thatgfsj.qaa.feature.normal.models.NormalModelEntry
import com.thatgfsj.qaa.feature.normal.models.NormalModelPort
import com.thatgfsj.qaa.feature.normal.models.NormalResumeTask
import com.thatgfsj.qaa.feature.terminal.BackendRequest
import com.thatgfsj.qaa.feature.terminal.LocalModelEntry
import com.thatgfsj.qaa.feature.terminal.TerminalAgentPort
import com.thatgfsj.qaa.feature.terminal.TerminalAgentState
import com.thatgfsj.qaa.feature.terminal.TerminalMetricsSnapshot
import com.thatgfsj.qaa.feature.terminal.TerminalModelLoadState
import com.thatgfsj.qaa.feature.terminal.TerminalModelListPort
import com.thatgfsj.qaa.feature.terminal.TerminalModelPullPort
import com.thatgfsj.qaa.feature.terminal.TerminalPullEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 端到端 Agent 运行装配（Issue #10 / #11 装配遗留 + Issue #19 执行体接线）。
 *
 * 首启链：内置模型释放（[AssetModelReleaser]）→ GenieX SDK 初始化（[GenieXSdkClient]）
 * → 模型清单构建（[ModelCatalog]）→ 引擎加载（[GenieXLlmEngine]）。
 *
 * 端口适配（依赖倒置，组件本体零改动）：
 * - [LlmGatewayAdapter]：GenieX 流 → HarnessPorts.LlmEngineGateway + 统计栏指标；
 * - [ToolRouterGateway]：四原语工具经 [LocalSandboxFs] 执行，filesDir 为默认授权根，
 *   外部目录经 storage grant 以 SAF 根动态挂载（不映射真实路径）；
 * - [RecordingTurnRecorder]：树状 JSONL 落盘 + 压缩视图作为提示历史；
 * - [SteerQueue] 同时接 Loop 的工具步边界与确认挂起竞速（L7），由 [SteerTurnDriver] 驱动轮次；
 * - [ContextCompactor] 在工具步边界与轮末两个安全点自动检查，/compact 走手动入口；
 * - [ModelMemoryCoordinator] 单例，draw_image 本期 engine = null（快速失败，Phase 3 门禁后接入）。
 */
class AgentRuntime private constructor(private val appContext: Context) :
    TerminalAgentPort,
    NormalAgentPort,
    TerminalModelListPort,
    TerminalModelPullPort,
    NormalModelPort {

    private val filesDir: File = appContext.filesDir
    private val modelsDir = File(filesDir, MODELS_DIR)
    private val sessionsDir = File(filesDir, TreeSessionStore.DEFAULT_SESSIONS_DIR)
    private val skillsDir = File(filesDir, SKILLS_DIR)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val sdkClient = GenieXSdkClient(appContext)
    // 引擎复用同一 SDK 客户端：首启链只做一次 GenieX init（引擎内部再调即幂等命中）。
    private val engine: LlmEnginePort = GenieXLlmEngine(
        initializer = sdkClient,
        modelResolver = GenieXModelResolver(),
        sessionFactory = GenieXLlmSessionFactory(),
        backendProbe = LogcatBackendProbe(),
    )
    private val llmGateway = LlmGatewayAdapter(engine)
    private val releaser = AssetModelReleaser(AndroidAssetSource(appContext), modelsDir)
    private val catalog = ModelCatalog(modelsDir, DefaultGenieXModelGateway)
    private val pullClient = ModelPullClient()
    private val sandbox = LocalSandboxFs(listOf(SandboxRoot.File(SANDBOX_ROOT_ID, filesDir.toPath())))
    private val storageGrant = StorageGrant(appContext, sandbox)
    private val modelImport = ModelImportCoordinator(modelsDir, catalog)

    // 下载记录（私有清单文件）与编排：Application 级 scope 持有；进程退出后记录留存，
    // 重进模型管理页时由 recover 检测未完成任务并提示继续（SDK 侧分块保留）。
    private val downloadTracker = ModelDownloadTracker(File(filesDir, ModelDownloadTracker.FILE_NAME))
    private val downloads = ModelDownloadCoordinator(
        tracker = downloadTracker,
        catalog = catalog,
        scope = scope,
        pullClient = pullClient,
    )

    @Volatile
    private var sessionStore = TreeSessionStore(
        sessionId = SESSION_ID,
        file = TreeSessionStore.fileFor(sessionsDir, SESSION_ID),
    )
    private val sessionSwitcher = SessionSwitcher(sessionsDir)
    private val jsonlRecorder = JsonlTurnRecorder(sessionStore)
    private val skills = SkillRegistry.fromDirectory(skillsDir)
    private val compactor = ContextCompactor(engine = llmGateway)

    /** token 级流式转发器：生成步 → UI 增量行（压缩等内部生成期间静默）。 */
    private val tokenRelay = AssistantTokenRelay()
    private val recorder = RecordingTurnRecorder(
        delegate = jsonlRecorder,
        compactedView = { compactor.view(sessionStore) },
        tokenRelay = tokenRelay,
    )
    private val steerQueue = SteerQueue()
    private val driver = SteerTurnDriver(steerQueue)

    private val memoryCoordinator = ModelMemoryCoordinator(
        llm = LlmEngineMemoryHandle(engine) { loadedModelSource },
        imageGen = AbsentImageGenHandle(),
        memoryProbe = MemoryProbe {
            val runtime = Runtime.getRuntime()
            runtime.freeMemory() + (runtime.maxMemory() - runtime.totalMemory())
        },
        outputProbe = SandboxOutputFileProbe(sandbox),
    )
    private val drawImageTool = DrawImageTool(engine = null, coordinator = memoryCoordinator)
    private val toolGateway = ToolRouterGateway(
        sandbox = sandbox,
        sessionId = SESSION_ID,
        limits = ToolLimits(),
        executors = ToolRouterGateway.defaultExecutors(drawImageTool),
    )
    private val gate = DelegatingConfirmationGate()

    private val loop = AgentLoop(
        // 手机端默认关闭 YOLO（AgentConfig 契约），非 YOLO 走确认门。
        config = AgentConfig(yoloMode = false, maxToolSteps = MAX_TOOL_STEPS),
        engine = llmGateway,
        tools = toolGateway,
        gate = gate,
        recorder = recorder,
        assembler = PromptAssembler(skillsSection = skills.describeAll()),
        interruptCheck = {
            // 工具步边界是安全点：先做压缩检查，再消费 steer（消费即终止本轮）。
            safePointCompact()
            steerQueue.interruptCheck()
        },
        confirmationInterrupt = steerQueue,
    )

    private val initMutex = Mutex()
    private val turnMutex = Mutex()
    private val manualCompactRequested = AtomicBoolean(false)

    @Volatile
    private var initialized = false

    @Volatile
    private var startRequested = false

    @Volatile
    private var loadedModelSource: String? = null

    @Volatile
    private var backend: ComputeBackend = ComputeBackend.AUTO

    private val _state = MutableStateFlow(TerminalAgentState.PREPARING)
    private val _status = MutableStateFlow(PREPARING_TEXT)
    private val _modelLoad = MutableStateFlow<TerminalModelLoadState>(TerminalModelLoadState.Idle)
    private val backendSwitcher = BackendSwitcher(engine) { _status.value = it }

    override val state: StateFlow<TerminalAgentState> = _state.asStateFlow()

    /** 运行链状态文案（初始化进度、失败原因）。 */
    val status: StateFlow<String> = _status.asStateFlow()

    /** 统计栏指标流（真实推理指标）。 */
    val metrics: StateFlow<TerminalMetricsSnapshot> = llmGateway.metrics

    /** 模型载入进度流：载入开始置 Loading，结束（无论成败）回到 Idle。 */
    override val modelLoad: StateFlow<TerminalModelLoadState> = _modelLoad.asStateFlow()

    /** 轮次事件流（终端逐条回显）。 */
    val events: SharedFlow<AgentEvent> = recorder.events

    /**
     * 助手 token 流式事件流（生成步粒度）：Terminal 前端据此边生成边渲染，
     * Normal 前端经 [assistantTokens] 取同源片段。
     */
    val assistantStream: SharedFlow<AssistantStreamEvent> = tokenRelay.events

    /** UI 层装配确认门（终端行内确认 / Normal 对话框）。 */
    fun bindConfirmationGate(delegate: ConfirmationGate) = gate.bind(delegate)

    /** 触发首启链（幂等，非阻塞）。 */
    fun start() {
        if (startRequested) return
        startRequested = true
        scope.launch { initialize() }
        // 启动即清点上次遗留的下载任务（幂等）：可续传则暴露给模型管理页。
        scope.launch { downloads.recover() }
    }

    // ---- TerminalAgentPort ----

    override suspend fun runTurn(text: String) {
        if (!ensureReady()) {
            emitNotice("模型尚未就绪：${_status.value}")
            return
        }
        executeTurn(text)
    }

    override suspend fun steer(text: String): String {
        if (!turnMutex.isLocked) return "当前无运行中轮次：/steer 只在轮次运行时生效。"
        steerQueue.requestSteer(text)
        return "已提交干预指令：$text（当前工具步结束后生效）。"
    }

    override suspend fun followUp(text: String): String {
        if (!turnMutex.isLocked) {
            runTurn(text)
            return ""
        }
        steerQueue.offerFollowUp(text)
        return "已加入 Follow-up 队列：轮末按 FIFO 顺序衔接。"
    }

    override suspend fun selectModel(token: String): String {
        ensureSdkReady()
        val entry = catalog.resolveByName(token)
            ?: return "未找到本地模型：$token。输入 model 查看清单（名称或序号，内置模型为 0 号）。"
        val path = entry.path ?: return "模型文件缺失：${entry.name}；请重新下载或导入。"
        if (engine.isLoaded.value && loadedModelSource == path) return "模型已在运行：${entry.name}"
        // 切换耗时由运行链状态流广播：终端占位行据此出现 / 移除，UI 不做耗时估算。
        _modelLoad.value = TerminalModelLoadState.Loading(entry.name)
        val result = try {
            engine.load(path, LlmLoadConfig(backend = backend))
        } finally {
            _modelLoad.value = TerminalModelLoadState.Idle
        }
        loadedModelSource = path
        return result.fold(
            onSuccess = {
                _status.value = READY_TEXT
                "模型已载入：${entry.name}（${entry.quantization ?: "未知量化"}）"
            },
            onFailure = { "模型加载失败：${it.message ?: it::class.simpleName}" },
        )
    }

    override suspend fun lockBackend(request: BackendRequest): String {
        // 重载会中断生成：引擎已加载且轮次进行中时拒绝，避免卸载运行中的会话。
        if (engine.isLoaded.value && turnMutex.isLocked) {
            return "轮次进行中：暂不能切换后端，请等本轮结束后重试。"
        }
        return when (val result = backendSwitcher.apply(loadedModelSource, backend, request)) {
            is BackendSwitchResult.Deferred -> {
                backend = result.next
                result.message
            }
            is BackendSwitchResult.Applied -> {
                backend = result.next
                result.message
            }
            is BackendSwitchResult.RolledBack -> result.message
        }
    }

    override suspend fun newSession(): String {
        if (turnMutex.isLocked) return "轮次进行中：暂不能新建会话，请等本轮结束后重试。"
        val previous = sessionStore
        val store = sessionSwitcher.openNext()
        recorder.rebind(JsonlTurnRecorder(store))
        sessionStore = store
        toolGateway.rebindSession(store.sessionId)
        previous.close()
        return "已新建会话：${store.sessionId}（清屏完成；旧会话 ${previous.sessionId} 的记录已保留）。"
    }

    override suspend fun tree(): String = sessionStore.renderTree()

    override suspend fun fork(nodeId: String?): String {
        val target = nodeId?.trim()?.takeIf { it.isNotEmpty() }
        return when (val result = sessionStore.fork(target)) {
            is ForkResult.Success -> "已从节点 ${result.forkPointId} 分叉" +
                (result.previousLeafId?.let { "（原活动叶 $it）" } ?: "")
            is ForkResult.NodeNotFound -> "节点不存在：${result.nodeId}。输入 /tree 查看节点 ID。"
            ForkResult.EmptySession -> "空会话，没有可分叉的节点。"
            ForkResult.TurnInProgress -> "Agent 轮次进行中，暂不能改树指针；请等本轮结束后重试。"
        }
    }

    override suspend fun compact(): String {
        if (turnMutex.isLocked) {
            manualCompactRequested.set(true)
            return "轮次进行中：手动压缩已排队到最近安全点。"
        }
        return describeCompaction(
            runCompaction { compactor.compactManually(sessionStore, CONTEXT_BUDGET) },
        )
    }

    override suspend fun skills(): String {
        val status = skills.status()
        return buildString {
            appendLine(
                "技能注册表：扫描 ${status.scannedDirectories} 个目录，注册 ${status.registeredCount} 个，" +
                    "跳过 ${status.skippedCount} 个。",
            )
            if (status.skills.isEmpty()) {
                append("技能目录为空：把 SKILL.md 放入 filesDir/$SKILLS_DIR/<名称>/ 后再执行 /skills。")
            } else {
                status.skills.forEach { appendLine("  - ${it.name}: ${it.description}") }
            }
            status.skipped.forEach { appendLine("  ! ${it.directory}（${it.reason.name}）：${it.detail}") }
        }.trimEnd()
    }

    override suspend fun statusExtra(): String {
        val models = runCatching { catalog.list() }.getOrElse { emptyList() }
        val summaryCount = sessionStore.allNodes().count { it.role == SessionRole.SUMMARY }
        val queued = if (steerQueue.hasQueuedInput()) "有" else "无"
        return buildString {
            appendLine("模型清单：${models.size} 项（内置 0 号，其余从 1 起）")
            appendLine(
                sessionStatusText(
                    sessionId = sessionStore.sessionId,
                    nodeCount = sessionStore.nodeCount(),
                    branchNodes = sessionStore.activeBranch().size,
                    summaries = summaryCount,
                ),
            )
            append(
                "上下文：预算 $CONTEXT_BUDGET token，上次 prompt ${llmGateway.lastPromptTokens ?: 0} token；" +
                    "待处理输入：$queued；运行状态：${_state.value}",
            )
        }
    }

    // ---- NormalAgentPort ----

    override suspend fun ask(text: String): String {
        if (!ensureReady()) return "模型尚未就绪：${_status.value}"
        executeTurn(text)
        return recorder.turnAssistantText().ifBlank { "（本轮没有文字答复）" }
    }

    /** Normal 端流式片段：与 Terminal 同源 token；最终文本仍以 [ask] 返回值定稿。 */
    override val assistantTokens: Flow<String> =
        assistantStream.mapNotNull { (it as? AssistantStreamEvent.Delta)?.text }

    // ---- TerminalModelListPort / TerminalModelPullPort ----

    override suspend fun entries(): List<LocalModelEntry> = catalogToLocalEntries(catalog.list())

    override fun pull(url: String): Flow<TerminalPullEvent> = flow {
        pullClient.pull(url).collect { event ->
            if (event is ModelPullEvent.Completed) registerPulled(event)
            emit(event.toTerminalEvent())
        }
    }

    // ---- NormalModelPort ----

    override val downloadState: StateFlow<NormalDownloadState> = downloads.state

    override val resumeTask: StateFlow<NormalResumeTask?> = downloads.resumeTask

    override suspend fun modelEntries(): List<NormalModelEntry> = catalog.list().map { entry ->
        NormalModelEntry(
            ordinal = entry.ordinal,
            name = entry.name,
            quantization = entry.quantization,
            sizeBytes = entry.sizeBytes,
            sourceLabel = when (entry.source) {
                ModelSource.BUILT_IN -> "内置"
                ModelSource.PULL -> "Hub 下载"
                ModelSource.IMPORT -> "本地导入"
            },
        )
    }

    override suspend fun loadModel(ordinal: Int): String = selectModel(ordinal.toString())

    override fun validateSource(source: String): String? = downloads.validate(source)

    override fun startDownload(source: String): String? = downloads.start(source)

    override fun cancelDownload() = downloads.cancel()

    override fun discardDownload() = downloads.discard()

    override suspend fun recoverDownload() = downloads.recover()

    // ---- 系统选择器接线 ----

    /** model import：把所选文档复制进模型目录、登记清单并返回中文结果。 */
    suspend fun importModel(uri: Uri): String =
        modelImport.import(ContentResolverDocument(appContext.contentResolver, uri))

    /** storage grant：持久化目录授权并挂载为沙盒的 ext 根，返回中文结果。 */
    suspend fun mountStorageGrant(uri: Uri): String = withContext(Dispatchers.IO) {
        storageGrant.mount(uri)
    }

    // ---- 内部执行 ----

    private suspend fun executeTurn(text: String) = turnMutex.withLock {
        _state.value = TerminalAgentState.RUNNING
        recorder.beginTurn()
        try {
            driver.drive(loop, text, onToken = tokenRelay::emit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emitNotice("本轮执行失败：${error.message ?: error::class.simpleName}")
        } finally {
            runCatching { safePointCompact() }
            // 异常中断也要收口当前流式行：保留已生成片段，不留下「生成中」形态。
            tokenRelay.endTurn()
            recorder.endTurn()
            _state.value = TerminalAgentState.IDLE
        }
    }

    /** 首启链（幂等）：释放 → SDK init → 清单 → 引擎加载。 */
    private suspend fun initialize() = initMutex.withLock {
        if (initialized) return@withLock
        if (engine.isLoaded.value) {
            initialized = true
            return@withLock
        }
        _state.value = TerminalAgentState.PREPARING
        try {
            // 恢复上次外部目录授权；失败只影响 ext 根挂载，不阻断启动链。
            runCatching { storageGrant.restore() }
            _status.value = "正在释放内置模型资源…"
            val released = releaser.releaseAll()
            _status.value = "正在初始化推理 SDK…"
            sdkClient.initialize().getOrThrow()
            val source = released.firstOrNull()
                ?: File(modelsDir, ModelCatalog.BUILT_IN_FILE_NAME).takeIf { it.isFile }
            if (source == null) {
                fail("未找到可用模型：请先执行 model pull <url> 下载，或用 model import 旁加载。")
                return@withLock
            }
            _status.value = "正在加载模型 ${source.name}…"
            _modelLoad.value = TerminalModelLoadState.Loading(source.name)
            try {
                engine.load(source.absolutePath, LlmLoadConfig(backend = backend)).getOrThrow()
            } finally {
                _modelLoad.value = TerminalModelLoadState.Idle
            }
            loadedModelSource = source.absolutePath
            initialized = true
            _state.value = TerminalAgentState.IDLE
            _status.value = READY_TEXT
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            fail("启动失败：${error.message ?: error::class.simpleName}")
        }
    }

    private suspend fun ensureReady(): Boolean {
        if (engine.isLoaded.value) return true
        start()
        initialize()
        return engine.isLoaded.value
    }

    private suspend fun ensureSdkReady(): Boolean {
        start()
        if (initialized) return true
        initialize()
        return sdkClient.initialize().isSuccess
    }

    /**
     * 安全点压缩（工具步边界 / 轮末）：先处理 /compact 排队，再按实测或估算占用判定自动压缩。
     * 摘要生成属内部调用，期间关闭统计栏刷新，避免污染用户可见指标。
     */
    private suspend fun safePointCompact() {
        if (manualCompactRequested.compareAndSet(true, false)) {
            emitNotice(
                describeCompaction(
                    runCompaction { compactor.compactManually(sessionStore, CONTEXT_BUDGET) },
                ),
            )
            return
        }
        val used = llmGateway.lastPromptTokens ?: compactor.estimateContextTokens(sessionStore)
        if (!compactor.shouldCompact(used, CONTEXT_BUDGET)) return
        val result = runCompaction { compactor.compact(sessionStore, CONTEXT_BUDGET, used) }
        if (result is CompactionResult.Compacted) emitNotice(describeCompaction(result))
    }

    private suspend fun runCompaction(block: suspend () -> CompactionResult): CompactionResult {
        llmGateway.telemetryEnabled = false
        // 摘要生成属内部调用：token 同样不外透，避免压缩内容混入用户可见的流式行。
        tokenRelay.silenced = true
        return try {
            block()
        } finally {
            tokenRelay.silenced = false
            llmGateway.telemetryEnabled = true
        }
    }

    private suspend fun registerPulled(event: ModelPullEvent.Completed) {
        val name = event.modelName.substringAfterLast('/').ifBlank { event.modelName }
        runCatching { catalog.register(RegisteredModel(event.modelName, name, ModelSource.PULL)) }
    }

    private fun emitNotice(text: String) = recorder.notice(text)

    private fun fail(message: String) {
        _status.value = message
        _state.value = TerminalAgentState.UNAVAILABLE
    }

    companion object {

        /** 基础会话标识（首个会话 filesDir/sessions/main.jsonl；/new 之后为 main-2、main-3…）。 */
        const val SESSION_ID = "main"
        const val MODELS_DIR = "models"
        const val SKILLS_DIR = "skills"

        /** 沙盒授权根 id（既可由长路径进入，也可省略）。 */
        const val SANDBOX_ROOT_ID = "files"

        /** 工具步硬顶（设计文档 1.2）。 */
        const val MAX_TOOL_STEPS = 12

        /** 上下文预算：冒烟 nCtx 4096 扣除输出预留后的可用 prompt 预算。 */
        const val CONTEXT_BUDGET = 3072

        const val PREPARING_TEXT = "正在准备运行环境…"
        const val READY_TEXT = "就绪"

        private const val NO_MODEL_HINT = "未找到可用模型"

        /** 生产装配入口（applicationContext 持有，避免泄漏 Activity）。 */
        fun create(context: Context): AgentRuntime = AgentRuntime(context.applicationContext)
    }
}

/** 模型清单条目 → 终端展示条目（序号与量化对齐 UI 模型）。 */
internal fun catalogToLocalEntries(entries: List<CatalogEntry>): List<LocalModelEntry> =
    entries.map { LocalModelEntry(index = it.ordinal, name = it.name, quantization = it.quantization ?: "未知") }

/** 拉取事件 → 终端投影。 */
internal fun ModelPullEvent.toTerminalEvent(): TerminalPullEvent = when (this) {
    is ModelPullEvent.Progress -> TerminalPullEvent.Progress(bytes, totalBytes, percent)
    is ModelPullEvent.Completed -> TerminalPullEvent.Completed(modelName, resolvedPath)
    is ModelPullEvent.Failed -> TerminalPullEvent.Failed(code, message, resumable)
}

/** 请求后端 → 引擎后端（AUTO 交给降级链）。 */
internal fun BackendRequest.toComputeBackend(): ComputeBackend = when (this) {
    BackendRequest.AUTO -> ComputeBackend.AUTO
    BackendRequest.NPU -> ComputeBackend.NPU
    BackendRequest.HYBRID -> ComputeBackend.HYBRID
    BackendRequest.CPU -> ComputeBackend.CPU
}

/** /status 会话段：当前会话 id、节点规模、活动分支与摘要段数。 */
internal fun sessionStatusText(
    sessionId: String,
    nodeCount: Int,
    branchNodes: Int,
    summaries: Int,
): String = "会话：$sessionId，节点 $nodeCount，活动分支 $branchNodes 节点，摘要 $summaries 段"

/** 压缩结果 → 中文回显。 */
internal fun describeCompaction(result: CompactionResult): String = when (result) {
    is CompactionResult.Compacted -> "已压缩上下文：${result.ranges.size} 段，约 " +
        "${result.estimatedTokensBefore} → ${result.estimatedTokensAfter} token。"
    is CompactionResult.Skipped -> when (result.reason) {
        SkipReason.BELOW_THRESHOLD -> "上下文占用未达压缩阈值，本次无需压缩。"
        SkipReason.NOTHING_TO_COMPACT -> "保护窗口之外没有可压缩节点。"
    }
    CompactionResult.DeferredUntilSafePoint -> "轮次进行中：压缩已排队到最近安全点。"
    is CompactionResult.Failed -> "压缩失败：" + result.reason
}

/**
 * 确认门委派：运行时构造早于 UI，确认门由具体前端在组合期注入
 * （终端 [com.thatgfsj.qaa.feature.terminal.TerminalUiGate] / Normal NormalGate）。
 * 未注入时按拒绝处理，绝不静默放行写类工具。
 */
private class DelegatingConfirmationGate : ConfirmationGate {

    @Volatile
    private var delegate: ConfirmationGate? = null

    fun bind(gate: ConfirmationGate) {
        delegate = gate
    }

    override suspend fun awaitConfirmation(call: ToolCall): ConfirmationDecision =
        delegate?.awaitConfirmation(call) ?: ConfirmationDecision.DENY
}
