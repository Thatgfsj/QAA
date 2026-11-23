package com.thatgfsj.qaa.core.inference

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 降级链顺序、锁定不回退、全败聚合（Issue #5）。 */
class BackendFallbackManagerTest {

    @Test
    fun autoChainFallsBackNpuHybridGpuCpu() = runTest {
        val manager = BackendFallbackManager()
        val calls = mutableListOf<ActiveBackend>()

        val result = manager.create(ComputeBackend.AUTO) { tier ->
            calls += tier
            if (tier == ActiveBackend.CPU) {
                Result.success("cpu-session")
            } else {
                Result.failure(IllegalStateException(tier.computeUnit + " create failed"))
            }
        }

        assertEquals(
            listOf(ActiveBackend.NPU, ActiveBackend.HYBRID, ActiveBackend.GPU, ActiveBackend.CPU),
            calls,
        )
        val success = result.getOrThrow()
        assertEquals("cpu-session", success.value)
        assertEquals(ActiveBackend.CPU, success.tier)
        assertEquals(ActiveBackend.CPU, manager.activeBackend.value)
        assertEquals(ComputeBackend.AUTO, manager.lockedBackend.value)
        assertEquals(
            listOf(ActiveBackend.NPU, ActiveBackend.HYBRID, ActiveBackend.GPU),
            manager.lastAttempts.value.map { it.tier },
        )
    }

    @Test
    fun autoChainStopsAtFirstSuccess() = runTest {
        val manager = BackendFallbackManager()
        val calls = mutableListOf<ActiveBackend>()

        val result = manager.create(ComputeBackend.AUTO) { tier ->
            calls += tier
            if (tier == ActiveBackend.HYBRID) {
                Result.success("hybrid-session")
            } else {
                Result.failure(IllegalStateException("unavailable"))
            }
        }

        assertEquals(listOf(ActiveBackend.NPU, ActiveBackend.HYBRID), calls)
        assertEquals(ActiveBackend.HYBRID, result.getOrThrow().tier)
        assertEquals(ActiveBackend.HYBRID, manager.activeBackend.value)
        assertEquals(1, manager.lastAttempts.value.size)
    }

    @Test
    fun lockedBackendDoesNotFallBack() = runTest {
        val manager = BackendFallbackManager()
        val calls = mutableListOf<ActiveBackend>()

        val result = manager.create<Unit>(ComputeBackend.NPU) { tier ->
            calls += tier
            Result.failure(IllegalStateException("npu create failed"))
        }

        assertTrue(result.isFailure)
        assertEquals(listOf(ActiveBackend.NPU), calls)
        assertEquals(ComputeBackend.NPU, manager.lockedBackend.value)
        assertNull(manager.activeBackend.value)
    }

    @Test
    fun allTiersFailedReturnsAggregatedError() = runTest {
        val manager = BackendFallbackManager()

        val result = manager.create<String>(ComputeBackend.AUTO) { tier ->
            Result.failure(IllegalStateException(tier.computeUnit + " boom"))
        }

        val error = result.exceptionOrNull() as BackendFallbackException
        assertEquals(ComputeBackend.AUTO, error.locked)
        assertEquals(
            listOf(ActiveBackend.NPU, ActiveBackend.HYBRID, ActiveBackend.GPU, ActiveBackend.CPU),
            error.attempts.map { it.tier },
        )
        assertEquals(4, error.suppressed.size)
        assertTrue(error.message!!.contains("AUTO"))
        assertTrue(error.message!!.contains("npu"))
        assertTrue(error.message!!.contains("cpu"))
        assertNull(manager.activeBackend.value)
    }

    @Test
    fun gpuHitIsReportedAsGpu() = runTest {
        val manager = BackendFallbackManager()

        val result = manager.create(ComputeBackend.AUTO) { tier ->
            if (tier == ActiveBackend.GPU) {
                Result.success("gpu-session")
            } else {
                Result.failure(IllegalStateException("unavailable"))
            }
        }

        assertEquals(ActiveBackend.GPU, result.getOrThrow().tier)
        assertEquals(ActiveBackend.GPU, manager.activeBackend.value)
        assertEquals(ComputeBackend.AUTO, manager.lockedBackend.value)
    }

    @Test
    fun creatorExceptionIsTreatedAsFailure() = runTest {
        val manager = BackendFallbackManager()

        val result = manager.create(ComputeBackend.AUTO) { tier ->
            if (tier == ActiveBackend.NPU) {
                throw IllegalStateException("npu exploded")
            }
            Result.success("ok")
        }

        assertEquals(ActiveBackend.HYBRID, result.getOrThrow().tier)
        assertEquals("npu exploded", manager.lastAttempts.value.single().error)
    }
}
