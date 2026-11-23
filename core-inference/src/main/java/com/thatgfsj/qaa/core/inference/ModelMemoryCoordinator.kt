package com.thatgfsj.qaa.core.inference

import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 内存互斥状态（docs/ARCHITECTURE.md 第 5 节，仅此 6 个）。
 *
 * 生命周期自「App 启动、LLM 加载完成」起接管；冷启动加载期不归本状态机管辖。
 */
enum class CoordinatorState {
    LLM_LOADED,
    LLM_UNLOADING,
    IMAGEGEN_LOADING,
    IMAGEGEN_RUNNING,
    IMAGEGEN_UNLOADING,
    LLM_RELOADING,
}

/**
 * 迁移表的一行（显式实现 6 状态 × 7 条边，与设计 §9.2 一一对应）。
 *
 * @param from 源状态
 * @param to 目标状态；null 表示终止迁移（会话结束 → [*]）
 * @param event 触发事件
 * @param guardPoint 该边的 guard 校验点
 * @param action 该边的动作
 */
data class TransitionEdge(
    val from: CoordinatorState,
    val to: CoordinatorState?,
    val event: String,
    val guardPoint: String,
    val action: String,
)

/**
 * 非法迁移：请求的 (from → to) 不在迁移表内，或 from 与当前状态不一致。
 *
 * 抛出时状态保持不变；调用方据此转译 FAILURE(E_MEMORY_MUTEX)。
 */
class IllegalTransitionException(
    val currentState: CoordinatorState,
    val requestedState: CoordinatorState,
) : IllegalStateException(
    "非法内存互斥迁移：当前 " + currentState + "，请求 " + requestedState,
)

/**
 * 迁移失败退避与内存水位配置。
 *
 * @param maxAttempts 单条边 guard/动作的最大尝试次数（含首次）
 * @param retryInitialDelayMillis 首次重试退避
 * @param retryMaxDelayMillis 退避上限（指数增长封顶）
 * @param memorySafeWatermarkBytes 卸载后可用内存必须达到的安全水位
 * @param imageModelPath 画图模型加载路径
 */
data class CoordinatorConfig(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val retryInitialDelayMillis: Long = 50L,
    val retryMaxDelayMillis: Long = 500L,
    val memorySafeWatermarkBytes: Long = 0L,
    val imageModelPath: String = DEFAULT_IMAGE_MODEL_PATH,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts 必须 >= 1" }
        require(retryInitialDelayMillis > 0L) { "retryInitialDelayMillis 必须为正数" }
        require(retryMaxDelayMillis >= retryInitialDelayMillis) { "retryMaxDelayMillis 不能小于初始退避" }
    }

    /** 第 retryIndex 次重试（从 1 起）的退避毫秒数。 */
    fun backoffMillis(retryIndex: Int): Long {
        var value = retryInitialDelayMillis
        repeat(retryIndex - 1) {
            value = (value * 2).coerceAtMost(retryMaxDelayMillis)
        }
        return value
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_IMAGE_MODEL_PATH = "models/imagegen"
    }
}

/** 退避等待端口（单测注入记录型替身，避免真实等待）。 */
fun interface RetrySleeper {
    suspend fun sleep(millis: Long)

    companion object {
        val Default: RetrySleeper = RetrySleeper { millis -> delay(millis) }
    }
}

/** 单条迁移请求的结局。 */
sealed interface TransitionOutcome {

    data class Committed(
        val from: CoordinatorState,
        val to: CoordinatorState,
        val attempts: Int,
    ) : TransitionOutcome

    /** 目标态 == 当前态，幂等成功。 */
    data class AlreadyInTarget(val state: CoordinatorState) : TransitionOutcome

    /** guard/动作重试耗尽；recovery 记录图外恢复结果。 */
    data class Failed(
        val from: CoordinatorState,
        val requested: CoordinatorState,
        val attempts: Int,
        val reason: String,
        val recovery: RecoveryOutcome,
    ) : TransitionOutcome
}

/** 图外恢复（销毁重建）结果；不属于图内迁移边。 */
sealed interface RecoveryOutcome {

    data class Rebuilt(val state: CoordinatorState) : RecoveryOutcome

    data class Failed(val reason: String) : RecoveryOutcome
}

/** 终止迁移（LLM_LOADED → [*]）的结局。 */
sealed interface TerminationOutcome {
    data object Terminated : TerminationOutcome
    data object AlreadyTerminated : TerminationOutcome
    data class Failed(val reason: String) : TerminationOutcome
}

/** 一次画图互斥周期的结局。 */
sealed interface ImageGenCycleOutcome {

    data class Success(
        val outputPath: String,
        val summary: String,
        val state: CoordinatorState,
    ) : ImageGenCycleOutcome

    data class Failed(
        val from: CoordinatorState,
        val requested: CoordinatorState,
        val attempts: Int,
        val reason: String,
        val recovery: RecoveryOutcome,
    ) : ImageGenCycleOutcome
}

/**
 * LLM / 画图模型内存互斥状态机（docs/design/harness-core-design.md 第 9 章）。
 *
 * 强制约束：LLM 与画图模型严禁同时驻留内存。状态机是这条约束的唯一执行者：
 * 任何不在迁移表内的请求都抛 [IllegalTransitionException] 且状态不变；
 * 每条迁移按「guard → 动作 → 原子提交」执行，失败停留原态并有限退避，
 * 重试耗尽后走图外销毁重建（不新增图内边）。
 *
 * 并发模型：单 actor 串行（互斥锁），外部并发请求按最新态裁决；
 * 状态对外暴露为 [state]。
 */
class ModelMemoryCoordinator(
    private val llm: LlmHandle,
    private val imageGen: ImageGenHandle,
    private val memoryProbe: MemoryProbe,
    private val outputProbe: OutputFileProbe,
    private val config: CoordinatorConfig = CoordinatorConfig(),
    private val sleeper: RetrySleeper = RetrySleeper.Default,
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(CoordinatorState.LLM_LOADED)
    private val _terminated = MutableStateFlow(false)

    /** 当前状态（单 actor 提交后的最新值）。 */
    val state: StateFlow<CoordinatorState> = _state.asStateFlow()

    /** 是否已完成终止迁移（会话结束）。 */
    val isTerminated: StateFlow<Boolean> = _terminated.asStateFlow()

    private var lastGeneratedSummary: String = ""

    /**
     * 请求一次显式迁移。
     *
     * @param target 目标状态
     * @param request guard/动作所需的出图上下文；无关边可为 null
     * @throws IllegalTransitionException (from → target) 不在表内或 from ≠ 当前态
     */
    suspend fun requestTransition(
        target: CoordinatorState,
        request: ImageGenCycleRequest? = null,
    ): TransitionOutcome = mutex.withLock {
        ensureRunning()
        val from = _state.value
        if (target == from) {
            return@withLock TransitionOutcome.AlreadyInTarget(from)
        }
        val edge = findEdge(from, target) ?: run {
            LOGGER.warning("拒绝非法迁移: " + from + " -> " + target)
            throw IllegalTransitionException(from, target)
        }
        when (val result = executeEdge(edge, request)) {
            is EdgeResult.Committed -> TransitionOutcome.Committed(from, target, result.attempts)
            is EdgeResult.Exhausted -> {
                LOGGER.warning("迁移失败: " + from + " -> " + target + "，原因=" + result.reason)
                TransitionOutcome.Failed(
                    from = from,
                    requested = target,
                    attempts = result.attempts,
                    reason = result.reason,
                    recovery = recoverOutsideGraph(),
                )
            }
        }
    }

    /**
     * 同步驱动一次完整画图互斥周期（设计 §9.5）。
     *
     * 依次提交 LLM_LOADED → LLM_UNLOADING → IMAGEGEN_LOADING → IMAGEGEN_RUNNING →
     * IMAGEGEN_UNLOADING → LLM_RELOADING → LLM_LOADED 六条边；任一环节重试耗尽即
     * 走图外恢复并返回失败。
     *
     * @throws IllegalTransitionException 当前态不是 LLM_LOADED（生成互拒）
     */
    suspend fun requestImageGenCycle(
        prompt: String,
        outputPath: String,
        enginePath: String = outputPath,
        confirmation: CycleConfirmation? = CycleConfirmation(),
        summary: String = "",
    ): ImageGenCycleOutcome = mutex.withLock {
        ensureRunning()
        val start = _state.value
        if (start != CoordinatorState.LLM_LOADED) {
            throw IllegalTransitionException(start, CoordinatorState.LLM_UNLOADING)
        }
        val request = ImageGenCycleRequest(prompt, outputPath, enginePath, confirmation, summary)
        lastGeneratedSummary = ""
        for (edge in CYCLE_EDGES) {
            when (val result = executeEdge(edge, request)) {
                is EdgeResult.Committed -> {
                    if (!result.summary.isNullOrEmpty()) lastGeneratedSummary = result.summary
                }
                is EdgeResult.Exhausted -> {
                    val target = requireNotNull(edge.to)
                    LOGGER.warning("出图周期中断: " + edge.from + " -> " + target + "，原因=" + result.reason)
                    return@withLock ImageGenCycleOutcome.Failed(
                        from = edge.from,
                        requested = target,
                        attempts = result.attempts,
                        reason = result.reason,
                        recovery = recoverOutsideGraph(),
                    )
                }
            }
        }
        ImageGenCycleOutcome.Success(
            outputPath = outputPath,
            summary = lastGeneratedSummary,
            state = _state.value,
        )
    }

    /**
     * 终止迁移（LLM_LOADED → [*]）：释放全部资源，不再接受迁移请求。
     *
     * @throws IllegalStateException 当前态不是 LLM_LOADED
     */
    suspend fun terminate(): TerminationOutcome = mutex.withLock {
        if (_terminated.value) {
            return@withLock TerminationOutcome.AlreadyTerminated
        }
        val from = _state.value
        check(from == CoordinatorState.LLM_LOADED) {
            "仅允许从 LLM_LOADED 终止，当前 " + from
        }
        when (val result = executeEdge(TERMINAL_EDGE, null)) {
            is EdgeResult.Committed -> TerminationOutcome.Terminated
            is EdgeResult.Exhausted -> TerminationOutcome.Failed(result.reason)
        }
    }

    // ---- 迁移执行 ----

    private sealed interface EdgeResult {
        data class Committed(val attempts: Int, val summary: String?) : EdgeResult
        data class Exhausted(val attempts: Int, val reason: String) : EdgeResult
    }

    private sealed interface GuardResult {
        data object Passed : GuardResult
        data class Failed(val reason: String) : GuardResult
    }

    private data class ActionOutcome(val summary: String? = null)

    private suspend fun executeEdge(
        edge: TransitionEdge,
        request: ImageGenCycleRequest?,
    ): EdgeResult {
        var lastReason = "未知失败"
        for (attempt in 1..config.maxAttempts) {
            if (attempt > 1) {
                sleeper.sleep(config.backoffMillis(attempt - 1))
            }
            val guard = try {
                checkGuard(edge, request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                GuardResult.Failed("guard 校验异常: " + (e.message ?: e.javaClass.simpleName))
            }
            if (guard is GuardResult.Failed) {
                lastReason = guard.reason
                continue
            }
            val action = runAction(edge, request)
            if (action.isFailure) {
                lastReason = action.exceptionOrNull()?.message ?: "动作失败"
                continue
            }
            val summary = action.getOrThrow().summary
            if (!summary.isNullOrEmpty()) {
                lastGeneratedSummary = summary
            }
            val target = edge.to
            if (target == null) {
                _terminated.value = true
            } else {
                _state.value = target
            }
            return EdgeResult.Committed(attempt, summary)
        }
        return EdgeResult.Exhausted(config.maxAttempts, lastReason)
    }

    /** guard 按设计 §9.2 逐边实现；顺序即校验顺序（测试据此断言）。 */
    private suspend fun checkGuard(
        edge: TransitionEdge,
        request: ImageGenCycleRequest?,
    ): GuardResult = when (edge.to) {
        CoordinatorState.LLM_UNLOADING -> {
            if (request?.confirmation == null) {
                GuardResult.Failed("缺少用户确认记录")
            } else if (!llm.contextPersisted()) {
                GuardResult.Failed("LLM 上下文尚未持久化到会话树")
            } else {
                GuardResult.Passed
            }
        }

        CoordinatorState.IMAGEGEN_LOADING -> {
            val references = llm.referenceCount
            if (references != 0) {
                GuardResult.Failed("LLM 引用未释放（引用计数 " + references + "）")
            } else if (llm.isLoaded) {
                GuardResult.Failed("LLM 仍在内存，禁止加载画图模型")
            } else if (imageGen.isLoaded) {
                GuardResult.Failed("画图引擎已在内存，互斥断言失败")
            } else {
                val available = memoryProbe.availableBytes()
                if (available < config.memorySafeWatermarkBytes) {
                    GuardResult.Failed(
                        "内存回收未达安全水位（可用 " + available +
                            " < " + config.memorySafeWatermarkBytes + "）",
                    )
                } else {
                    GuardResult.Passed
                }
            }
        }

        CoordinatorState.IMAGEGEN_RUNNING -> {
            val references = llm.referenceCount
            if (references != 0 || llm.isLoaded) {
                GuardResult.Failed("LLM 引用计数为 " + references + "，与画图模型同时驻留")
            } else if (!imageGen.isLoaded) {
                GuardResult.Failed("画图引擎加载未完成")
            } else {
                GuardResult.Passed
            }
        }

        CoordinatorState.IMAGEGEN_UNLOADING -> {
            val req = request
            if (req == null) {
                GuardResult.Failed("缺少出图请求参数")
            } else {
                when (val check = outputProbe.inspect(req.outputPath)) {
                    is OutputFileCheck.Valid ->
                        if (check.sizeBytes > 0L) {
                            GuardResult.Passed
                        } else {
                            GuardResult.Failed("输出文件为空: " + req.outputPath)
                        }
                    is OutputFileCheck.Invalid -> GuardResult.Failed(check.reason)
                }
            }
        }

        CoordinatorState.LLM_RELOADING -> {
            val references = imageGen.referenceCount
            if (references != 0) {
                GuardResult.Failed("画图引擎引用未释放（引用计数 " + references + "）")
            } else if (imageGen.isLoaded) {
                GuardResult.Failed("画图引擎仍在内存，禁止重载 LLM")
            } else {
                val available = memoryProbe.availableBytes()
                if (available < config.memorySafeWatermarkBytes) {
                    GuardResult.Failed(
                        "画图模型释放后内存未回收（可用 " + available +
                            " < " + config.memorySafeWatermarkBytes + "）",
                    )
                } else {
                    GuardResult.Passed
                }
            }
        }

        CoordinatorState.LLM_LOADED -> {
            val references = imageGen.referenceCount
            if (references != 0 || imageGen.isLoaded) {
                GuardResult.Failed("画图引擎引用计数为 " + references + "，重载 LLM 前必须释放")
            } else if (!llm.isLoaded) {
                GuardResult.Failed("LLM 重载未完成")
            } else if (!llm.contextRebuilt()) {
                GuardResult.Failed("上下文尚未从会话树重建")
            } else if (!llm.imageResultInjected()) {
                GuardResult.Failed("图片路径与摘要尚未回注")
            } else {
                GuardResult.Passed
            }
        }

        null -> GuardResult.Passed
    }

    /** 动作按目标态实现：guard 通过后执行，成功才提交目标态。 */
    private suspend fun performAction(
        edge: TransitionEdge,
        request: ImageGenCycleRequest?,
    ): ActionOutcome = when (edge.to) {
        CoordinatorState.LLM_UNLOADING -> {
            llm.unload().getOrThrow()
            ActionOutcome()
        }

        CoordinatorState.IMAGEGEN_LOADING -> {
            imageGen.load(config.imageModelPath).getOrThrow()
            ActionOutcome()
        }

        CoordinatorState.IMAGEGEN_RUNNING -> {
            val req = requireNotNull(request) { "缺少出图请求参数" }
            val generated = imageGen.generate(req.prompt, req.enginePath).getOrThrow()
            ActionOutcome(generated.ifBlank { req.summary })
        }

        CoordinatorState.IMAGEGEN_UNLOADING -> {
            imageGen.unload().getOrThrow()
            ActionOutcome()
        }

        CoordinatorState.LLM_RELOADING -> {
            val req = requireNotNull(request) { "缺少出图请求参数" }
            llm.reload().getOrThrow()
            val summary = lastGeneratedSummary.ifBlank { req.summary }
            llm.injectImageResult(req.outputPath, summary).getOrThrow()
            ActionOutcome()
        }

        CoordinatorState.LLM_LOADED -> ActionOutcome()

        null -> {
            llm.destroy()
            imageGen.destroy()
            ActionOutcome()
        }
    }

    private suspend fun runAction(
        edge: TransitionEdge,
        request: ImageGenCycleRequest?,
    ): Result<ActionOutcome> = try {
        Result.success(performAction(edge, request))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 图外恢复：销毁两个模型句柄并重新加载 LLM，复位到初始态；不新增图内边。 */
    private suspend fun recoverOutsideGraph(): RecoveryOutcome {
        LOGGER.warning("guard/动作重试耗尽，执行图外销毁重建")
        return try {
            llm.destroy()
            imageGen.destroy()
            val rebuild = llm.rebuild()
            if (rebuild.isSuccess) {
                lastGeneratedSummary = ""
                _state.value = CoordinatorState.LLM_LOADED
                RecoveryOutcome.Rebuilt(CoordinatorState.LLM_LOADED)
            } else {
                RecoveryOutcome.Failed(rebuild.exceptionOrNull()?.message ?: "LLM 重建失败")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RecoveryOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun ensureRunning() {
        check(!_terminated.value) { "内存互斥协调器已终止" }
    }

    companion object {

        private val LOGGER: Logger = Logger.getLogger(ModelMemoryCoordinator::class.java.name)

        /**
         * 显式迁移表（7 条边）：前 6 条构成画图互斥闭环，第 7 条为 LLM_LOADED → [*]。
         */
        val TRANSITIONS: List<TransitionEdge> = listOf(
            TransitionEdge(
                CoordinatorState.LLM_LOADED,
                CoordinatorState.LLM_UNLOADING,
                "draw_image 获用户 Allow",
                "确认记录在案；LLM 上下文已持久化",
                "卸载 LLM",
            ),
            TransitionEdge(
                CoordinatorState.LLM_UNLOADING,
                CoordinatorState.IMAGEGEN_LOADING,
                "卸载完成",
                "LLM 引用计数 0；MemoryProbe 可用内存达到安全水位",
                "加载画图模型",
            ),
            TransitionEdge(
                CoordinatorState.IMAGEGEN_LOADING,
                CoordinatorState.IMAGEGEN_RUNNING,
                "load 完成",
                "LLM 不在内存；画图引擎加载成功",
                "出图",
            ),
            TransitionEdge(
                CoordinatorState.IMAGEGEN_RUNNING,
                CoordinatorState.IMAGEGEN_UNLOADING,
                "出图完成",
                "输出文件存在、非空且在沙盒授权根内",
                "卸载画图模型",
            ),
            TransitionEdge(
                CoordinatorState.IMAGEGEN_UNLOADING,
                CoordinatorState.LLM_RELOADING,
                "释放完成",
                "画图引擎引用计数 0；MemoryProbe 可用内存达到安全水位",
                "重载 LLM 并回注图片结果",
            ),
            TransitionEdge(
                CoordinatorState.LLM_RELOADING,
                CoordinatorState.LLM_LOADED,
                "重载完成",
                "画图引擎不在内存；上下文已重建；图片路径与摘要已回注",
                "提交周期完成",
            ),
            TransitionEdge(
                CoordinatorState.LLM_LOADED,
                null,
                "会话结束",
                "无",
                "释放全部资源",
            ),
        )

        /** 闭环六边（按迁移顺序）。 */
        val CYCLE_EDGES: List<TransitionEdge> = TRANSITIONS.filter { it.to != null }

        private val TERMINAL_EDGE: TransitionEdge = TRANSITIONS.first { it.to == null }

        /** 从 from 出发的合法目标态集合（不含终止边）。 */
        fun allowedTargets(from: CoordinatorState): Set<CoordinatorState> =
            TRANSITIONS.filter { it.from == from && it.to != null }.mapNotNull { it.to }.toSet()

        private fun findEdge(from: CoordinatorState, to: CoordinatorState): TransitionEdge? =
            TRANSITIONS.firstOrNull { it.from == from && it.to == to }
    }
}
