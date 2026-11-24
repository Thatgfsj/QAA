package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.CompactionResult
import com.thatgfsj.qaa.core.harness.SkipReason
import com.thatgfsj.qaa.core.inference.ActiveBackend
import com.thatgfsj.qaa.core.inference.ComputeBackend
import com.thatgfsj.qaa.core.inference.LlmEnginePort
import com.thatgfsj.qaa.core.inference.LlmLoadConfig
import com.thatgfsj.qaa.core.inference.LlmMessage
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

/** 上下文安全网与生成前预检单测（#106 决策 5 / 预算公式）。 */
class ContextLoadGuardTest {

    @Test
    fun 载入失败时回退4096重试一次并回调记录() = runTest {
        val engine = FakeEngine(failures = mutableListOf(RuntimeException("nCtx 32768 分配失败")))
        val fallbacks = mutableListOf<Pair<Int, String>>()
        val outcome = ContextLoadFallback(engine) { requested, failure ->
            fallbacks += requested to (failure.message ?: "")
        }.load("model.gguf", LlmLoadConfig(nCtx = 32768))

        assertEquals(listOf("load:32768", "unload", "load:4096"), engine.calls)
        assertEquals(ContextLoadOutcome.Loaded(4096), outcome)
        assertEquals(32768, fallbacks.single().first)
        assertTrue(fallbacks.single().second.contains("分配失败"))
    }

    @Test
    fun 回退重试仍失败时判定失败且只重试一次() = runTest {
        val engine = FakeEngine(
            failures = mutableListOf(RuntimeException("第一次失败"), RuntimeException("第二次失败")),
        )
        val outcome = ContextLoadFallback(engine).load("model.gguf", LlmLoadConfig(nCtx = 32768))

        assertEquals(listOf("load:32768", "unload", "load:4096"), engine.calls)
        assertTrue(outcome is ContextLoadOutcome.Failed)
        assertTrue((outcome as ContextLoadOutcome.Failed).cause.message!!.contains("第二次失败"))
    }

    @Test
    fun 请求档已是4096时失败不重复重试() = runTest {
        val engine = FakeEngine(failures = mutableListOf(RuntimeException("依然失败")))
        val outcome = ContextLoadFallback(engine).load("model.gguf", LlmLoadConfig(nCtx = 4096))

        assertEquals(listOf("load:4096"), engine.calls)
        assertTrue(outcome is ContextLoadOutcome.Failed)
    }

    @Test
    fun 安全网回执包含回退档与固定命令() {
        val text = describeContextFallback(32768, 4096)

        assertTrue(text.contains("32768"))
        assertTrue(text.contains("已回退 4096"))
        assertTrue(text.contains("/ctx 4096"))
    }

    @Test
    fun 预检三要素按输出预留判定越界() {
        val guard = ContextPreflightGuard()

        // 3900 + 100 + 512 = 4512 > 4096
        assertTrue(guard.overflows(3900, 100, 4096))
        // 无新输入时输出预留仍让 3900 越界（3900 + 0 + 512 > 4096）
        assertTrue(guard.overflows(3900, 0, 4096))
        // 3450 + 100 + 512 = 4062 ≤ 4096 放行
        assertFalse(guard.overflows(3450, 100, 4096))
    }

    @Test
    fun 预检越界且压缩无效时拒绝生成且零generate() = runTest {
        val guard = ContextPreflightGuard()
        val engine = FakeEngine()
        val notices = mutableListOf<String>()
        var generateCalls = 0

        val admitted = guard.admit(
            text = "继续",
            lastPromptTokens = 3900,
            estimatedHistory = { 3900 },
            nCtx = 4096,
            compact = {
                engine.calls += "compact:$it"
                CompactionResult.Skipped(SkipReason.NOTHING_TO_COMPACT)
            },
        )
        if (admitted) {
            engine.generate(LlmRequest(listOf(LlmMessage.user("继续")))).collect { }
            generateCalls += 1
        } else {
            notices += AgentRuntime.PREFLIGHT_INSUFFICIENT_TEXT
        }

        assertEquals(0, generateCalls)
        assertEquals(0, engine.generates)
        assertEquals("上下文不足：请 /new 或 /compact", notices.single())
        assertTrue(engine.calls.single().startsWith("compact:"))
    }

    @Test
    fun 预检越界但压缩后回落时放行() = runTest {
        val guard = ContextPreflightGuard()
        var compactCalls = 0

        val admitted = guard.admit(
            text = "继续",
            lastPromptTokens = 3900,
            estimatedHistory = { if (compactCalls == 0) 3900 else 1200 },
            nCtx = 4096,
            compact = {
                compactCalls += 1
                CompactionResult.Skipped(SkipReason.NOTHING_TO_COMPACT)
            },
        )

        assertTrue(admitted)
        assertEquals(1, compactCalls)
    }

    @Test
    fun 预检未越界时不压缩直接放行() = runTest {
        val guard = ContextPreflightGuard()
        var compactCalls = 0

        val admitted = guard.admit(
            text = "你好",
            lastPromptTokens = 500,
            estimatedHistory = { 500 },
            nCtx = 32768,
            compact = {
                compactCalls += 1
                CompactionResult.Skipped(SkipReason.BELOW_THRESHOLD)
            },
        )

        assertTrue(admitted)
        assertEquals(0, compactCalls)
    }

    /** 记录 load / generate 调用的引擎替身；[failures] 按 load 顺序消费。 */
    private class FakeEngine(
        private val failures: MutableList<Throwable> = mutableListOf(),
    ) : LlmEnginePort {

        val calls = mutableListOf<String>()
        var generates = 0
        private val loaded = MutableStateFlow(true)

        override val lockedBackend: StateFlow<ComputeBackend> = MutableStateFlow(ComputeBackend.AUTO)
        override val activeBackend: StateFlow<ActiveBackend?> = MutableStateFlow(null)
        override val isLoaded: StateFlow<Boolean> = loaded

        override suspend fun load(modelSource: String, config: LlmLoadConfig): Result<Unit> {
            calls += "load:${config.nCtx}"
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

        override fun generate(request: LlmRequest): Flow<LlmStreamEvent> {
            generates += 1
            return emptyFlow()
        }

        override suspend fun cancel() = Unit
    }
}
