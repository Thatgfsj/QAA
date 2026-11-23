package com.thatgfsj.qaa.core.inference

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 设计附录 A.9：M4 退避/图外恢复与 §9.2 逐边 guard 校验点。 */
class ModelMemoryCoordinatorGuardTest {

    private val request = CoordinatorFixture.REQUEST

    @Test
    fun guard1RejectsMissingConfirmationAndUnpersistedContext() = runTest {
        val missing = CoordinatorFixture()
        val missingOutcome = missing.coordinator.requestTransition(
            CoordinatorState.LLM_UNLOADING,
            ImageGenCycleRequest("p", "images/a.png", confirmation = null),
        )
        val missingFailure = missingOutcome as TransitionOutcome.Failed
        assertTrue(missingFailure.reason.contains("确认"), missingFailure.reason)
        assertEquals(0, missing.llm.unloadCount)
        assertEquals(CoordinatorState.LLM_LOADED, missing.coordinator.state.value)

        val unpersisted = CoordinatorFixture()
        unpersisted.llm.persisted = false
        val second = unpersisted.coordinator.requestTransition(
            CoordinatorState.LLM_UNLOADING,
            request,
        ) as TransitionOutcome.Failed
        assertTrue(second.reason.contains("持久化"), second.reason)
        assertTrue(second.recovery is RecoveryOutcome.Rebuilt)
        assertEquals(CoordinatorState.LLM_LOADED, unpersisted.coordinator.state.value)
    }

    @Test
    fun guard2ChecksLlmReleaseBeforeMemoryWatermark() = runTest {
        val both = CoordinatorFixture()
        both.driveTo(CoordinatorState.LLM_UNLOADING)
        both.llm.refs = 1
        both.probe.level = 1L
        val bothOutcome = both.coordinator.requestTransition(
            CoordinatorState.IMAGEGEN_LOADING,
            request,
        ) as TransitionOutcome.Failed
        assertTrue(bothOutcome.reason.contains("引用"), bothOutcome.reason)
        assertEquals(0, both.imageGen.loadCount)

        val lowMemory = CoordinatorFixture()
        lowMemory.driveTo(CoordinatorState.LLM_UNLOADING)
        lowMemory.probe.level = 1L
        lowMemory.probe.readings.clear()
        val low = lowMemory.coordinator.requestTransition(
            CoordinatorState.IMAGEGEN_LOADING,
            request,
        ) as TransitionOutcome.Failed
        assertTrue(low.reason.contains("安全水位"), low.reason)
        assertEquals(3, low.attempts)
        assertEquals(3, lowMemory.probe.readings.size)
        assertEquals(0, lowMemory.imageGen.loadCount)
    }

    @Test
    fun guard3ChecksLlmAbsenceBeforeImageLoadCompletion() = runTest {
        val llmLoaded = CoordinatorFixture()
        llmLoaded.driveTo(CoordinatorState.IMAGEGEN_LOADING)
        llmLoaded.llm.loaded = true
        val first = llmLoaded.coordinator.requestTransition(
            CoordinatorState.IMAGEGEN_RUNNING,
            request,
        ) as TransitionOutcome.Failed
        assertTrue(first.reason.contains("同时驻留"), first.reason)
        assertEquals(0, llmLoaded.imageGen.generateCount)

        val notLoaded = CoordinatorFixture()
        notLoaded.driveTo(CoordinatorState.IMAGEGEN_LOADING)
        notLoaded.imageGen.loaded = false
        val second = notLoaded.coordinator.requestTransition(
            CoordinatorState.IMAGEGEN_RUNNING,
            request,
        ) as TransitionOutcome.Failed
        assertTrue(second.reason.contains("加载未完成"), second.reason)
        assertEquals(0, notLoaded.imageGen.generateCount)
    }

    @Test
    fun guard4RequiresNonEmptyOutputInsideSandbox() = runTest {
        val f = CoordinatorFixture()
        f.driveTo(CoordinatorState.IMAGEGEN_RUNNING)
        f.output.result = OutputFileCheck.Invalid("输出文件不存在: images/cat.png")
        val outcome = f.coordinator.requestTransition(
            CoordinatorState.IMAGEGEN_UNLOADING,
            request,
        ) as TransitionOutcome.Failed
        assertTrue(outcome.reason.contains("输出文件不存在"), outcome.reason)
        assertEquals(3, outcome.attempts)
        assertEquals(0, f.imageGen.unloadCount)
        assertEquals(CoordinatorState.LLM_LOADED, f.coordinator.state.value)
    }

    @Test
    fun guard5ChecksImageReleaseBeforeMemoryWatermark() = runTest {
        val f = CoordinatorFixture()
        f.driveTo(CoordinatorState.IMAGEGEN_UNLOADING)
        f.imageGen.refs = 1
        f.probe.level = 1L
        val outcome = f.coordinator.requestTransition(
            CoordinatorState.LLM_RELOADING,
            request,
        ) as TransitionOutcome.Failed
        assertTrue(outcome.reason.contains("引用"), outcome.reason)
        assertEquals(0, f.llm.reloadCount)
    }

    @Test
    fun guard6ChecksImageAbsenceBeforeReinjection() = runTest {
        val f = CoordinatorFixture()
        f.driveTo(CoordinatorState.LLM_RELOADING)
        f.imageGen.loaded = true
        f.llm.injected = false
        val first = f.coordinator.requestTransition(
            CoordinatorState.LLM_LOADED,
            request,
        ) as TransitionOutcome.Failed
        assertTrue(first.reason.contains("画图引擎"), first.reason)
        assertEquals(1, f.llm.injectCount)
    }

    @Test
    fun guard6RejectsMissingReinjection() = runTest {
        val f = CoordinatorFixture()
        f.driveTo(CoordinatorState.LLM_RELOADING)
        f.llm.injected = false
        val outcome = f.coordinator.requestTransition(
            CoordinatorState.LLM_LOADED,
            request,
        ) as TransitionOutcome.Failed
        assertTrue(outcome.reason.contains("回注"), outcome.reason)
        assertEquals(CoordinatorState.LLM_LOADED, f.coordinator.state.value)
    }

    @Test
    fun m4MemoryReclaimFailureRetriesThenRebuildsOutsideGraph() = runTest {
        val f = CoordinatorFixture()
        f.driveTo(CoordinatorState.LLM_UNLOADING)
        f.probe.level = 1L
        f.probe.readings.clear()
        val outcome = f.coordinator.requestTransition(
            CoordinatorState.IMAGEGEN_LOADING,
            request,
        ) as TransitionOutcome.Failed
        assertEquals(3, outcome.attempts)
        assertEquals(CoordinatorState.LLM_UNLOADING, outcome.from)
        assertEquals(CoordinatorState.IMAGEGEN_LOADING, outcome.requested)
        assertEquals(listOf(50L, 100L), f.sleeper.delays)
        assertTrue(outcome.recovery is RecoveryOutcome.Rebuilt)
        assertEquals(CoordinatorState.LLM_LOADED, f.coordinator.state.value)
        assertEquals(1, f.llm.destroyCount)
        assertEquals(1, f.imageGen.destroyCount)
        assertEquals(1, f.llm.rebuildCount)
        assertEquals(0, f.imageGen.loadCount)
    }

    @Test
    fun actionFailureRetriesThenRebuilds() = runTest {
        val f = CoordinatorFixture()
        f.driveTo(CoordinatorState.LLM_UNLOADING)
        f.imageGen.failLoad = true
        val outcome = f.coordinator.requestTransition(
            CoordinatorState.IMAGEGEN_LOADING,
            request,
        ) as TransitionOutcome.Failed
        assertEquals(3, f.imageGen.loadCount)
        assertEquals(listOf(50L, 100L), f.sleeper.delays)
        assertTrue(outcome.recovery is RecoveryOutcome.Rebuilt)
        assertEquals(CoordinatorState.LLM_LOADED, f.coordinator.state.value)
    }

    @Test
    fun recoveryFailureKeepsStateAndReportsReason() = runTest {
        val f = CoordinatorFixture()
        f.driveTo(CoordinatorState.LLM_UNLOADING)
        f.probe.level = 1L
        f.llm.failRebuild = true
        val outcome = f.coordinator.requestTransition(
            CoordinatorState.IMAGEGEN_LOADING,
            request,
        ) as TransitionOutcome.Failed
        assertTrue(outcome.recovery is RecoveryOutcome.Failed)
        assertEquals(CoordinatorState.LLM_UNLOADING, f.coordinator.state.value)
    }
}
