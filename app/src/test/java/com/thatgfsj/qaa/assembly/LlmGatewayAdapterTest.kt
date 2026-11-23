package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.PromptMessage
import com.thatgfsj.qaa.core.harness.PromptRole
import com.thatgfsj.qaa.core.inference.ActiveBackend
import com.thatgfsj.qaa.core.inference.ComputeBackend
import com.thatgfsj.qaa.core.inference.InferenceMetrics
import com.thatgfsj.qaa.core.inference.LlmEnginePort
import com.thatgfsj.qaa.core.inference.LlmLoadConfig
import com.thatgfsj.qaa.core.inference.LlmRequest
import com.thatgfsj.qaa.core.inference.LlmStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 推理端口适配单测：提示角色映射、流式聚合与指标映射（JVM，无需 Android）。 */
class LlmGatewayAdapterTest {

    private val metrics = InferenceMetrics(
        ttftMs = 142.0,
        promptTokens = 300,
        generatedTokens = 12,
        prefillSpeed = 900.0,
        decodingSpeed = 38.4,
        stopReason = "eos",
        nCtx = 4096,
        backend = ActiveBackend.NPU,
    )

    private class FakeEngine(
        private val metrics: InferenceMetrics,
        private val tokens: List<String> = listOf("你", "好"),
    ) : LlmEnginePort {

        val requests = mutableListOf<LlmRequest>()

        override val lockedBackend: StateFlow<ComputeBackend> = MutableStateFlow(ComputeBackend.AUTO)
        override val activeBackend: StateFlow<ActiveBackend?> = MutableStateFlow(null)
        override val isLoaded: StateFlow<Boolean> = MutableStateFlow(true)

        override suspend fun load(modelSource: String, config: LlmLoadConfig): Result<Unit> =
            Result.success(Unit)

        override suspend fun unload() = Unit

        override fun generate(request: LlmRequest): Flow<LlmStreamEvent> = flow {
            requests += request
            tokens.forEach { emit(LlmStreamEvent.Token(it)) }
            emit(LlmStreamEvent.Completed(metrics))
        }

        override suspend fun cancel() = Unit
    }

    @Test
    fun 流式片段聚合为完整文本并映射落树指标() = runTest {
        val engine = FakeEngine(metrics)
        val adapter = LlmGatewayAdapter(engine)
        val streamed = StringBuilder()

        val output = adapter.streamGenerate(
            listOf(PromptMessage(PromptRole.USER, "你好")),
            onToken = { streamed.append(it) },
        )

        assertEquals("你好", output.text)
        assertEquals("你好", streamed.toString())
        assertNotNull(output.metrics)
        assertEquals("NPU", output.metrics?.backend)
        assertEquals(142L, output.metrics?.ttftMs)
        assertEquals(300, output.metrics?.promptTokens)
        assertEquals(12, output.metrics?.completionTokens)
        assertEquals(38.4f, output.metrics?.tokensPerSec ?: 0f, 1e-4f)
    }

    @Test
    fun 统计栏快照使用实测指标并累计已用token() = runTest {
        val adapter = LlmGatewayAdapter(FakeEngine(metrics))
        adapter.streamGenerate(listOf(PromptMessage(PromptRole.USER, "你好")))

        val snapshot = adapter.metrics.value
        assertEquals(142L, snapshot.ttftMs)
        assertEquals(312, snapshot.tokensUsed)
        assertEquals(4096, snapshot.contextTokens)
        assertEquals(300, adapter.lastPromptTokens)
    }

    @Test
    fun 关闭遥测时内部生成不污染统计栏与占用记录() = runTest {
        val adapter = LlmGatewayAdapter(FakeEngine(metrics))
        adapter.telemetryEnabled = false
        adapter.streamGenerate(listOf(PromptMessage(PromptRole.SYSTEM, "摘要")))

        assertNull(adapter.lastPromptTokens)
        assertEquals(0, adapter.metrics.value.tokensUsed)
        assertNull(adapter.metrics.value.ttftMs)
    }

    @Test
    fun 工具结果段以user角色加前缀回注() = runTest {
        val engine = FakeEngine(metrics)
        val adapter = LlmGatewayAdapter(engine)
        adapter.streamGenerate(
            listOf(
                PromptMessage(PromptRole.SYSTEM, "系统段"),
                PromptMessage(PromptRole.TOOL, "文件内容"),
                PromptMessage(PromptRole.ASSISTANT, "好的"),
            ),
        )

        val messages = engine.requests.single().messages
        assertEquals(listOf("system", "user", "assistant"), messages.map { it.role })
        assertEquals("工具结果：\n文件内容", messages[1].content)
        assertEquals("系统段", messages[0].content)
    }
}
