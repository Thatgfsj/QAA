package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.delay

/**
 * 脚本化引擎替身（设计附录 A.0）：按序弹出预设输出，队列耗尽重复末元素；
 * 记录每次收到的 messages；可注入延迟、抛错与生成指标。
 */
class FakeLlmEngine(
    private val scripted: List<String>,
    private val failWith: Exception? = null,
    private val delayMs: Long = 0,
    private val metrics: GenerationMetrics? = GenerationMetrics(backend = "NPU"),
) : LlmEngineGateway {

    val receivedMessages = mutableListOf<List<PromptMessage>>()
    private val queue = ArrayDeque(scripted)

    override suspend fun streamGenerate(
        messages: List<PromptMessage>,
        onToken: (String) -> Unit,
    ): GenerationOutput {
        receivedMessages += messages
        failWith?.let { throw it }
        if (delayMs > 0) delay(delayMs)
        val text = if (queue.isEmpty()) scripted.last() else queue.removeFirst()
        onToken(text)
        return GenerationOutput(text = text, metrics = metrics)
    }
}
