package com.thatgfsj.qaa.core.inference

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * 脚本化假引擎（JVM 可跑）：供 UI 联调与降级链/指标/取消单测。
 *
 * - [FakeScript.tokens] 决定 token 流，[FakeScript.tokenDelayMillis] 注入逐 token 延迟；
 * - [FakeScript.loadFailure] 让 load 失败，[FakeScript.failureTokenIndex] 让生成中途
 *   以 [LlmStreamEvent.Failed] 结束；
 * - 命中档位按 [FakeScript.tier] 上报；锁定 [LlmLoadConfig.backend] 时按锁定值上报
 *   （模拟"锁定档不回退"）。
 */
class FakeLlmEngine(
    private val script: FakeScript = FakeScript(),
) : LlmEnginePort {

    private val _isLoaded = MutableStateFlow(false)
    private val _lockedBackend = MutableStateFlow(ComputeBackend.AUTO)
    private val _activeBackend = MutableStateFlow<ActiveBackend?>(null)

    override val lockedBackend: StateFlow<ComputeBackend> = _lockedBackend.asStateFlow()

    override val activeBackend: StateFlow<ActiveBackend?> = _activeBackend.asStateFlow()

    override val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    /** 累计 load 次数。 */
    val loadCount = AtomicInteger()

    /** 累计 cancel 次数。 */
    val cancelCount = AtomicInteger()

    /** 最近一次生成的请求。 */
    @Volatile
    var lastRequest: LlmRequest? = null
        private set

    override suspend fun load(modelSource: String, config: LlmLoadConfig): Result<Unit> {
        loadCount.incrementAndGet()
        _lockedBackend.value = config.backend
        script.loadFailure?.let { failure ->
            _isLoaded.value = false
            _activeBackend.value = null
            return Result.failure(failure)
        }
        _activeBackend.value = script.tierFor(config.backend)
        _isLoaded.value = true
        return Result.success(Unit)
    }

    override suspend fun unload() {
        _isLoaded.value = false
        _activeBackend.value = null
        _lockedBackend.value = ComputeBackend.AUTO
    }

    override fun generate(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        check(_isLoaded.value) { "FakeLlmEngine 尚未加载模型" }
        lastRequest = request
        script.tokens.forEachIndexed { index, text ->
            if (script.tokenDelayMillis > 0L) {
                delay(script.tokenDelayMillis)
            }
            if (script.failureTokenIndex == index) {
                emit(LlmStreamEvent.Failed(script.failureCause))
                return@flow
            }
            emit(LlmStreamEvent.Token(text))
        }
        val completed = script.metrics.copy(
            backend = script.metrics.backend ?: _activeBackend.value,
        )
        emit(LlmStreamEvent.Completed(completed))
    }

    override suspend fun cancel() {
        cancelCount.incrementAndGet()
    }
}

/** [FakeLlmEngine] 的脚本参数。 */
data class FakeScript(
    val tokens: List<String> = listOf("你", "好", "，", "世界"),
    val tokenDelayMillis: Long = 0L,
    val loadFailure: Throwable? = null,
    val failureTokenIndex: Int? = null,
    val failureCause: Throwable = RuntimeException("fake generation failure"),
    val tier: ActiveBackend = ActiveBackend.CPU,
    val metrics: InferenceMetrics = defaultFakeMetrics(),
) {

    /** 锁定后端时按锁定档位上报，AUTO 用 [tier]。 */
    fun tierFor(backend: ComputeBackend): ActiveBackend = when (backend) {
        ComputeBackend.AUTO -> tier
        ComputeBackend.NPU -> ActiveBackend.NPU
        ComputeBackend.HYBRID -> ActiveBackend.HYBRID
        ComputeBackend.CPU -> ActiveBackend.CPU
    }
}

/** 单档假会话：可注入 token 流、延迟与中途失败，并记录取消/关闭次数。 */
class FakeLlmSession(
    private val tokens: List<String> = listOf("a", "b", "c"),
    private val tokenDelayMillis: Long = 0L,
    private val metrics: InferenceMetrics = defaultFakeMetrics(),
    private val failureTokenIndex: Int? = null,
    private val failureCause: Throwable = RuntimeException("fake session failure"),
) : LlmSession {

    val cancelCount = AtomicInteger()
    val closeCount = AtomicInteger()

    override fun generate(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        tokens.forEachIndexed { index, text ->
            if (tokenDelayMillis > 0L) {
                delay(tokenDelayMillis)
            }
            if (failureTokenIndex == index) {
                emit(LlmStreamEvent.Failed(failureCause))
                return@flow
            }
            emit(LlmStreamEvent.Token(text))
        }
        emit(LlmStreamEvent.Completed(metrics))
    }

    override suspend fun cancel() {
        cancelCount.incrementAndGet()
    }

    override suspend fun close() {
        closeCount.incrementAndGet()
    }
}

/**
 * 脚本化 create 工厂：按档位注入失败，驱动降级链单测。
 *
 * @param failures 指定档位 create 失败；未列出的档位成功
 */
class FakeLlmSessionFactory(
    private val failures: Map<ActiveBackend, Throwable> = emptyMap(),
    private val sessionProvider: () -> LlmSession = { FakeLlmSession() },
) : LlmSessionFactory {

    /** 按调用顺序记录的 create 档位。 */
    val createdTiers = CopyOnWriteArrayList<ActiveBackend>()

    override suspend fun create(
        modelPath: String,
        tier: ActiveBackend,
        config: LlmLoadConfig,
    ): Result<LlmSession> {
        createdTiers += tier
        failures[tier]?.let { failure -> return Result.failure(failure) }
        return Result.success(sessionProvider())
    }
}

private fun defaultFakeMetrics(): InferenceMetrics = InferenceMetrics(
    ttftMs = 12.0,
    promptTokens = 8L,
    generatedTokens = 3L,
    prefillSpeed = 1000.0,
    decodingSpeed = 50.0,
    stopReason = "eos",
    nCtx = 4096,
)
