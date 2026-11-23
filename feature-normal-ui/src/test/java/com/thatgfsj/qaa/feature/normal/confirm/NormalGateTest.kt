package com.thatgfsj.qaa.feature.normal.confirm

import com.thatgfsj.qaa.core.harness.ConfirmationDecision
import com.thatgfsj.qaa.core.harness.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NormalGate 状态机单测：挂起 / 允许 / 拒绝 / 取消传播。 */
@OptIn(ExperimentalCoroutinesApi::class)
class NormalGateTest {

    private val writeCall = ToolCall("write", """{"path":"a.txt","content":"hi"}""")

    @Test
    fun 挂起后Allow完成_文案为工具名与参数摘要() = runTest {
        val gate = NormalGate()
        val decision = async { gate.awaitConfirmation(writeCall) }
        runCurrent()

        val pending = gate.pending.value
        assertNotNull(pending)
        assertTrue(pending!!.description.startsWith("write "))
        assertTrue(pending.description.contains("a.txt"))
        assertFalse(decision.isCompleted)

        gate.allow()
        assertEquals(ConfirmationDecision.ALLOW, decision.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun 挂起后Deny完成() = runTest {
        val gate = NormalGate()
        val decision = async { gate.awaitConfirmation(writeCall) }
        runCurrent()
        gate.deny()
        assertEquals(ConfirmationDecision.DENY, decision.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun 挂起中取消_清空对话框并向上传播() = runTest {
        val gate = NormalGate()
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
        assertNotNull(gate.pending.value)

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertNotNull(cancelled)
        assertNull(gate.pending.value)
    }

    @Test
    fun 无挂起时决策为空操作() = runTest {
        val gate = NormalGate()
        gate.allow()
        gate.deny()
        assertNull(gate.pending.value)
    }
}
