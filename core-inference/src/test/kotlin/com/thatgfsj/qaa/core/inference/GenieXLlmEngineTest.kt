package com.thatgfsj.qaa.core.inference

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** GenieX 引擎装配面：create 级降级链、取消传播、指标事件（Issue #5）。 */
class GenieXLlmEngineTest {

    private val modelPath = "/models/smoke.gguf"

    private fun engine(
        factory: LlmSessionFactory,
        initializer: SdkInitializer = SdkInitializer { Result.success(Unit) },
        resolver: ModelResolver = ModelResolver { Result.success(modelPath) },
    ): GenieXLlmEngine = GenieXLlmEngine(
        initializer = initializer,
        modelResolver = resolver,
        sessionFactory = factory,
    )

    @Test
    fun loadDrivesFallbackChainAndReportsHitTier() = runTest {
        val factory = FakeLlmSessionFactory(
            failures = mapOf(
                ActiveBackend.NPU to IllegalStateException("npu unavailable"),
                ActiveBackend.HYBRID to IllegalStateException("hybrid unavailable"),
            ),
        )
        val engine = engine(factory)

        val result = engine.load(modelPath)

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(ActiveBackend.NPU, ActiveBackend.HYBRID, ActiveBackend.GPU),
            factory.createdTiers,
        )
        assertEquals(ActiveBackend.GPU, engine.activeBackend.value)
        assertEquals(ComputeBackend.AUTO, engine.lockedBackend.value)
        assertTrue(engine.isLoaded.value)
    }

    @Test
    fun loadFailureReturnsAggregatedErrorAndStaysUnloaded() = runTest {
        val factory = FakeLlmSessionFactory(
            failures = ActiveBackend.entries.associateWith { tier ->
                IllegalStateException(tier.computeUnit + " unavailable")
            },
        )
        val engine = engine(factory)

        val result = engine.load(modelPath)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is BackendFallbackException)
        assertFalse(engine.isLoaded.value)
        assertNull(engine.activeBackend.value)
    }

    @Test
    fun lockedBackendOnlyCreatesItself() = runTest {
        val factory = FakeLlmSessionFactory(
            failures = mapOf(ActiveBackend.CPU to IllegalStateException("cpu unavailable")),
        )
        val engine = engine(factory)

        val result = engine.load(modelPath, LlmLoadConfig(backend = ComputeBackend.CPU))

        assertTrue(result.isFailure)
        assertEquals(listOf(ActiveBackend.CPU), factory.createdTiers)
        assertEquals(ComputeBackend.CPU, engine.lockedBackend.value)
        assertNull(engine.activeBackend.value)
    }

    @Test
    fun cancellationPropagatesToSessionStop() = runTest {
        val session = FakeLlmSession(
            tokens = listOf("a", "b", "c"),
            tokenDelayMillis = 100L,
        )
        val engine = engine(LlmSessionFactory { _, _, _ -> Result.success(session) })
        engine.load(modelPath).getOrThrow()

        val received = mutableListOf<String>()
        engine.generate(LlmRequest(listOf(LlmMessage.user("你好"))))
            .take(1)
            .collect { event ->
                if (event is LlmStreamEvent.Token) received += event.text
            }

        assertEquals(listOf("a"), received)
        assertEquals(1, session.cancelCount.get())
    }

    @Test
    fun completedEventCarriesMetrics() = runTest {
        val metrics = InferenceMetrics(
            ttftMs = 3.0,
            promptTokens = 5L,
            generatedTokens = 2L,
            prefillSpeed = 100.0,
            decodingSpeed = 20.0,
            stopReason = "eos",
            nCtx = 2048,
            backend = ActiveBackend.CPU,
        )
        val session = FakeLlmSession(tokens = listOf("x"), metrics = metrics)
        val engine = engine(LlmSessionFactory { _, _, _ -> Result.success(session) })
        engine.load(modelPath, LlmLoadConfig(nCtx = 2048)).getOrThrow()

        val events = engine.generate(LlmRequest(listOf(LlmMessage.user("你好")))).toList()

        assertEquals(
            listOf<LlmStreamEvent>(LlmStreamEvent.Token("x"), LlmStreamEvent.Completed(metrics)),
            events,
        )
    }

    @Test
    fun unloadClosesSessionAndResetsState() = runTest {
        val session = FakeLlmSession()
        val engine = engine(LlmSessionFactory { _, _, _ -> Result.success(session) })
        engine.load(modelPath).getOrThrow()

        engine.unload()

        assertEquals(1, session.closeCount.get())
        assertFalse(engine.isLoaded.value)
        assertNull(engine.activeBackend.value)
        assertEquals(ComputeBackend.AUTO, engine.lockedBackend.value)
    }

    @Test
    fun generateBeforeLoadFailsFast() = runTest {
        val engine = engine(FakeLlmSessionFactory())

        val failure = runCatching {
            engine.generate(LlmRequest(listOf(LlmMessage.user("你好")))).toList()
        }.exceptionOrNull()

        assertTrue(failure is LlmNotLoadedException)
    }
}
