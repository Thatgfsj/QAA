package com.thatgfsj.qaa.core.harness

import kotlin.math.ceil

/**
 * 上下文窗口预算（Issue #106）：把用户可配置的 nCtx 档位换算为提示预算与压缩水位。
 *
 * 语义变化（#106）：旧的装配层常量 CONTEXT_BUDGET = 3072 是 nCtx=4096 时扣除固定
 * 输出预留与系统段后的经验值；本类按 #100 真机取证重标定：
 * promptBudget = nCtx - outputReserve - safetyMargin，其中 outputReserve 取请求的
 * maxTokens（默认 512），safetyMargin = max(128, ceil(0.02·nCtx))。
 * 因此 nCtx=4096 时预算为 3456，比旧常量 3072 高 384 token，压缩触发点相应后移。
 *
 * 压缩水位（设计文档 6.2 / 6.4）：触发 0.8·promptBudget、目标 0.5·promptBudget，
 * 与 [ContextCompactor] 的 thresholdRatio / targetWatermarkRatio 默认值一致。
 *
 * 32k 档的真实成本（供用户决策）：4B 级 GQA 模型 f16 KV cache 约 144 KiB/token，
 * 32768 token ≈ 4.5 GiB，叠加模型权重与运行时缓冲后对手机内存压力显著；且 bundle 类
 * 非 CPU 后端（HTP/NPU）的长上下文在真机上有 abort 风险。因此 #106 不做非 CPU 硬 clamp，
 * 只在 >8192 档位改为「只保存不自动重载」，并由回执 / help / 设置页提示 abort 风险。
 */
object ContextWindow {

    /** 首启默认上下文长度：32k（#106 决策 1，首启即按 32k 载入，无迁移特例）。 */
    const val DEFAULT: Int = 32768

    /** 用户可选档位：固定 4 档（#106 决策 1，暂不做自由数值）。 */
    val OPTIONS: List<Int> = listOf(4096, 8192, 16384, 32768)

    /**
     * 输出预留默认值：与 LlmRequest.DEFAULT_MAX_TOKENS 对齐（:core-harness 不依赖
     * :core-inference，故在此镜像同一常量）。
     */
    const val DEFAULT_OUTPUT_RESERVE: Int = 512

    /** 安全边界下限（token）：吸收分词差异与 chat template 的固定开销。 */
    const val MIN_SAFETY_MARGIN: Int = 128

    /** 自动压缩触发水位：0.8 · promptBudget。 */
    const val COMPACT_TRIGGER_RATIO: Float = 0.8f

    /** 自动压缩目标水位：0.5 · promptBudget。 */
    const val COMPACT_TARGET_RATIO: Float = 0.5f

    /** 档位是否合法（4 档之一）。 */
    fun isValid(nCtx: Int): Boolean = nCtx in OPTIONS

    /**
     * 输出预留：一次生成最多占用的 token。
     *
     * 当前固定取 [DEFAULT_OUTPUT_RESERVE]（= LlmRequest 默认 maxTokens）；保留 [maxTokens]
     * 参数，便于后续按请求动态预留。校验 [nCtx] 仅为语义完整，不参与计算。
     */
    fun outputReserve(nCtx: Int, maxTokens: Int = DEFAULT_OUTPUT_RESERVE): Int {
        require(nCtx > 0) { "nCtx 必须为正数" }
        require(maxTokens > 0) { "maxTokens 必须为正数" }
        return maxTokens
    }

    /** 安全边界：max(128, ceil(2% · nCtx))，宁可高估也不低估。 */
    fun safetyMargin(nCtx: Int): Int {
        require(nCtx > 0) { "nCtx 必须为正数" }
        return maxOf(MIN_SAFETY_MARGIN, ceil(0.02 * nCtx).toInt())
    }

    /**
     * 提示预算：nCtx - 输出预留 - 安全边界。
     *
     * 四档实测值：4096→3456、8192→7516、16384→15544、32768→31600。
     */
    fun promptBudget(nCtx: Int): Int {
        require(nCtx > 0) { "nCtx 必须为正数" }
        return nCtx - outputReserve(nCtx) - safetyMargin(nCtx)
    }

    /** 预算别名：装配层压缩阈值统一以「提示预算」为分母。 */
    fun budgetFor(nCtx: Int): Int = promptBudget(nCtx)
}
