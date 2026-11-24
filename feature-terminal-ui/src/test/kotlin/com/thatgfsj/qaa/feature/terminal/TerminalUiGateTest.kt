package com.thatgfsj.qaa.feature.terminal

import com.thatgfsj.qaa.core.harness.ConfirmationDecision
import com.thatgfsj.qaa.core.harness.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TerminalUiGate 状态机单测：fake 确认区宿主驱动挂起、Allow、Deny 与取消传播。 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalUiGateTest {

    /** fake 确认区宿主：与 [TerminalViewModel] 同形，但不依赖 Android ViewModel。 */
    private class FakeHost : TerminalToolConfirmHost {
        private val mutableState = MutableStateFlow(TerminalUiState())
        private var nextRequestId = 1L

        override val uiState: StateFlow<TerminalUiState> = mutableState.asStateFlow()

        override fun requestToolConfirmation(description: String) {
            mutableState.value = mutableState.value.copy(
                toolConfirm = ToolConfirmState.Pending(nextRequestId++, description),
            )
        }

        override fun dismissToolConfirmation() {
            if (mutableState.value.toolConfirm is ToolConfirmState.Pending) {
                mutableState.value = mutableState.value.copy(toolConfirm = ToolConfirmState.Idle)
            }
        }

        fun allow() = resolve(allowed = true)

        fun deny() = resolve(allowed = false)

        /** 模拟 yes / no 长按：写入会话级策略。 */
        fun remember(allowed: Boolean) {
            mutableState.value = mutableState.value.copy(
                confirmPolicy = ToolConfirmPolicy.forDecision(allowed),
            )
        }

        private fun resolve(allowed: Boolean) {
            val pending = mutableState.value.toolConfirm as? ToolConfirmState.Pending ?: return
            mutableState.value = mutableState.value.copy(
                toolConfirm = ToolConfirmState.Resolved(pending.requestId, pending.description, allowed),
            )
        }
    }

    private val writeCall = ToolCall("write", """{"path":"a.txt","content":"hi"}""")

    @Test
    fun 挂起后Allow完成_文案含工具名与参数摘要() = runTest {
        val host = FakeHost()
        val gate = TerminalUiGate(host)
        val decision = async { gate.awaitConfirmation(writeCall) }
        runCurrent()

        val pending = host.uiState.value.toolConfirm as? ToolConfirmState.Pending
        assertNotNull(pending)
        assertTrue(pending!!.description.startsWith("write "))
        assertTrue(pending.description.contains("a.txt"))
        assertFalse(decision.isCompleted)

        host.allow()
        assertEquals(ConfirmationDecision.ALLOW, decision.await())
    }

    @Test
    fun 挂起后Deny完成() = runTest {
        val host = FakeHost()
        val gate = TerminalUiGate(host)
        val decision = async { gate.awaitConfirmation(writeCall) }
        runCurrent()
        host.deny()
        assertEquals(ConfirmationDecision.DENY, decision.await())
    }

    @Test
    fun 挂起中取消_收起确认项且向上传播() = runTest {
        val host = FakeHost()
        val gate = TerminalUiGate(host)
        var cancelled: CancellationException? = null
        val job = launch {
            try {
                gate.awaitConfirmation(writeCall)
            } catch (e: CancellationException) {
                cancelled = e
                throw e
            }
        }
        runCurrent()
        assertTrue(host.uiState.value.toolConfirm is ToolConfirmState.Pending)

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertNotNull(cancelled)
        assertEquals(ToolConfirmState.Idle, host.uiState.value.toolConfirm)
    }

    @Test
    fun 残留旧决策不被误取() = runTest {
        val host = FakeHost()
        val gate = TerminalUiGate(host)
        // 制造一条历史 Resolved（旧的允许结果仍显示在确认区）。
        host.requestToolConfirmation("read 路径=\"old.txt\"")
        host.allow()
        assertTrue(host.uiState.value.toolConfirm is ToolConfirmState.Resolved)

        val decision = async { gate.awaitConfirmation(writeCall) }
        runCurrent()
        assertFalse(decision.isCompleted) // requestId 不同，必须继续等待本次决策
        host.deny()
        assertEquals(ConfirmationDecision.DENY, decision.await())
    }

    // ---- #93 会话级自动决策 ----

    @Test
    fun 会话级自动放行不挂起并上报策略() = runTest {
        val host = FakeHost()
        host.remember(allowed = true)
        var reported: Pair<String, ToolConfirmPolicy>? = null
        val gate = TerminalUiGate(host) { description, policy -> reported = description to policy }

        assertEquals(ConfirmationDecision.ALLOW, gate.awaitConfirmation(writeCall))
        assertEquals(ToolConfirmState.Idle, host.uiState.value.toolConfirm)
        assertNotNull(reported)
        assertTrue(reported!!.first.startsWith("write "))
        assertEquals(ToolConfirmPolicy.AUTO_ALLOW, reported!!.second)
    }

    @Test
    fun 会话级自动拒绝不挂起并上报策略() = runTest {
        val host = FakeHost()
        host.remember(allowed = false)
        var reported: Pair<String, ToolConfirmPolicy>? = null
        val gate = TerminalUiGate(host) { description, policy -> reported = description to policy }

        assertEquals(ConfirmationDecision.DENY, gate.awaitConfirmation(writeCall))
        assertEquals(ToolConfirmState.Idle, host.uiState.value.toolConfirm)
        assertEquals(ToolConfirmPolicy.AUTO_DENY, reported!!.second)
    }
}
