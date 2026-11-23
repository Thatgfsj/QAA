package com.thatgfsj.qaa.core.inference

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GenieX 引擎适配（Issue #5）。
 *
 * 职责：
 * 1. 幂等初始化 SDK（[GenieXSdkClient]）；
 * 2. 把释放后的本地 GGUF 导入 SDK 缓存并取权威 model_path（[GenieXModelResolver]）；
 * 3. 按 [LlmLoadConfig.backend] 执行 create 级降级链（[BackendFallbackManager]：
 *    AUTO 为 NPU → HYBRID → GPU → CPU，锁定档不回退，全败返回聚合错误）；
 * 4. 流式生成：复用 GenieX Flow，取消时保证调用 native stopStream（见 [GenieXLlmSession]）。
 *
 * 构造参数全部可注入，便于 JVM 单测与上层装配；生产装配用 [create]。
 */
class GenieXLlmEngine(
    private val initializer: SdkInitializer,
    private val modelResolver: ModelResolver,
    private val sessionFactory: LlmSessionFactory,
    private val backendProbe: BackendProbe? = null,
    private val fallback: BackendFallbackManager = BackendFallbackManager(),
) : LlmEnginePort {

    private val lifecycleMutex = Mutex()
    private val _isLoaded = MutableStateFlow(false)
    private val _observedSignal = MutableStateFlow<BackendSignal?>(null)

    @Volatile
    private var session: LlmSession? = null

    override val lockedBackend: StateFlow<ComputeBackend> get() = fallback.lockedBackend

    override val activeBackend: StateFlow<ActiveBackend?> get() = fallback.activeBackend

    override val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    /** 最近一次降级链的失败记录（成功档位之前的部分）。 */
    val lastAttempts: StateFlow<List<BackendAttempt>> get() = fallback.lastAttempts

    /** 可选旁证：logcat 解析出的后端信号；未注入探针或读取失败时为 null。 */
    val observedBackendSignal: StateFlow<BackendSignal?> = _observedSignal.asStateFlow()

    override suspend fun load(modelSource: String, config: LlmLoadConfig): Result<Unit> =
        lifecycleMutex.withLock {
            releaseSession()
            fallback.reset(config.backend)
            _observedSignal.value = null

            val initialized = initializer.initialize()
            if (initialized.isFailure) {
                return@withLock Result.failure(initialized.exceptionOrNull()!!)
            }

            val resolved = modelResolver.resolve(modelSource)
            if (resolved.isFailure) {
                return@withLock Result.failure(resolved.exceptionOrNull()!!)
            }
            val modelPath = resolved.getOrThrow()

            val created = fallback.create(config.backend) { tier ->
                sessionFactory.create(modelPath, tier, config)
            }
            if (created.isFailure) {
                return@withLock Result.failure(created.exceptionOrNull()!!)
            }

            session = created.getOrThrow().value
            _isLoaded.value = true
            _observedSignal.value = probeBackend()
            Result.success(Unit)
        }

    override suspend fun unload() {
        lifecycleMutex.withLock {
            releaseSession()
            fallback.reset(ComputeBackend.AUTO)
            _observedSignal.value = null
        }
    }

    override fun generate(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val current = session ?: throw LlmNotLoadedException()
        try {
            current.generate(request).collect { event -> emit(event) }
        } catch (cancelled: CancellationException) {
            // 下游取消：只取消 Flow 不会停止 native 生成，必须显式停止。
            withContext(NonCancellable) { current.cancel() }
            throw cancelled
        }
    }

    override suspend fun cancel() {
        session?.cancel()
    }

    private suspend fun releaseSession() {
        val current = session
        session = null
        _isLoaded.value = false
        current?.close()
    }

    private suspend fun probeBackend(): BackendSignal? {
        val probe = backendProbe ?: return null
        return withTimeoutOrNull(PROBE_TIMEOUT_MS) { probe.probe() }
    }

    companion object {

        private const val PROBE_TIMEOUT_MS = 1000L

        /** 生产装配：SDK 初始化 + LOCALFS 模型导入 + GenieX 会话工厂 + logcat 旁证。 */
        fun create(
            context: Context,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): GenieXLlmEngine = GenieXLlmEngine(
            initializer = GenieXSdkClient(context, ioDispatcher),
            modelResolver = GenieXModelResolver(),
            sessionFactory = GenieXLlmSessionFactory(ioDispatcher),
            backendProbe = LogcatBackendProbe(),
        )
    }
}
