package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SteerQueue 单测（设计文档 7.2/7.3 与附录 A.7）：优先序、队内 FIFO、多条 steer
 * 不覆盖、边界消费与续跑标记、peek 唤醒、清空语义、空白文本忽略。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SteerQueueTest {

    @Test
    fun Q1_steer恒先于followUp() = runTest {
        val queue = SteerQueue()
        queue.offerFollowUp("后续任务")
        queue.requestSteer("转向任务")

        val first = queue.nextTurnAction() as TurnAction.NewTurn
        assertEquals("转向任务", first.input.text)
        assertTrue(first.input.isSteer)

        val second = queue.nextTurnAction() as TurnAction.NewTurn
        assertEquals("后续任务", second.input.text)
        assertFalse(second.input.isSteer)

        assertNull(queue.nextTurnAction())
    }

    @Test
    fun Q2_同队列严格FIFO() = runTest {
        val queue = SteerQueue()
        queue.requestSteer("s1")
        queue.requestSteer("s2")
        queue.offerFollowUp("f1")
        queue.offerFollowUp("f2")

        val seen = mutableListOf<String>()
        while (true) {
            val action = queue.nextTurnAction() ?: break
            seen += (action as TurnAction.NewTurn).input.text
        }
        assertEquals(listOf("s1", "s2", "f1", "f2"), seen)
    }

    @Test
    fun Q4_多条steer不覆盖_边界消费后保留其余() = runTest {
        val queue = SteerQueue()
        queue.requestSteer("s1")
        queue.requestSteer("s2")

        // 工具步边界只消费一条，并置位续跑标记。
        assertEquals("s1", queue.interruptCheck())
        assertEquals(TurnAction.ResumeAfterSteer, queue.nextTurnAction())

        // s2 未被覆盖：作为下一条独立轮次输入。
        val next = queue.nextTurnAction() as TurnAction.NewTurn
        assertEquals("s2", next.input.text)
        assertTrue(next.input.isSteer)
        assertNull(queue.nextTurnAction())
    }

    @Test
    fun awaitSteer仅唤醒不消费() = runTest {
        val queue = SteerQueue()
        val seen = CompletableDeferred<String>()
        val waiter = launch { seen.complete(queue.awaitSteer()) }
        runCurrent()
        assertFalse(seen.isCompleted)

        queue.requestSteer("s1")
        waiter.join()

        assertEquals("s1", seen.await())
        // peek 语义：文本仍在队列，等待边界统一消费。
        assertTrue(queue.hasPendingSteer())
        assertEquals("s1", queue.interruptCheck())
        assertFalse(queue.hasPendingSteer())
    }

    @Test
    fun 清空队列_重置边界标记与待消费输入() = runTest {
        val queue = SteerQueue()
        queue.requestSteer("s1")
        queue.offerFollowUp("f1")
        queue.interruptCheck()

        queue.clear()

        assertFalse(queue.hasPendingSteer())
        assertFalse(queue.hasQueuedInput())
        assertNull(queue.nextTurnAction())
    }

    @Test
    fun 空白文本忽略_非空白首尾裁剪() = runTest {
        val queue = SteerQueue()
        queue.requestSteer("   ")
        queue.offerFollowUp("\n\t")
        assertFalse(queue.hasQueuedInput())
        assertNull(queue.nextTurnAction())

        queue.requestSteer("  转向  ")
        val action = queue.nextTurnAction() as TurnAction.NewTurn
        assertEquals("转向", action.input.text)
    }
}
