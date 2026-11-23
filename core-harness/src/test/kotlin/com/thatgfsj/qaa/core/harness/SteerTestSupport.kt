package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/**
 * 工具执行可暂停替身：execute 挂起到测试显式放行，用于在「工具执行中」精确注入
 * /steer（附录 A.1 L6）。放行后转为立即完成，避免后续轮次意外挂起。
 */
class BlockingToolGateway : ToolExecutorGateway {

    private data class Pending(val call: ToolCall, val release: CompletableDeferred<Unit>)

    val executed = mutableListOf<ExecutedCall>()
    private val pending = mutableListOf<Pending>()
    private val started = Channel<Unit>(Channel.UNLIMITED)
    private var open = false

    override suspend fun requiresConfirmation(toolName: String): Boolean = false

    override suspend fun execute(call: ToolCall, stepIndex: Int): ToolOutcome {
        val release = CompletableDeferred<Unit>()
        pending += Pending(call, release)
        executed += ExecutedCall(call, stepIndex)
        started.send(Unit)
        if (!open) release.await() else release.complete(Unit)
        return ToolOutcome(ToolOutcomeStatus.SUCCESS, "（模拟执行成功）${call.tool} 已处理")
    }

    /** 等待下一次 execute 进入挂起。 */
    suspend fun awaitStarted() = started.receive()

    /** 放行全部已进入挂起的执行；之后的执行不再阻塞。 */
    fun releaseAll() {
        open = true
        pending.forEach { it.release.complete(Unit) }
    }
}

/**
 * 生成可暂停替身：每次 streamGenerate 挂起到测试放行，用于在「纯文本生成中」注入
 * /steer（附录 A.7 Q3：不硬切流，轮末优先注入）。
 */
class BlockingLlmEngine(outputs: List<String>) : LlmEngineGateway {

    private val scripted = outputs.also { require(it.isNotEmpty()) { "至少需要一条脚本回复" } }

    val receivedMessages = mutableListOf<List<PromptMessage>>()
    private val pending = mutableListOf<CompletableDeferred<Unit>>()
    private val started = Channel<Unit>(Channel.UNLIMITED)
    private var open = false

    override suspend fun streamGenerate(
        messages: List<PromptMessage>,
        onToken: (String) -> Unit,
    ): GenerationOutput {
        receivedMessages += messages
        val release = CompletableDeferred<Unit>()
        pending += release
        started.send(Unit)
        if (!open) release.await() else release.complete(Unit)
        val text = scripted[(receivedMessages.size - 1).coerceAtMost(scripted.lastIndex)]
        onToken(text)
        return GenerationOutput(text = text, metrics = GenerationMetrics(backend = "NPU"))
    }

    /** 等待下一次生成进入挂起。 */
    suspend fun awaitStarted() = started.receive()

    /** 放行全部挂起生成；之后的生成不再阻塞。 */
    fun releaseAll() {
        open = true
        pending.forEach { it.complete(Unit) }
    }
}
