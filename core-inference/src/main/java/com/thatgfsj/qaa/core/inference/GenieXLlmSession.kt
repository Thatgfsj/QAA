package com.thatgfsj.qaa.core.inference

import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * GenieX LlmWrapper 会话（Issue #5）。
 *
 * 提示词契约：必须把多轮消息经 applyChatTemplate 渲染成模板化提示词后再喂
 * generateStreamFlow，直接传原始用户文本会退化输出（调研 §4.2）。
 *
 * 取消契约：只取消 Flow 采集不会停止 native 生成，必须调用 LlmWrapper.stopStream()
 * （调研 §5.2）。本类在两处兜底：
 * - 采集方取消（协程取消、take 提前结束）时捕获 CancellationException 后停止；
 * - [cancel] 显式停止。
 *
 * 会话历史由调用方持有，本类不存储对话状态；每轮由完整消息数组重建提示词。
 */
internal class GenieXLlmSession(
    private val wrapper: LlmWrapper,
    private val config: LlmLoadConfig,
    private val backend: ActiveBackend,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LlmSession {

    private val generating = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun generate(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        check(!closed.get()) { "会话已关闭" }
        if (!generating.compareAndSet(false, true)) {
            throw LlmGenerationBusyException()
        }
        try {
            val template = wrapper.applyChatTemplate(
                messages = request.messages
                    .map { message -> ChatMessage(role = message.role, content = message.content) }
                    .toTypedArray(),
                tools = request.toolsJson,
                enableThinking = request.enableThinking,
            ).getOrElse { error ->
                emit(LlmStreamEvent.Failed(error))
                return@flow
            }

            val generationConfig = GenerationConfig(
                maxTokens = request.maxTokens,
                samplerConfig = request.temperature?.let { value ->
                    SamplerConfig().also { sampler -> sampler.temperature = value }
                },
            )

            wrapper.generateStreamFlow(
                prompt = template.formattedText,
                config = generationConfig,
            ).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> emit(LlmStreamEvent.Token(result.text))
                    is LlmStreamResult.Completed -> emit(
                        LlmStreamEvent.Completed(
                            InferenceMetrics.from(result.profile, config.nCtx, backend),
                        ),
                    )
                    is LlmStreamResult.Error -> emit(LlmStreamEvent.Failed(result.throwable))
                }
            }
        } catch (cancelled: CancellationException) {
            // 采集方提前结束（协程取消或 take 截断）同样必须停止 native 生成。
            withContext(NonCancellable) { stopQuietly() }
            throw cancelled
        } finally {
            generating.set(false)
        }
    }

    override suspend fun cancel() {
        withContext(NonCancellable) { stopQuietly() }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withContext(NonCancellable) { stopQuietly() }
        withContext(dispatcher) { wrapper.close() }
    }

    private suspend fun stopQuietly() {
        runCatching { wrapper.stopStream() }
    }
}

/**
 * GenieX 会话工厂：把 [ActiveBackend] 翻译成 compute_unit 并执行 LlmWrapper.builder().build()。
 *
 * create 后做一次空句柄校验：Builder.build() 只把异常映射为 Result.failure，native
 * std::exception 分支可能返回 0 句柄仍报 success（调研 §9.2）；此时 applyChatTemplate
 * 会以 "Llm not initialized" 失败。识别到该失败即视为该档 create 失败，让降级链继续
 * 尝试下一档；其他模板错误不在此拦截。
 */
class GenieXLlmSessionFactory(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LlmSessionFactory {

    override suspend fun create(
        modelPath: String,
        tier: ActiveBackend,
        config: LlmLoadConfig,
    ): Result<LlmSession> {
        val createInput = LlmCreateInput(
            model_path = modelPath,
            config = ModelConfig(
                nCtx = config.nCtx,
                nThreads = config.nThreads,
                nThreadsBatch = config.nThreadsBatch,
                power_mode = config.powerMode,
            ),
            runtime_id = config.runtimeId,
            compute_unit = tier.computeUnit,
        )
        val wrapper = LlmWrapper.builder()
            .llmCreateInput(createInput)
            .dispatcher(dispatcher)
            .build()
            .getOrElse { error -> return Result.failure(error) }

        val probe = wrapper.applyChatTemplate(
            messages = arrayOf(ChatMessage(role = "user", content = "ping")),
            tools = null,
            enableThinking = false,
        )
        if (probe.exceptionOrNull()?.message?.contains("Llm not initialized") == true) {
            wrapper.close()
            return Result.failure(
                IllegalStateException(
                    "GenieX native create 返回空句柄（compute_unit=" + tier.computeUnit + "）",
                    probe.exceptionOrNull(),
                ),
            )
        }
        return Result.success(GenieXLlmSession(wrapper, config, tier, dispatcher))
    }
}
