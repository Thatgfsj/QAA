package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.CompactionResult
import com.thatgfsj.qaa.core.harness.ContextWindow
import com.thatgfsj.qaa.core.harness.HeuristicTokenCounter
import com.thatgfsj.qaa.core.harness.PromptMessage
import com.thatgfsj.qaa.core.harness.PromptRole
import com.thatgfsj.qaa.core.harness.TokenCounter
import com.thatgfsj.qaa.core.inference.LlmEnginePort
import com.thatgfsj.qaa.core.inference.LlmLoadConfig

/** 载入结果：nCtx 为引擎实际生效值（安全网回退后可能小于请求值）。 */
internal sealed interface ContextLoadOutcome {

    data class Loaded(val nCtx: Int) : ContextLoadOutcome

    data class Failed(val cause: Throwable) : ContextLoadOutcome
}

/** 安全网回执文案（#106 决策 5）。 */
internal fun describeContextFallback(requested: Int, fallback: Int): String =
    "上下文 $requested 载入失败，已回退 $fallback（终端 /ctx $fallback 可固定）"

/**
 * 上下文载入安全网（#106 决策 5）：按配置 nCtx 载入返回 Result.failure（非 native abort，
 * 后者不可捕获）时，先经 [onFallback] 交装配层写 AppLog / 状态栏，再自动用 4096 重试一次；
 * 仍失败才判定载入失败。
 */
internal class ContextLoadFallback(
    private val engine: LlmEnginePort,
    private val fallbackNctx: Int = FALLBACK_N_CTX,
    private val onFallback: (requested: Int, failure: Throwable) -> Unit = { _, _ -> },
) {

    suspend fun load(modelSource: String, config: LlmLoadConfig): ContextLoadOutcome {
        val requested = config.nCtx
        val firstFailure = engine.load(modelSource, config).exceptionOrNull()
        if (firstFailure == null) return ContextLoadOutcome.Loaded(requested)
        if (requested == fallbackNctx) return ContextLoadOutcome.Failed(firstFailure)
        onFallback(requested, firstFailure)
        // 半加载状态可能残留：重试前先清干净。
        engine.unload()
        val retryFailure = engine.load(modelSource, config.copy(nCtx = fallbackNctx)).exceptionOrNull()
        return if (retryFailure == null) {
            ContextLoadOutcome.Loaded(fallbackNctx)
        } else {
            ContextLoadOutcome.Failed(retryFailure)
        }
    }

    companion object {

        /** 安全网回退档位：最小可用档，任何模型都应能载入。 */
        const val FALLBACK_N_CTX = 4096
    }
}

/**
 * 每轮首次生成前的上下文预检（#106）：实测 prompt + 估算新输入 + 输出预留越界时先压缩，
 * 压缩后仍越界则拒绝本轮生成（真机 3951/4096 事故的直接防线）。
 */
internal class ContextPreflightGuard(
    private val counter: TokenCounter = HeuristicTokenCounter(),
) {

    /** 本轮新输入的 token 估算（含单条消息固定开销）。 */
    fun estimateInput(text: String): Int =
        counter.countMessages(listOf(PromptMessage(PromptRole.USER, text)))

    /** 三要素是否越过 nCtx：prompt + 新输入 + 输出预留 > nCtx。 */
    fun overflows(usedTokens: Int, newInputTokens: Int, nCtx: Int): Boolean =
        usedTokens + newInputTokens + ContextWindow.outputReserve(nCtx) > nCtx

    /**
     * 判定本轮能否发送 generate：可发返回 true；越界则先压缩再复判，仍越界返回 false。
     *
     * [estimatedHistory] 必须与真实 prompt 同口径（含 PromptAssembler 稳定前缀）；
     * [compact] 的入参取实测与新输入之和，避免压缩器按实测值误判「未达阈值」而跳过。
     */
    suspend fun admit(
        text: String,
        lastPromptTokens: Int?,
        estimatedHistory: () -> Int,
        nCtx: Int,
        compact: suspend (usedTokens: Int) -> CompactionResult,
    ): Boolean {
        val newInput = estimateInput(text)
        val used = lastPromptTokens ?: estimatedHistory()
        if (!overflows(used, newInput, nCtx)) return true
        compact(maxOf(used, used + newInput))
        return !overflows(estimatedHistory(), newInput, nCtx)
    }
}
