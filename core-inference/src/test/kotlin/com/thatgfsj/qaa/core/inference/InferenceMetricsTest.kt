package com.thatgfsj.qaa.core.inference

import com.geniex.sdk.bean.ProfilingData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** ProfilingData → InferenceMetrics 字段直映射（Issue #5）。 */
class InferenceMetricsTest {

    @Test
    fun mapsAllProfilingFields() {
        val profile = ProfilingData(
            ttftMs = 123.5,
            mediaMs = 0.0,
            promptTimeMs = 10.0,
            decodeTimeMs = 500.0,
            promptTokens = 42L,
            generatedTokens = 100L,
            prefillSpeed = 4200.0,
            decodingSpeed = 200.0,
            draftNTotal = 0L,
            draftNAccepted = 0L,
            stopReason = "length",
        )

        val metrics = InferenceMetrics.from(profile, nCtx = 4096, backend = ActiveBackend.NPU)

        assertEquals(123.5, metrics.ttftMs, 1e-9)
        assertEquals(42L, metrics.promptTokens)
        assertEquals(100L, metrics.generatedTokens)
        assertEquals(4200.0, metrics.prefillSpeed, 1e-9)
        assertEquals(200.0, metrics.decodingSpeed, 1e-9)
        assertEquals("length", metrics.stopReason)
        assertEquals(4096, metrics.nCtx)
        assertEquals(ActiveBackend.NPU, metrics.backend)
        assertEquals(142L, metrics.totalTokens)
        assertEquals(142f / 4096f, metrics.contextUsage!!, 1e-6f)
    }

    @Test
    fun contextUsageIsNullWithoutConfiguredContext() {
        val profile = ProfilingData(
            ttftMs = 1.0,
            mediaMs = 0.0,
            promptTimeMs = 1.0,
            decodeTimeMs = 1.0,
            promptTokens = 1L,
            generatedTokens = 1L,
            prefillSpeed = 1.0,
            decodingSpeed = 1.0,
            draftNTotal = 0L,
            draftNAccepted = 0L,
            stopReason = "eos",
        )

        val metrics = InferenceMetrics.from(profile)

        assertNull(metrics.contextUsage)
        assertEquals(0, metrics.nCtx)
        assertNull(metrics.backend)
    }
}
