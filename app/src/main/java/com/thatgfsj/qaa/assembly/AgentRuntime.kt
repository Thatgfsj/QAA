package com.thatgfsj.qaa.assembly

import android.content.Context
import android.net.Uri
import com.thatgfsj.qaa.core.harness.AgentConfig
import com.thatgfsj.qaa.core.harness.AgentLoop
import com.thatgfsj.qaa.core.harness.CompactionResult
import com.thatgfsj.qaa.core.harness.CompactionTrigger
import com.thatgfsj.qaa.core.harness.ConfirmationDecision
import com.thatgfsj.qaa.core.harness.ConfirmationGate
import com.thatgfsj.qaa.core.harness.ContextCompactor
import com.thatgfsj.qaa.core.harness.ContextWindow
import com.thatgfsj.qaa.core.harness.ForkResult
import com.thatgfsj.qaa.core.harness.HeuristicTokenCounter
import com.thatgfsj.qaa.core.harness.JsonlTurnRecorder
import com.thatgfsj.qaa.core.harness.LoraTraceExporter
import com.thatgfsj.qaa.core.harness.PromptAssembler
import com.thatgfsj.qaa.core.harness.SessionRole
import com.thatgfsj.qaa.core.harness.SkillRegistry
import com.thatgfsj.qaa.core.harness.SteerQueue
import com.thatgfsj.qaa.core.harness.SteerTurnDriver
import com.thatgfsj.qaa.core.harness.ToolCall
import com.thatgfsj.qaa.core.harness.TreeSessionStore
import com.thatgfsj.qaa.core.harness.summarizeToolCall
import com.thatgfsj.qaa.core.inference.AndroidAssetSource
import com.thatgfsj.qaa.core.inference.AssetModelReleaser
import com.thatgfsj.qaa.core.inference.ComputeBackend
import com.thatgfsj.qaa.core.inference.ContentResolverDocument
import com.thatgfsj.qaa.core.inference.DefaultGenieXModelGateway
import com.thatgfsj.qaa.core.inference.GenieXLlmEngine
import com.thatgfsj.qaa.core.inference.GenieXLlmSessionFactory
import com.thatgfsj.qaa.core.inference.GenieXModelResolver
import com.thatgfsj.qaa.core.inference.GenieXSdkClient
import com.thatgfsj.qaa.core.inference.GgufKvEstimator
import com.thatgfsj.qaa.core.inference.KvEstimate
import com.thatgfsj.qaa.core.inference.LogcatBackendProbe
import com.thatgfsj.qaa.core.inference.LlmEnginePort
import com.thatgfsj.qaa.core.inference.LlmLoadConfig
import com.thatgfsj.qaa.core.inference.MemoryProbe
import com.thatgfsj.qaa.core.inference.ModelCatalog
import com.thatgfsj.qaa.core.inference.ModelDeleteResult
import com.thatgfsj.qaa.core.inference.ModelDeleter
import com.thatgfsj.qaa.core.inference.ModelMemoryCoordinator
import com.thatgfsj.qaa.core.inference.ModelPullClient
import com.thatgfsj.qaa.core.inference.ModelPullEvent
import com.thatgfsj.qaa.core.inference.ModelSource
import com.thatgfsj.qaa.core.inference.RegisteredModel
import com.thatgfsj.qaa.core.tools.DrawImageTool
import com.thatgfsj.qaa.core.tools.LocalSandboxFs
import com.thatgfsj.qaa.core.tools.SandboxRoot
import com.thatgfsj.qaa.core.tools.ToolLimits
import com.thatgfsj.qaa.data.ContextLengthStore
import com.thatgfsj.qaa.data.SystemPromptStore
import com.thatgfsj.qaa.data.dataStoreContextLengthStore
import com.thatgfsj.qaa.data.dataStoreSystemPromptStore
import com.thatgfsj.qaa.feature.normal.chat.ChatMetrics
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
class AgentRuntime private constructor(
    private val appContext: Context,
    private val contextLengthStore: ContextLengthStore,
    private val systemPromptStore: SystemPromptStore,
) :
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
    private val llmGateway = LlmGatewayAdapter(engine) { contextLengthSetting }
    private val releaser = AssetModelReleaser(AndroidAssetSource(appContext), modelsDir)
    private val catalog = ModelCatalog(modelsDir, DefaultGenieXModelGateway)

    /** 模型删除链路（#111）：终端 model delete 与 Normal 模型页共用同一实现。 */
    private val deleter = ModelDeleter(modelsDir, catalog, DefaultGenieXModelGateway)
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
    private val tokenCounter = HeuristicTokenCounter()
    private val promptAssembler = PromptAssembler(skillsSection = skills.describeAll())

    /** 系统提示词生效策略（#105）：每轮组装前从 DataStore 同步，终端 /system 与设置页共用。 */
    private val systemPromptSwitcher = SystemPromptSwitcher(systemPromptStore) {
        promptAssembler.updateSystemSection(it)
    }

    /** PromptAssembler 稳定前缀（system + skills 两段）的 token 数：提示词变更后按当前系统段重算（#105）。 */
    private val stablePrefixTokens: Int
        get() = tokenCounter.countMessages(promptAssembler.assemble(emptyList()))
    private val compactor = ContextCompactor(engine = llmGateway, counter = tokenCounter)

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
        llm = LlmEngineMemoryHandle(
            engine = engine,
            modelSource = { loadedModelSource },
            config = { LlmLoadConfig(backend = backend, nCtx = loadedContextTokens) },
        ),
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
        assembler = promptAssembler,
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

    /** 用户设置的上下文长度（持久化值；终端 /ctx 或 Normal 设置页写入，首启从 DataStore 读取）。 */
    @Volatile
    private var contextLengthSetting: Int = ContextWindow.DEFAULT

    /** 引擎实际载入的 nCtx：安全网回退后可能小于 [contextLengthSetting]。 */
    @Volatile
    private var loadedContextTokens: Int = ContextWindow.DEFAULT

    /** 最近一次安全网回退回执（非空表示上次载入发生过 4096 回退）。 */
    @Volatile
    private var lastContextFallbackText: String? = null

    /** /ctx 回执用的设备内存探针（ActivityManager；JVM 单测可注入 fake）。 */
    private val deviceMemoryProbe: DeviceMemoryProbe = AndroidDeviceMemoryProbe(appContext)

    /** GGUF 头 KV 估算缓存：key 为模型路径，避免每次 /ctx 重复解析 GB 级文件头。 */
    @Volatile
    private var kvEstimateCache: Pair<String, KvEstimate>? = null

    private val _state = MutableStateFlow(TerminalAgentState.PREPARING)
    private val _status = MutableStateFlow(PREPARING_TEXT)
    private val _modelLoad = MutableStateFlow<TerminalModelLoadState>(TerminalModelLoadState.Idle)

    /** #104 载入闸门：冷启动与切档载入串行化，轮次入口在载入期排队而不是被拒绝。 */
    private val modelLoadGate = ModelLoadGate()
    private val backendSwitcher = BackendSwitcher(engine) { _status.value = it }
    private val contextLengthSwitcher = ContextLengthSwitcher(engine) { _status.value = it }
    private val contextLoadFallback = ContextLoadFallback(engine) { requested, failure ->
        val reason = failure.message ?: failure::class.simpleName.orEmpty()
        AppLog.contextFallback(requested, ContextLoadFallback.FALLBACK_N_CTX, reason)
        val text = describeContextFallback(requested, ContextLoadFallback.FALLBACK_N_CTX)
        lastContextFallbackText = text
        _status.value = text
        emitNotice(text)
    }
    private val preflightGuard = ContextPreflightGuard(tokenCounter)

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
        // #104：载入期先把指令排队（回执明确告知不会丢），载入结束后本行继续执行。
        if (modelLoadGate.isLoadInProgress) {
            emitNotice(queuedPromptNotice(loadingModelName()))
            modelLoadGate.awaitIdle()
        }
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
        syncContextLength()
        val entry = catalog.resolveByName(token)
            ?: return "未找到本地模型：$token。输入 model 查看清单（名称或序号，内置模型为 0 号）。"
        val path = entry.path ?: return "模型文件缺失：${entry.name}；请重新下载或导入。"
        if (engine.isLoaded.value &&
            loadedModelSource == path &&
            loadedContextTokens == contextLengthSetting
        ) {
            return "模型已在运行：${entry.name}"
        }
        // 切换耗时由运行链状态流广播：终端占位行据此出现 / 移除，UI 不做耗时估算。
        AppLog.modelLoadStart(entry.name)
        _modelLoad.value = TerminalModelLoadState.Loading(entry.name)
        val outcome = try {
            modelLoadGate.withLoad { loadWithContextFallback(path) }
        } finally {
            _modelLoad.value = TerminalModelLoadState.Idle
        }
        return when (outcome) {
            is ContextLoadOutcome.Loaded -> {
                loadedModelSource = path
                loadedContextTokens = outcome.nCtx
                if (outcome.nCtx == contextLengthSetting) lastContextFallbackText = null
                _status.value = readyText()
                AppLog.modelLoaded(entry.name, "后端 ${backend.displayName()}，nCtx ${outcome.nCtx}")
                "模型已载入：${entry.name}（${entry.quantization ?: "未知量化"}）" +
                    fallbackSuffix(outcome.nCtx)
            }
            is ContextLoadOutcome.Failed -> {
                val reason = outcome.cause.message ?: outcome.cause::class.simpleName.orEmpty()
                AppLog.modelLoadFailed(entry.name, reason)
                "模型加载失败：$reason"
            }
        }
    }

    override suspend fun lockBackend(request: BackendRequest): String {
        // 重载会中断生成：引擎已加载且轮次进行中时拒绝，避免卸载运行中的会话。
        if (engine.isLoaded.value && turnMutex.isLocked) {
            return "轮次进行中：暂不能切换后端，请等本轮结束后重试。"
        }
        val switched = backendSwitcher.apply(
            modelSource = loadedModelSource,
            previous = backend,
            request = request,
            nCtx = effectiveContextTokens(),
        )
        // 降级链命中（含回滚）单独按 WARN 记录，便于 /logs 里一眼定位。
        AppLog.backend(
            request = request.displayName,
            result = switched.message,
            fallback = switched is BackendSwitchResult.RolledBack,
        )
        return when (val result = switched) {
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

    // ---- 上下文长度（#106） ----

    override suspend fun contextLength(): String {
        val engineTokens = effectiveContextTokens()
        val modelSource = loadedModelSource
        val kv = withContext(Dispatchers.IO) { kvEstimateFor(modelSource) }
        val memory = runCatching { deviceMemoryProbe.read() }.getOrNull()
        return describeContextReceipt(
            setting = contextLengthSetting,
            engine = engineTokens,
            budget = currentBudget(),
            kv = kv,
            kvNctx = engineTokens,
            memory = memory,
            modelLoaded = modelSource != null,
            fallbackNotice = lastContextFallbackText,
        )
    }

    /** GGUF 头 KV 估算（按模型路径缓存）：文件缺失 / 解析失败回退 144 KiB/token 经验值。 */
    private fun kvEstimateFor(modelSource: String?): KvEstimate {
        if (modelSource == null) return GgufKvEstimator.estimate(null)
        kvEstimateCache?.let { cached ->
            if (cached.first == modelSource) return cached.second
        }
        val estimate = GgufKvEstimator.estimate(File(modelSource))
        kvEstimateCache = modelSource to estimate
        return estimate
    }

    override suspend fun setContextLength(tokens: Int): String {
        if (!ContextWindow.isAllowed(tokens)) {
            return "无效的上下文长度：$tokens（范围 ${ContextWindow.MIN_N_CTX}~${ContextWindow.MAX_N_CTX}；" +
                "支持写法 ${ContextWindow.INPUT_EXAMPLES}）。"
        }
        if (turnMutex.isLocked) {
            return "轮次进行中：暂不能切换上下文长度，请等本轮结束后重试。"
        }
        val previous = contextLengthSetting
        val result = contextLengthSwitcher.set(
            modelSource = loadedModelSource,
            current = previous,
            requested = tokens,
            backend = backend,
        )
        when (result) {
            is ContextLengthChangeResult.Applied -> {
                contextLengthSetting = tokens
                loadedContextTokens = tokens
                // 用户已把档位固定到实际载入值：安全网提示不再适用。
                lastContextFallbackText = null
                persistContextLength(tokens)
            }
            // 只保存不重载（>8192 / 引擎未加载 / 值未变）：设置值立即更新，载入时生效。
            is ContextLengthChangeResult.Deferred -> {
                contextLengthSetting = tokens
                persistContextLength(tokens)
            }
            // 回滚：旧值仍生效，不落盘。
            is ContextLengthChangeResult.RolledBack -> Unit
        }
        AppLog.contextChanged(
            from = previous,
            to = tokens,
            applied = result is ContextLengthChangeResult.Applied,
        )
        return result.message
    }

    /** 写 DataStore；失败只记日志，不影响本次内存生效。 */
    private suspend fun persistContextLength(tokens: Int) {
        try {
            contextLengthStore.setTokens(tokens)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.error(ERROR_CONTEXT, error.message ?: error::class.simpleName.orEmpty())
        }
    }

    // ---- 系统提示词（#105） ----

    override suspend fun systemPrompt(): String = systemPromptSwitcher.describe()

    override suspend fun setSystemPrompt(text: String?): String = systemPromptSwitcher.applyChange(text)

    override suspend fun newSession(): String {
        if (turnMutex.isLocked) return "轮次进行中：暂不能新建会话，请等本轮结束后重试。"
        val previous = sessionStore
        val store = sessionSwitcher.openNext()
        recorder.rebind(JsonlTurnRecorder(store))
        sessionStore = store
        toolGateway.rebindSession(store.sessionId)
        previous.close()
        AppLog.sessionOpened(store.sessionId)
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
            runCompaction {
                compactor.compactManually(
                    sessionStore,
                    currentBudget(),
                    prefixTokens = stablePrefixTokens,
                )
            },
        ) + watermarkSuffix()
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

    override suspend fun exportTrace(path: String?): String {
        // 导出快照以当前生效提示词为准（#105）：设置页可能刚改过，先同步再组装轨迹。
        systemPromptSwitcher.sync()
        val branch = sessionStore.activeBranch()
        val stats = traceBranchStats(branch)
        val target = traceExportPath(sessionStore.sessionId, path)
        val line = LoraTraceExporter.export(
            branch = branch,
            options = traceExportOptions(sessionStore.sessionId, systemPromptSwitcher.current),
        )
        if (line != null) {
            try {
                sandbox.writeText(target, line + "\n")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.error(ERROR_EXPORT, error.message ?: error::class.simpleName.orEmpty())
                return "导出失败：" + (error.message ?: error::class.simpleName) + "（目标：$target）"
            }
        }
        AppLog.exportTrace(target, if (line == null) 0 else 1)
        return describeTraceExport(target, stats, if (line == null) 0 else 1)
    }

    /**
     * /logs [行数]：把应用内环形日志最近 N 行导出到沙盒 exports/logs/log-<时间戳>.txt。
     *
     * 落点与写路径复用 #90 的沙盒写通道（自动建目录）；
     * 行数超过缓冲容量时按实际行数导出，回执里给出真实行数与缓冲占用。
     */
    override suspend fun exportLogs(lines: Int?): String {
        val requested = lines ?: APP_LOG_DEFAULT_LINES
        val records = AppRingLog.global.snapshot(requested)
        val now = System.currentTimeMillis()
        val target = appLogExportPath(now)
        return try {
            sandbox.writeText(target, formatAppLogExport(records, now, AppRingLog.global.size))
            AppLog.logsExported(target, records.size, AppRingLog.global.size)
            describeLogExport(target, records.size, AppRingLog.global.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.error(ERROR_LOG_EXPORT, error.message ?: error::class.simpleName.orEmpty())
            "日志导出失败：${error.message ?: error::class.simpleName}（目标：$target）"
        }
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
            appendLine(
                describeContextLength(
                    setting = contextLengthSetting,
                    engine = effectiveContextTokens(),
                    budget = currentBudget(),
                ),
            )
            appendLine(
                describeContextUsage(
                    estimatedTokens = compactor.estimateContextTokens(
                        store = sessionStore,
                        prefixTokens = stablePrefixTokens,
                    ),
                    contextBudget = currentBudget(),
                    lastPromptTokens = llmGateway.lastPromptTokens,
                ),
            )
            lastContextFallbackText?.let { appendLine(it) }
            append(
                "上次 prompt ${llmGateway.lastPromptTokens ?: 0} token；" +
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

    /** Normal 会话页指标条：与终端统计栏同源，只做形状映射。 */
    override val chatMetrics: Flow<ChatMetrics> = metrics.map { snapshot ->
        ChatMetrics(
            ttftMs = snapshot.ttftMs,
            tokensPerSecond = snapshot.tokensPerSecond,
            tokensUsed = snapshot.tokensUsed,
            contextTokens = snapshot.contextTokens,
        )
    }

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

    // ---- 模型删除（#111）：终端与 Normal 共用受保护策略与三处同步 ----

    /** 终端 `model delete <序号|名称> --force`：名称用于序号失效时兜底解析。 */
    override suspend fun deleteModel(ordinal: Int, name: String): String =
        deleteModelShared(ordinal, name)

    /** Normal 模型页删除：只透传序号（页面按当前清单展示序号）。 */
    override suspend fun deleteModel(ordinal: Int): String = deleteModelShared(ordinal, null)

    /**
     * 受保护判断（内置 0 号 / 当前已加载 / 轮次进行中）→ [ModelDeleter] 三处同步
     * （SDK 缓存 + 落盘文件 + 清单登记）→ 清理下载记录 → 中文回执。
     *
     * 删除成功后下载记录一并清理，避免重启恢复出「已删模型的遗留任务」；
     * 清理失败不影响删除结果（记录只是下载态投影）。
     */
    private suspend fun deleteModelShared(ordinal: Int, name: String?): String {
        ensureSdkReady()
        val entry = catalog.resolve(ordinal)
            ?: name?.let { catalog.resolveByName(it) }
            ?: return "未找到本地模型：${name ?: ordinal}。输入 model 查看清单。"
        if (entry.ordinal == 0 || entry.source == ModelSource.BUILT_IN) {
            return ModelDeleter.BUILT_IN_REFUSED
        }
        if (turnMutex.isLocked) {
            return "轮次进行中：暂不能删除模型，请等本轮结束后重试。"
        }
        if (engine.isLoaded.value && loadedModelSource != null && entry.path == loadedModelSource) {
            return "当前模型已加载：请先切换模型（chat <序号>）或卸载后再删除。"
        }
        val result = deleter.delete(entry.ordinal)
        return when (result) {
            is ModelDeleteResult.Deleted -> {
                runCatching { downloadTracker.discardByTarget(entry.name) }
                AppLog.modelDeleted(entry.name, result.freedBytes, result.remaining)
                "已删除模型：${entry.name}（释放约 ${formatFreedBytes(result.freedBytes)}），" +
                    "剩余清单 ${result.remaining} 项。"
            }
            is ModelDeleteResult.Refused -> result.reason
            is ModelDeleteResult.NotFound -> "未找到本地模型：${result.token}。"
            is ModelDeleteResult.Failed -> {
                AppLog.modelDeleteFailed(entry.name, result.reason)
                result.reason
            }
        }
    }

    private fun formatFreedBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 ->
            "%.1f GB".format(java.util.Locale.ROOT, bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
        else -> "$bytes B"
    }

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
        // 每轮组装前同步系统提示词（#105）：终端 /system 或 Normal 设置页的修改在下一轮生效。
        systemPromptSwitcher.sync()
        try {
            // 每轮首次生成前做上下文预检（#106）：越界先压缩，压缩后仍越界则拒绝生成，
            // 避免把注定超窗的 prompt 交给引擎（真机 3951/4096 事故的直接防线）。
            if (!preflightAdmits(text)) {
                emitNotice(PREFLIGHT_INSUFFICIENT_TEXT)
                // 已知仍越界：finally 里的 safePointCompact 会再尝试一次压缩，属预期内兜底
                // （#106 决策：不为此消除轮末安全点压缩，保持收尾语义一致）。
                return@withLock
            }
            driver.drive(loop, text, onToken = tokenRelay::emit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.error(ERROR_TURN, error.message ?: error::class.simpleName.orEmpty())
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
        // 首启即按持久化设置载入（#106）：默认 32768，无迁移特例。
        syncContextLength()
        if (engine.isLoaded.value) {
            loadedContextTokens = contextLengthSetting
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
            sdkClient.initialize()
                .onSuccess { AppLog.sdkInit(true) }
                .onFailure { AppLog.sdkInit(false, it.message ?: it::class.simpleName.orEmpty()) }
                .getOrThrow()
            val source = released.firstOrNull()
                ?: File(modelsDir, ModelCatalog.BUILT_IN_FILE_NAME).takeIf { it.isFile }
            if (source == null) {
                fail("未找到可用模型：请先执行 model pull <url> 下载，或用 model import 旁加载。")
                return@withLock
            }
            _status.value = "正在加载模型 ${source.name}…"
            AppLog.modelLoadStart(source.name)
            _modelLoad.value = TerminalModelLoadState.Loading(source.name)
            val outcome = try {
                modelLoadGate.withLoad { loadWithContextFallback(source.absolutePath) }
            } finally {
                _modelLoad.value = TerminalModelLoadState.Idle
            }
            when (outcome) {
                is ContextLoadOutcome.Loaded -> {
                    loadedModelSource = source.absolutePath
                    loadedContextTokens = outcome.nCtx
                    if (outcome.nCtx == contextLengthSetting) lastContextFallbackText = null
                    AppLog.modelLoaded(
                        source.name,
                        "后端 ${backend.displayName()}，nCtx ${outcome.nCtx}",
                    )
                    initialized = true
                    _state.value = TerminalAgentState.IDLE
                    _status.value = readyText()
                }
                is ContextLoadOutcome.Failed -> {
                    val reason = outcome.cause.message
                        ?: outcome.cause::class.simpleName.orEmpty()
                    AppLog.modelLoadFailed(source.name, reason)
                    fail("启动失败：$reason")
                    return@withLock
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.error(ERROR_INIT, error.message ?: error::class.simpleName.orEmpty())
            fail("启动失败：${error.message ?: error::class.simpleName}")
        }
    }

    /** 当前载入中的模型名（#104 排队回执用；无载入时为 null）。 */
    private fun loadingModelName(): String? = (_modelLoad.value as? TerminalModelLoadState.Loading)?.name

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

    /** 每轮首次生成前的上下文预检（#106）：判定 via [preflightGuard]。 */
    private suspend fun preflightAdmits(text: String): Boolean {
        val nCtx = effectiveContextTokens()
        val budget = ContextWindow.promptBudget(nCtx)
        return preflightGuard.admit(
            text = text,
            lastPromptTokens = llmGateway.lastPromptTokens,
            estimatedHistory = {
                compactor.estimateContextTokens(sessionStore, prefixTokens = stablePrefixTokens)
            },
            nCtx = nCtx,
            compact = { used ->
                runCompaction {
                    compactor.compact(
                        store = sessionStore,
                        contextBudget = budget,
                        usedTokens = used,
                        prefixTokens = stablePrefixTokens,
                    )
                }
            },
        )
    }

    /** 从 DataStore 同步设置值（首启 / 每次载入模型前）：非法值回退默认值。 */
    private suspend fun syncContextLength() {
        val stored = runCatching { contextLengthStore.tokens.first() }.getOrNull()
        contextLengthSetting = if (stored != null && ContextWindow.isAllowed(stored)) {
            stored
        } else {
            ContextWindow.DEFAULT
        }
        if (!engine.isLoaded.value) loadedContextTokens = contextLengthSetting
    }

    /** 引擎实际生效的 nCtx：已加载时取载入值（可能被安全网回退），否则取设置值。 */
    private fun effectiveContextTokens(): Int =
        if (engine.isLoaded.value) loadedContextTokens else contextLengthSetting

    /** 当前提示预算（压缩阈值与 /status 回显共用）。 */
    private fun currentBudget(): Int = ContextWindow.promptBudget(effectiveContextTokens())

    /** 统一载入配置（#106）：后端锁定值 + 当前上下文长度设置。 */
    private fun loadConfig(backendOverride: ComputeBackend = backend): LlmLoadConfig =
        LlmLoadConfig(backend = backendOverride, nCtx = contextLengthSetting)

    /** 安全网（#106）：按配置 nCtx 载入失败时记录 AppLog 并自动回退 4096 重试一次。 */
    private suspend fun loadWithContextFallback(modelSource: String): ContextLoadOutcome =
        contextLoadFallback.load(modelSource, loadConfig())

    /** 就绪文案：发生过安全网回退时附带说明，避免用户误以为设置档位已生效。 */
    private fun readyText(): String =
        if (lastContextFallbackText != null) {
            "$READY_TEXT（上下文已回退 $loadedContextTokens，终端 /ctx 可改）"
        } else {
            READY_TEXT
        }

    /** 载入回执附言：实际 nCtx 与设置值不一致时说明回退原因。 */
    private fun fallbackSuffix(nCtx: Int): String =
        if (nCtx == contextLengthSetting) {
            ""
        } else {
            "；" + (lastContextFallbackText ?: describeContextFallback(contextLengthSetting, nCtx))
        }

    /**
     * 安全点压缩（工具步边界 / 轮末）：先处理 /compact 排队，再按实测或估算占用判定自动压缩。
     * 摘要生成属内部调用，期间关闭统计栏刷新，避免污染用户可见指标。
     */
    private suspend fun safePointCompact() {
        val budget = currentBudget()
        if (manualCompactRequested.compareAndSet(true, false)) {
            emitNotice(
                describeCompaction(
                    runCompaction {
                        compactor.compactManually(
                            sessionStore,
                            budget,
                            prefixTokens = stablePrefixTokens,
                        )
                    },
                ) + watermarkSuffix(),
            )
            return
        }
        val used = llmGateway.lastPromptTokens
            ?: compactor.estimateContextTokens(sessionStore, prefixTokens = stablePrefixTokens)
        if (!compactor.shouldCompact(used, budget)) return
        val result = runCompaction {
            compactor.compact(
                store = sessionStore,
                contextBudget = budget,
                usedTokens = used,
                prefixTokens = stablePrefixTokens,
            )
        }
        if (result is CompactionResult.Compacted) {
            emitNotice(describeCompaction(result) + watermarkSuffix(result))
        }
    }

    /** 压缩回执附言：显示压缩后水位是否达到 0.5·预算 的目标（#106）。 */
    private fun watermarkSuffix(result: CompactionResult? = null): String {
        val compacted = result as? CompactionResult.Compacted
            ?: return "；目标水位 0.5·预算（/status 可查看当前预算）。"
        return if (compacted.watermarkSatisfied) "（已达目标水位）" else "（仍高于目标水位，可再 /compact）"
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

        /** 上下文不足时的统一提示（#106 preflight 拒绝生成）。 */
        const val PREFLIGHT_INSUFFICIENT_TEXT = "上下文不足：请 /new 或 /compact"

        const val PREPARING_TEXT = "正在准备运行环境…"
        const val READY_TEXT = "就绪"

        /** 运行链错误短码（写入应用内日志，便于 /logs 里检索）。 */
        const val ERROR_INIT = "E_INIT"
        const val ERROR_TURN = "E_TURN"
        const val ERROR_EXPORT = "E_EXPORT"
        const val ERROR_LOG_EXPORT = "E_LOG_EXPORT"
        const val ERROR_CONTEXT = "E_CONTEXT"

        private const val NO_MODEL_HINT = "未找到可用模型"

        /**
         * 生产装配入口（applicationContext 持有，避免泄漏 Activity）。
         *
         * 内置技能先释放到 filesDir/skills（幂等、仅数 KB）：[SkillRegistry] 构造时即可
         * 扫描到，断网首启也能在 /skills 与 AGENT 描述段看到内置技能。
         */
        fun create(context: Context): AgentRuntime {
            val appContext = context.applicationContext
            SkillsReleaser(
                source = AndroidSkillsAssetSource(appContext.assets),
                targetDir = File(appContext.filesDir, SKILLS_DIR),
            ).release()
            return AgentRuntime(
                appContext,
                dataStoreContextLengthStore(appContext),
                dataStoreSystemPromptStore(appContext),
            )
        }
    }
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

    override suspend fun awaitConfirmation(call: ToolCall): ConfirmationDecision {
        val decision = delegate?.awaitConfirmation(call) ?: ConfirmationDecision.DENY
        AppLog.confirmDecision(
            description = summarizeToolCall(call),
            allowed = decision == ConfirmationDecision.ALLOW,
        )
        return decision
    }
}
