package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.ContextWindow
import com.thatgfsj.qaa.core.inference.ActiveBackend
import com.thatgfsj.qaa.core.inference.ComputeBackend
import com.thatgfsj.qaa.core.inference.LlmEnginePort
import com.thatgfsj.qaa.core.inference.LlmLoadConfig
import com.thatgfsj.qaa.core.inference.LlmRequest
import com.thatgfsj.qaa.core.inference.LlmStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上下文长度即时生效策略单测（#106 决策 2）：三态结果、零调用与回滚落户纪律。 */
class ContextLengthSwitcherTest {

    @Test
    fun 引擎未加载时只保存且零引擎调用() = runTest {
        val engine = FakeCtxEngine(initiallyLoaded = false)
        val result = ContextLengthSwitcher(engine).set("model.gguf", 32768, 4096)

        assertTrue(engine.calls.isEmpty())
        assertTrue(result is ContextLengthChangeResult.Deferred)
        val deferred = result as ContextLengthChangeResult.Deferred
        assertEquals(4096, deferred.next)
        assertTrue(deferred.message.contains("引擎尚未加载"))
        assertTrue(deferred.message.contains("下次载入模型时生效"))
    }

    @Test
    fun 没有可重载模型时只保存() = runTest {
        val engine = FakeCtxEngine()
        val result = ContextLengthSwitcher(engine).set(null, 32768, 4096)

        assertTrue(engine.calls.isEmpty())
        assertTrue(result is ContextLengthChangeResult.Deferred)
        assertTrue(result.message.contains("已保存"))
    }

    @Test
    fun 已加载且目标不超过8192时卸载并按新值重载() = runTest {
        val engine = FakeCtxEngine()
        val statuses = mutableListOf<String>()
        val result = ContextLengthSwitcher(engine) { statuses += it }
            .set("model.gguf", 32768, 8192, backend = ComputeBackend.CPU)

        assertTrue(result is ContextLengthChangeResult.Applied)
        assertEquals(listOf("unload", "load:8192:CPU"), engine.calls)
        assertTrue(result.message.contains("32768 → 8192"))
        assertTrue(result.message.contains("已按新值重新载入"))
        assertEquals(AgentRuntime.READY_TEXT, statuses.last())
    }

    @Test
    fun 目标大于8192时只保存不重载() = runTest {
        val engine = FakeCtxEngine()
        val result = ContextLengthSwitcher(engine).set("model.gguf", 4096, 32768)

        assertTrue(engine.calls.isEmpty())
        assertTrue(result is ContextLengthChangeResult.Deferred)
        assertEquals(32768, (result as ContextLengthChangeResult.Deferred).next)
        assertTrue(result.message.contains("已保存"))
        assertTrue(result.message.contains("需重新载入模型后生效"))
        assertTrue(result.message.contains("内存溢出闪退"))
    }

    @Test
    fun 重载失败时回滚旧值且不视为成功() = runTest {
        val engine = FakeCtxEngine(failures = mutableListOf(RuntimeException("nCtx 分配失败")))
        val result = ContextLengthSwitcher(engine).set("model.gguf", 32768, 4096)

        assertEquals(
            listOf("unload", "load:4096:AUTO", "unload", "load:32768:AUTO"),
            engine.calls,
        )
        assertTrue(result is ContextLengthChangeResult.RolledBack)
        val rolled = result as ContextLengthChangeResult.RolledBack
        assertTrue(rolled.restored)
        assertTrue(rolled.message.contains("nCtx 分配失败"))
        assertTrue(rolled.message.contains("已回退 32768"))
        assertTrue(engine.isLoaded.value)
    }

    @Test
    fun 回滚也失败时提示重新载入() = runTest {
        val engine = FakeCtxEngine(
            failures = mutableListOf(RuntimeException("新档失败"), RuntimeException("旧档失败")),
        )
        val result = ContextLengthSwitcher(engine).set("model.gguf", 32768, 4096)

        val rolled = result as ContextLengthChangeResult.RolledBack
        assertFalse(rolled.restored)
        assertTrue(rolled.message.contains("重新载入"))
        assertFalse(engine.isLoaded.value)
    }

    @Test
    fun 相同档位不触发重载() = runTest {
        val engine = FakeCtxEngine()
        val result = ContextLengthSwitcher(engine).set("model.gguf", 8192, 8192)

        assertTrue(engine.calls.isEmpty())
        assertTrue(result is ContextLengthChangeResult.Deferred)
        assertTrue(result.message.contains("已是 8192"))
    }

    @Test
    fun 回执文案纯函数覆盖五类() {
        assertTrue(
            describeContextLengthChange(4096, 8192, ContextLengthChange.APPLIED)
                .contains("模型已按新值重新载入"),
        )
        assertTrue(
            describeContextLengthChange(4096, 32768, ContextLengthChange.SAVED_ONLY)
                .contains("内存溢出闪退"),
        )
        assertTrue(
            describeContextLengthChange(4096, 8192, ContextLengthChange.NOT_LOADED)
                .contains("下次载入模型时生效"),
        )
        assertTrue(
            describeContextLengthChange(8192, 4096, ContextLengthChange.ROLLED_BACK, "原因x")
                .contains("已回退 8192"),
        )
        assertTrue(
            describeContextLengthChange(8192, 4096, ContextLengthChange.ROLLBACK_FAILED, "原因x")
                .contains("重载也失败"),
        )
    }

    @Test
    fun 查询文案同时给出设置值引擎值与预算() {
        val text = describeContextLength(32768, 4096, ContextWindow.promptBudget(4096))

        assertTrue(text.contains("设置 32768"))
        assertTrue(text.contains("引擎 4096"))
        assertTrue(text.contains("预算 3456"))
        // #112：回执不再列建议档位。
        assertFalse(text.contains("8192 / 16384"))
    }

    /** 记录调用序列的引擎替身；[failures] 按 load 顺序消费，用尽后成功。 */
    private class FakeCtxEngine(
        private val failures: MutableList<Throwable> = mutableListOf(),
        initiallyLoaded: Boolean = true,
    ) : LlmEnginePort {

        val calls = mutableListOf<String>()
        private val loaded = MutableStateFlow(initiallyLoaded)

        override val lockedBackend: StateFlow<ComputeBackend> = MutableStateFlow(ComputeBackend.AUTO)
        override val activeBackend: StateFlow<ActiveBackend?> = MutableStateFlow(null)
        override val isLoaded: StateFlow<Boolean> = loaded

        override suspend fun load(modelSource: String, config: LlmLoadConfig): Result<Unit> {
            calls += "load:${config.nCtx}:${config.backend}"
            val failure = failures.removeFirstOrNull()
            if (failure != null) {
                loaded.value = false
                return Result.failure(failure)
            }
            loaded.value = true
            return Result.success(Unit)
        }

        override suspend fun unload() {
            calls += "unload"
            loaded.value = false
        }

        override fun generate(request: LlmRequest): Flow<LlmStreamEvent> = emptyFlow()

        override suspend fun cancel() = Unit
    }
}
