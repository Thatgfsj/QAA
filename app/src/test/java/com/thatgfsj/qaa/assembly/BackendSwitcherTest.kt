package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.inference.ActiveBackend
import com.thatgfsj.qaa.core.inference.ComputeBackend
import com.thatgfsj.qaa.core.inference.LlmEnginePort
import com.thatgfsj.qaa.core.inference.LlmLoadConfig
import com.thatgfsj.qaa.core.inference.LlmRequest
import com.thatgfsj.qaa.core.inference.LlmStreamEvent
import com.thatgfsj.qaa.feature.terminal.BackendRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** backend 即时切换单测：unload → load 序列、失败回滚、未加载时零调用。 */
class BackendSwitcherTest {

    @Test
    fun 切换成功时先卸载再按新后端载入() = runTest {
        val engine = RecordingEngine()
        val statuses = mutableListOf<String>()
        val result = BackendSwitcher(engine) { statuses += it }
            .apply("model.gguf", ComputeBackend.CPU, BackendRequest.NPU)

        assertTrue(result is BackendSwitchResult.Applied)
        assertEquals(listOf("unload", "load:NPU"), engine.calls)
        assertTrue(result.message.contains("NPU"))
        assertTrue(statuses.first().contains("正在切换"))
        assertEquals(AgentRuntime.READY_TEXT, statuses.last())
    }

    @Test
    fun 新后端失败时按旧后端回滚并说明() = runTest {
        val engine = RecordingEngine(failures = mutableListOf(RuntimeException("NPU 不可用")))
        val result = BackendSwitcher(engine)
            .apply("model.gguf", ComputeBackend.CPU, BackendRequest.NPU)

        assertEquals(listOf("unload", "load:NPU", "unload", "load:CPU"), engine.calls)
        assertTrue(result is BackendSwitchResult.RolledBack)
        val rolledBack = result as BackendSwitchResult.RolledBack
        assertTrue(rolledBack.restored)
        assertTrue(rolledBack.message.contains("NPU 不可用"))
        assertTrue(rolledBack.message.contains("回滚"))
        assertTrue(engine.isLoaded.value)
    }

    @Test
    fun 回滚也失败时提示重新载入() = runTest {
        val engine = RecordingEngine(
            failures = mutableListOf(RuntimeException("新档失败"), RuntimeException("旧档失败")),
        )
        val result = BackendSwitcher(engine)
            .apply("model.gguf", ComputeBackend.CPU, BackendRequest.NPU)

        assertTrue(result is BackendSwitchResult.RolledBack)
        assertFalse((result as BackendSwitchResult.RolledBack).restored)
        assertTrue(result.message.contains("重新载入"))
        assertFalse(engine.isLoaded.value)
    }

    @Test
    fun 引擎未加载时零调用且保留锁定值() = runTest {
        val engine = RecordingEngine(initiallyLoaded = false)
        val result = BackendSwitcher(engine)
            .apply("model.gguf", ComputeBackend.CPU, BackendRequest.HYBRID)

        assertTrue(engine.calls.isEmpty())
        assertTrue(result is BackendSwitchResult.Deferred)
        assertEquals(ComputeBackend.HYBRID, (result as BackendSwitchResult.Deferred).next)
        assertTrue(result.message.contains("下次载入模型时生效"))
    }

    @Test
    fun 没有可重载模型时零调用() = runTest {
        val engine = RecordingEngine()
        val result = BackendSwitcher(engine).apply(null, ComputeBackend.CPU, BackendRequest.CPU)

        assertTrue(engine.calls.isEmpty())
        assertTrue(result is BackendSwitchResult.Deferred)
        assertTrue(result.message.contains("没有可重载的模型"))
    }

    /** 记录引擎调用序列；[failures] 按 load 顺序消费，用尽后成功。 */
    private class RecordingEngine(
        private val failures: MutableList<Throwable> = mutableListOf(),
        initiallyLoaded: Boolean = true,
    ) : LlmEnginePort {

        val calls = mutableListOf<String>()
        private val loaded = MutableStateFlow(initiallyLoaded)
        private val locked = MutableStateFlow(ComputeBackend.AUTO)
        private val active = MutableStateFlow<ActiveBackend?>(null)

        override val lockedBackend: StateFlow<ComputeBackend> = locked
        override val activeBackend: StateFlow<ActiveBackend?> = active
        override val isLoaded: StateFlow<Boolean> = loaded

        override suspend fun load(modelSource: String, config: LlmLoadConfig): Result<Unit> {
            calls += "load:${config.backend}"
            val failure = failures.removeFirstOrNull()
            if (failure != null) {
                loaded.value = false
                active.value = null
                return Result.failure(failure)
            }
            locked.value = config.backend
            active.value = ActiveBackend.CPU
            loaded.value = true
            return Result.success(Unit)
        }

        override suspend fun unload() {
            calls += "unload"
            loaded.value = false
            active.value = null
        }

        override fun generate(request: LlmRequest): Flow<LlmStreamEvent> = emptyFlow()

        override suspend fun cancel() = Unit
    }
}
