package com.thatgfsj.qaa.core.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 设计附录 A.9：M1/M2/M3/M5/M6/M7 与终止边。 */
class ModelMemoryCoordinatorTest {

    private val request = CoordinatorFixture.REQUEST

    @Test
    fun transitionTableHasSixStatesAndSevenEdges() {
        val edges = ModelMemoryCoordinator.TRANSITIONS
        assertEquals(7, edges.size)
        assertEquals(6, edges.count { it.to != null })
        assertEquals(1, edges.count { it.to == null })
        assertEquals(6, ModelMemoryCoordinator.CYCLE_EDGES.size)
        assertEquals(CoordinatorState.values().toSet(), edges.map { it.from }.toSet())
        assertEquals(
            setOf(
                CoordinatorState.LLM_UNLOADING,
                CoordinatorState.IMAGEGEN_LOADING,
                CoordinatorState.IMAGEGEN_RUNNING,
                CoordinatorState.IMAGEGEN_UNLOADING,
                CoordinatorState.LLM_RELOADING,
                CoordinatorState.LLM_LOADED,
            ),
            ModelMemoryCoordinator.CYCLE_EDGES.mapNotNull { it.to }.toSet(),
        )
        assertEquals(1, ModelMemoryCoordinator.allowedTargets(CoordinatorState.LLM_LOADED).size)
    }

    @Test
    fun m1LegalChainCommitsSixEdgesInOrder() = runTest {
        val f = CoordinatorFixture()
        val expected = listOf(
            CoordinatorState.LLM_UNLOADING,
            CoordinatorState.IMAGEGEN_LOADING,
            CoordinatorState.IMAGEGEN_RUNNING,
            CoordinatorState.IMAGEGEN_UNLOADING,
            CoordinatorState.LLM_RELOADING,
            CoordinatorState.LLM_LOADED,
        )
        expected.forEach { target ->
            val outcome = f.coordinator.requestTransition(target, request)
            assertTrue(outcome is TransitionOutcome.Committed, "应提交 " + target + "，实际 " + outcome)
            assertEquals(target, f.coordinator.state.value)
            assertEquals(1, (outcome as TransitionOutcome.Committed).attempts)
        }
        assertEquals(1, f.llm.unloadCount)
        assertEquals(1, f.imageGen.loadCount)
        assertEquals(1, f.imageGen.generateCount)
        assertEquals(1, f.imageGen.unloadCount)
        assertEquals(1, f.llm.reloadCount)
        assertEquals(1, f.llm.injectCount)
        assertEquals(2, f.probe.readings.size)
        assertEquals(1, f.output.inspected.size)
        assertEquals("images/cat.png", f.llm.lastInjectedPath)
        assertEquals("一张测试图", f.llm.lastInjectedSummary)
        assertTrue(f.sleeper.delays.isEmpty())
    }

    @Test
    fun m2AllIllegalEdgesRejectedWithStateUnchanged() = runTest {
        val f = CoordinatorFixture()
        val chain = listOf(
            CoordinatorState.LLM_UNLOADING,
            CoordinatorState.IMAGEGEN_LOADING,
            CoordinatorState.IMAGEGEN_RUNNING,
            CoordinatorState.IMAGEGEN_UNLOADING,
            CoordinatorState.LLM_RELOADING,
            CoordinatorState.LLM_LOADED,
        )
        var checked = 0
        for (target in chain) {
            val current = f.coordinator.state.value
            val allowed = ModelMemoryCoordinator.allowedTargets(current)
            for (candidate in CoordinatorState.values()) {
                if (candidate == current || candidate in allowed) continue
                val error = try {
                    f.coordinator.requestTransition(candidate, request)
                    null
                } catch (e: IllegalTransitionException) {
                    e
                }
                assertNotNull(error, "应拒绝 " + current + " -> " + candidate)
                assertEquals(current, error!!.currentState)
                assertEquals(candidate, error.requestedState)
                assertEquals(current, f.coordinator.state.value)
                checked++
            }
            val outcome = f.coordinator.requestTransition(target, request)
            assertTrue(outcome is TransitionOutcome.Committed)
        }
        assertEquals(24, checked)
        assertEquals(CoordinatorState.LLM_LOADED, f.coordinator.state.value)
    }

    @Test
    fun m3IdempotentWhenTargetEqualsCurrent() = runTest {
        val f = CoordinatorFixture()
        val initial = f.coordinator.requestTransition(CoordinatorState.LLM_LOADED, request)
        assertTrue(initial is TransitionOutcome.AlreadyInTarget)
        assertEquals(0, f.llm.unloadCount)

        f.driveTo(CoordinatorState.IMAGEGEN_RUNNING)
        val idempotent = f.coordinator.requestTransition(CoordinatorState.IMAGEGEN_RUNNING, request)
        assertTrue(idempotent is TransitionOutcome.AlreadyInTarget)
        assertEquals(1, f.imageGen.generateCount)
    }

    @Test
    fun m5MutualExclusionInvariantHoldsAcrossPhases() = runTest {
        val f = CoordinatorFixture()
        f.driveTo(CoordinatorState.IMAGEGEN_LOADING)
        assertTrue(f.imageGen.isLoaded)
        assertFalse(f.llm.isLoaded)
        assertEquals(0, f.llm.referenceCount)

        f.driveTo(CoordinatorState.IMAGEGEN_UNLOADING)
        assertFalse(f.imageGen.isLoaded)
        assertEquals(0, f.imageGen.referenceCount)

        f.driveTo(CoordinatorState.LLM_LOADED)
        assertTrue(f.llm.isLoaded)
        assertEquals(0, f.imageGen.referenceCount)
    }

    @Test
    fun m6ConcurrentRequestsAreSerializedBySingleActor() = runBlocking {
        val f = CoordinatorFixture()
        f.llm.unloadDelayMillis = 50L
        val first = async(Dispatchers.Default) {
            f.coordinator.requestTransition(CoordinatorState.LLM_UNLOADING, request)
        }
        val second = async(Dispatchers.Default) {
            f.coordinator.requestTransition(CoordinatorState.LLM_UNLOADING, request)
        }
        val outcomes = listOf(first.await(), second.await())
        assertEquals(1, f.llm.unloadCount)
        assertEquals(1, outcomes.count { it is TransitionOutcome.Committed })
        assertEquals(1, outcomes.count { it is TransitionOutcome.AlreadyInTarget })
        assertEquals(CoordinatorState.LLM_UNLOADING, f.coordinator.state.value)
    }

    @Test
    fun m7ImageCycleRejectedWhenNotLlmLoaded() = runTest {
        val f = CoordinatorFixture()
        f.driveTo(CoordinatorState.LLM_UNLOADING)
        val error = try {
            f.coordinator.requestImageGenCycle("p", "images/x.png")
            null
        } catch (e: IllegalTransitionException) {
            e
        }
        assertNotNull(error)
        assertEquals(CoordinatorState.LLM_UNLOADING, error!!.currentState)
        assertEquals(CoordinatorState.LLM_UNLOADING, error.requestedState)
        assertEquals(CoordinatorState.LLM_UNLOADING, f.coordinator.state.value)
        assertEquals(0, f.imageGen.generateCount)
    }

    @Test
    fun imageCycleDrivesFullChainAndCarriesEnginePath() = runTest {
        val f = CoordinatorFixture()
        val outcome = f.coordinator.requestImageGenCycle(
            prompt = "猫",
            outputPath = "images/cat.png",
            enginePath = "/tmp/cat.png",
            summary = "兜底摘要",
        )
        assertTrue(outcome is ImageGenCycleOutcome.Success)
        val success = outcome as ImageGenCycleOutcome.Success
        assertEquals("images/cat.png", success.outputPath)
        assertEquals("一张测试图", success.summary)
        assertEquals(CoordinatorState.LLM_LOADED, success.state)
        assertEquals("/tmp/cat.png", f.imageGen.lastOutputPath)
        assertEquals("images/cat.png", f.llm.lastInjectedPath)
        assertEquals(CoordinatorState.LLM_LOADED, f.coordinator.state.value)
    }

    @Test
    fun terminateReleasesAllAndIsIdempotent() = runTest {
        val f = CoordinatorFixture()
        assertEquals(TerminationOutcome.Terminated, f.coordinator.terminate())
        assertTrue(f.coordinator.isTerminated.value)
        assertEquals(TerminationOutcome.AlreadyTerminated, f.coordinator.terminate())
        assertEquals(1, f.llm.destroyCount)
        assertEquals(1, f.imageGen.destroyCount)
    }

    @Test
    fun terminateRejectedOutsideLlmLoaded() = runTest {
        val f = CoordinatorFixture()
        f.driveTo(CoordinatorState.LLM_UNLOADING)
        val error = try {
            f.coordinator.terminate()
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull(error)
        assertEquals(CoordinatorState.LLM_UNLOADING, f.coordinator.state.value)
        assertFalse(f.coordinator.isTerminated.value)
    }
}
