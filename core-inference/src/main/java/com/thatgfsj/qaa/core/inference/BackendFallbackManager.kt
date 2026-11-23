package com.thatgfsj.qaa.core.inference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 单次降级尝试的失败记录。 */
data class BackendAttempt(
    val tier: ActiveBackend,
    val error: String,
    val cause: Throwable? = null,
)

/** 降级链成功结果：创建出的值 + 命中档位 + 此前失败记录。 */
data class FallbackSuccess<T>(
    val value: T,
    val tier: ActiveBackend,
    val attempts: List<BackendAttempt>,
)

/**
 * create 级后端降级链（docs/ARCHITECTURE.md 第 4 节）。
 *
 * GenieX 没有内建的自动降级链：任一档 create 失败（Result.failure）后由本类显式
 * 尝试下一档。锁定档（NPU / HYBRID / CPU）只尝试自身，不回退；AUTO 依次尝试
 * NPU → HYBRID → GPU → CPU，全部失败时返回聚合错误 [BackendFallbackException]。
 *
 * 状态暴露（请求面与命中面分离，docs/ARCHITECTURE.md 第 3 节）：
 * - [lockedBackend]：请求/锁定面 [ComputeBackend]；
 * - [activeBackend]：命中面 [ActiveBackend]，GPU 命中如实上报；未命中时为 null；
 * - [lastAttempts]：最近一次 create 的失败记录，供状态栏与排障使用。
 */
class BackendFallbackManager {

    private val mutex = Mutex()

    private val _lockedBackend = MutableStateFlow(ComputeBackend.AUTO)
    private val _activeBackend = MutableStateFlow<ActiveBackend?>(null)
    private val _lastAttempts = MutableStateFlow<List<BackendAttempt>>(emptyList())

    val lockedBackend: StateFlow<ComputeBackend> = _lockedBackend.asStateFlow()
    val activeBackend: StateFlow<ActiveBackend?> = _activeBackend.asStateFlow()
    val lastAttempts: StateFlow<List<BackendAttempt>> = _lastAttempts.asStateFlow()

    /**
     * 按 [locked] 的尝试序列执行 create，直到某一档成功。
     *
     * @param creator 单档 create；抛出的异常等价于 Result.failure，协程取消原样上抛
     * @return 成功时为 [FallbackSuccess]，全部失败时为 [BackendFallbackException]
     */
    suspend fun <T> create(
        locked: ComputeBackend,
        creator: suspend (ActiveBackend) -> Result<T>,
    ): Result<FallbackSuccess<T>> = mutex.withLock {
        _lockedBackend.value = locked
        _activeBackend.value = null
        val attempts = mutableListOf<BackendAttempt>()
        for (tier in ActiveBackend.chainFor(locked)) {
            val outcome = try {
                creator(tier)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (outcome.isSuccess) {
                _activeBackend.value = tier
                _lastAttempts.value = attempts.toList()
                return@withLock Result.success(
                    FallbackSuccess(
                        value = outcome.getOrThrow(),
                        tier = tier,
                        attempts = attempts.toList(),
                    ),
                )
            }
            attempts += BackendAttempt(
                tier = tier,
                error = outcome.exceptionOrNull()?.message ?: "未知错误",
                cause = outcome.exceptionOrNull(),
            )
        }
        _lastAttempts.value = attempts.toList()
        _activeBackend.value = null
        return@withLock Result.failure(BackendFallbackException(locked, attempts.toList()))
    }

    /** 清除命中状态并记录新的锁定值（卸载或重新加载前调用）。 */
    fun reset(locked: ComputeBackend = ComputeBackend.AUTO) {
        _lockedBackend.value = locked
        _activeBackend.value = null
        _lastAttempts.value = emptyList()
    }
}

/** 降级链全部档位创建失败。 */
class BackendFallbackException(
    val locked: ComputeBackend,
    val attempts: List<BackendAttempt>,
) : RuntimeException(describeFallback(locked, attempts)) {

    init {
        attempts.forEach { attempt -> attempt.cause?.let { addSuppressed(it) } }
    }
}

private fun describeFallback(locked: ComputeBackend, attempts: List<BackendAttempt>): String {
    val header = if (locked == ComputeBackend.AUTO) {
        "AUTO 降级链全部档位创建失败"
    } else {
        "后端 " + locked.name + " 创建失败"
    }
    if (attempts.isEmpty()) return header
    return header + "：" + attempts.joinToString("；") { attempt ->
        attempt.tier.name + "(" + attempt.tier.computeUnit + ")=" + attempt.error
    }
}
