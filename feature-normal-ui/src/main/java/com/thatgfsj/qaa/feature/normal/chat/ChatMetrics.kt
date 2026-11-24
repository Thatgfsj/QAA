package com.thatgfsj.qaa.feature.normal.chat

import java.util.Locale

/**
 * 会话页轻量指标快照（Issue #91）：TTFT / 解码速度 / Token 用量。
 *
 * 未载入模型或尚未产生实测指标时三项均为空，指标条显示 `--` 占位；
 * 数据只由装配层指标流推送，UI 不做本地推算。
 */
data class ChatMetrics(
    val ttftMs: Long? = null,
    val tokensPerSecond: Double? = null,
    val tokensUsed: Int = 0,
    val contextTokens: Int = 4096,
) {
    /** 尚无任何实测指标（未载入模型 / 从未生成）。 */
    val isPlaceholder: Boolean
        get() = ttftMs == null && tokensPerSecond == null && tokensUsed == 0
}

/** 指标条单行文案：一行小字，未载入模型时显示 -- 占位。 */
fun ChatMetrics.metricsBarText(): String {
    if (isPlaceholder) return "TTFT -- | 速度 -- tok/s | Token --"
    val ttft = ttftMs?.let { it.toString() + "ms" } ?: "--"
    val speed = tokensPerSecond?.let { "%.1f".format(Locale.ROOT, it) + " tok/s" } ?: "--"
    return "TTFT $ttft | 速度 $speed | Token $tokensUsed / $contextTokens"
}
