package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.GenerationMetrics
import com.thatgfsj.qaa.core.harness.GenerationOutput
import com.thatgfsj.qaa.core.harness.LlmEngineGateway
import com.thatgfsj.qaa.core.harness.PromptMessage
import com.thatgfsj.qaa.core.harness.PromptRole
import com.thatgfsj.qaa.core.inference.InferenceMetrics
import com.thatgfsj.qaa.core.inference.LlmEnginePort
import com.thatgfsj.qaa.core.inference.LlmMessage
import com.thatgfsj.qaa.core.inference.LlmRequest
import com.thatgfsj.qaa.core.inference.LlmStreamEvent
import com.thatgfsj.qaa.feature.terminal.TerminalMetricsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 推理引擎端口适配（HarnessPorts.LlmEngineGateway ⇄ core-inference LlmEnginePort）。
 *
 * 职责边界：
 * - 提示消息映射：SYSTEM / USER / ASSISTANT 原样对接；TOOL 段（工具结果回注与摘要段）
 *   以 user 角色发送并加前缀，兼容只认三角色的 chat template；
 * - 流式聚合：逐 Token 追加并把片段交给 onToken，Completed 事件收敛为指标；
 * - 指标映射：InferenceMetrics → GenerationMetrics（落树）与 TerminalMetricsSnapshot（统计栏）。
 *
 * [telemetryEnabled] 供装配层在压缩摘要生成等内部调用期间关闭统计栏刷新与实测占用记录，
 * 避免内部生成污染用户可见指标。
 *
 * [contextTokens] 为 nCtx 回退值提供者（#106）：引擎指标未携带 nCtx 时按当前用户设置回显，
 * 正常路径下 [InferenceMetrics.nCtx] 直接决定统计栏总量。
 */
class LlmGatewayAdapter(
    private val engine: LlmEnginePort,
    private val contextTokens: () -> Int = { DEFAULT_CONTEXT_TOKENS },
) : LlmEngineGateway {

    private val _metrics = MutableStateFlow(TerminalMetricsSnapshot(contextTokens = contextTokens()))

    /** 统计栏指标流（与 TerminalMetricsSnapshot 同形，UI 层零改动接入）。 */
    val metrics: StateFlow<TerminalMetricsSnapshot> = _metrics.asStateFlow()

    /** 是否记录实测指标（内部生成期间关闭）。 */
    @Volatile
    var telemetryEnabled: Boolean = true

    /** 最近一次计入统计的 prompt token 数；压缩判定与 /status 使用。 */
    @Volatile
    var lastPromptTokens: Int? = null
        private set

    override suspend fun streamGenerate(
        messages: List<PromptMessage>,
        onToken: (String) -> Unit,
    ): GenerationOutput {
        val builder = StringBuilder()
        var completed: InferenceMetrics? = null
        engine.generate(LlmRequest(messages = messages.map { it.toLlmMessage() })).collect { event ->
            when (event) {
                is LlmStreamEvent.Token -> {
                    builder.append(event.text)
                    onToken(event.text)
                }
                is LlmStreamEvent.Completed -> completed = event.metrics
                is LlmStreamEvent.Failed -> throw event.cause
            }
        }
        val measured = completed
        if (measured != null && telemetryEnabled) {
            lastPromptTokens = measured.promptTokens.toInt()
            _metrics.value = measured.toTerminalSnapshot(contextTokens())
        }
        return GenerationOutput(
            text = builder.toString(),
            metrics = measured?.toGenerationMetrics(),
        )
    }

    companion object {
        /** 冒烟配置 nCtx（docs/ARCHITECTURE.md 第 1 节）。 */
        const val DEFAULT_CONTEXT_TOKENS = 4096
    }
}

/** 提示消息映射：工具结果以 user 角色回注并加中文前缀。 */
internal fun PromptMessage.toLlmMessage(): LlmMessage = when (role) {
    PromptRole.SYSTEM -> LlmMessage.system(content)
    PromptRole.USER -> LlmMessage.user(content)
    PromptRole.ASSISTANT -> LlmMessage.assistant(content)
    PromptRole.TOOL -> LlmMessage.user("工具结果：\n" + content)
}

/** 引擎指标 → 落树指标（backend 取实际命中后端字符串）。 */
internal fun InferenceMetrics.toGenerationMetrics(): GenerationMetrics = GenerationMetrics(
    backend = backend?.name,
    ttftMs = ttftMs.toLong(),
    tokensPerSec = decodingSpeed.toFloat(),
    promptTokens = promptTokens.toInt(),
    completionTokens = generatedTokens.toInt(),
)

/** 引擎指标 → 统计栏快照（nCtx 未知时回退冒烟配置）。 */
internal fun InferenceMetrics.toTerminalSnapshot(fallbackContextTokens: Int): TerminalMetricsSnapshot =
    TerminalMetricsSnapshot(
        ttftMs = ttftMs.toLong(),
        tokensPerSecond = decodingSpeed,
        tokensUsed = (promptTokens + generatedTokens).toInt(),
        contextTokens = if (nCtx > 0) nCtx else fallbackContextTokens,
    )
