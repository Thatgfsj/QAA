package com.thatgfsj.qaa.feature.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 推理指标快照（Issue #5 的 InferenceMetrics 接线形状）。
 *
 * - [ttftMs]：首 Token 时延；未开始 / 未命中为 null，统计栏显示「--」。
 * - [tokensPerSecond]：生成速度；未开始为 null，生成结束可置 0.0。
 * - [tokensUsed]：上下文已用 Token；[contextTokens]：nCtx 总量（冒烟配置 4096）。
 */
data class TerminalMetricsSnapshot(
    val ttftMs: Long? = null,
    val tokensPerSecond: Double? = null,
    val tokensUsed: Int = 0,
    val contextTokens: Int = 4096,
)

/** 底部常驻统计栏单行文案（Issue #9 规格）：TTFT: 142ms | 速度: 38.4 tok/s | Token: 412 / 4096。 */
fun TerminalMetricsSnapshot.statsBarText(): String {
    val ttft = ttftMs?.let { "${it}ms" } ?: "--"
    val speed = tokensPerSecond?.let { "%.1f".format(Locale.ROOT, it) + " tok/s" } ?: "--"
    return "TTFT: $ttft | 速度: $speed | Token: $tokensUsed / $contextTokens"
}

/**
 * 模块内伪数据源：按固定间隔心跳产出 [TerminalMetricsSnapshot] StateFlow，
 * 驱动底部统计栏「随 metrics 流实时更新」的骨架验收项。
 *
 * Issue #5 接线契约：[TerminalViewModel] 只依赖
 * StateFlow&lt;TerminalMetricsSnapshot&gt;；真实 InferenceMetrics 流就绪后，
 * 在装配层把指标映射为本快照形状并替换本类即可，UI 层零改动。
 */
class FakeMetricsTicker(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
) {
    private val _metrics = MutableStateFlow(TerminalMetricsSnapshot(contextTokens = contextTokens))
    val metrics: StateFlow<TerminalMetricsSnapshot> = _metrics.asStateFlow()

    private var tickCount = 0

    /** 在给定作用域内启动心跳循环；返回 Job 供装配层按需取消。 */
    fun launchIn(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            delay(intervalMs)
            _metrics.value = nextSnapshot()
        }
    }

    /** 确定性递推（便于单测断言）：TTFT 固定冒烟值，速度按 [SPEED_CYCLE] 周期抖动，Token 累加封顶。 */
    internal fun nextSnapshot(): TerminalMetricsSnapshot {
        tickCount += 1
        val used = (tickCount * TOKENS_PER_TICK).coerceAtMost(contextTokens)
        return TerminalMetricsSnapshot(
            ttftMs = SMOKE_TTFT_MS,
            tokensPerSecond = if (used < contextTokens) SPEED_CYCLE[(tickCount - 1) % SPEED_CYCLE.size] else 0.0,
            tokensUsed = used,
            contextTokens = contextTokens,
        )
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 1_000L
        const val DEFAULT_CONTEXT_TOKENS = 4096
        /** Issue #9 示例冒烟值。 */
        const val SMOKE_TTFT_MS = 142L
        internal val SPEED_CYCLE = doubleArrayOf(36.0, 37.2, 38.4, 39.6, 40.8)
        internal const val TOKENS_PER_TICK = 24
    }
}
