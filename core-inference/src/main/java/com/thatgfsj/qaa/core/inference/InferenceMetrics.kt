package com.thatgfsj.qaa.core.inference

import com.geniex.sdk.bean.ProfilingData

/**
 * 单轮生成指标，直接映射 GenieX ProfilingData（docs/research/geniex-api.md §6）。
 *
 * 字段口径：
 * - [backend]：本轮实际命中的后端（[ActiveBackend]，状态栏与落盘指标以此为准）；
 * - [ttftMs]：首 token 延迟（SDK 语义化值，无需客户端自测）；
 * - [promptTokens]：本次 prefill 实际评估的 prompt token 数；
 * - [generatedTokens]：生成 token 数；
 * - [prefillSpeed] / [decodingSpeed]：prefill 与解码 tok/s；
 * - [stopReason]：SDK 原始结束原因（"eos" / "length" / "user" / "stop_sequence"）。
 *
 * [prefillSpeed] / [decodingSpeed] 同时是"实际后端感知"的第三源（速度侧写）：
 * NPU/GPU 与 CPU 的 tok/s 相差一个数量级，状态栏可据此做旁证展示；但档位判定以
 * create 成功档位为准（显式信号优先），速度阈值需真机校准，不在本类做推断。
 *
 * @param nCtx 加载时配置的上下文长度（0 表示沿用模型默认），用于估算上下文占用
 */
data class InferenceMetrics(
    val ttftMs: Double,
    val promptTokens: Long,
    val generatedTokens: Long,
    val prefillSpeed: Double,
    val decodingSpeed: Double,
    val stopReason: String,
    val nCtx: Int = 0,
    val backend: ActiveBackend? = null,
) {

    /** prompt + 生成 token 总量。 */
    val totalTokens: Long get() = promptTokens + generatedTokens

    /**
     * nCtx 占用估算（0.0-1.0）；[nCtx] 为 0 时为 null。
     *
     * 说明：0.10.0 的 ProfilingData 不提供 totalPromptTokens，KV 复用部分不计入
     * [promptTokens]，因此多轮场景下该值为下限估计。
     */
    val contextUsage: Float?
        get() = if (nCtx > 0) (totalTokens.toFloat() / nCtx).coerceIn(0f, 1f) else null

    companion object {

        /** 从 SDK 的 ProfilingData 映射；[nCtx] 与 [backend] 由引擎带入。 */
        fun from(
            profile: ProfilingData,
            nCtx: Int = 0,
            backend: ActiveBackend? = null,
        ): InferenceMetrics = InferenceMetrics(
            ttftMs = profile.ttftMs,
            promptTokens = profile.promptTokens,
            generatedTokens = profile.generatedTokens,
            prefillSpeed = profile.prefillSpeed,
            decodingSpeed = profile.decodingSpeed,
            stopReason = profile.stopReason,
            nCtx = nCtx,
            backend = backend,
        )
    }
}
