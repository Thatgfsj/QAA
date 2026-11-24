package com.thatgfsj.qaa.assembly

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #104 载入闸门单测：载入期的排队语义与「载入中」标记生命周期。 */
class ModelLoadGateTest {

    @Test
    fun 载入期间标记为进行中_结束后清除() = runTest {
        val gate = ModelLoadGate()
        val entered = AtomicBoolean(false)
        val job = launch {
            gate.withLoad {
                entered.set(true)
                assertTrue("载入执行中应标记为进行中", gate.isLoadInProgress)
            }
        }
        advanceUntilIdle()
        job.join()
        assertTrue(entered.get())
        assertFalse("载入结束后标记应清除", gate.isLoadInProgress)
    }

    @Test
    fun 等待方_等载入结束才继续() = runTest {
        val gate = ModelLoadGate()
        val order = mutableListOf<String>()
        val loadJob = launch {
            gate.withLoad {
                order += "载入开始"
                delay(100)
                order += "载入结束"
            }
        }
        val waiter = launch {
            gate.awaitIdle()
            order += "排队轮次执行"
        }
        advanceUntilIdle()
        loadJob.join()
        waiter.join()
        assertEquals(listOf("载入开始", "载入结束", "排队轮次执行"), order)
    }

    @Test
    fun 无载入时等待立即返回() = runTest {
        val gate = ModelLoadGate()
        gate.awaitIdle()
        assertFalse(gate.isLoadInProgress)
    }

    @Test
    fun 载入失败也清除进行中标记() = runTest {
        val gate = ModelLoadGate()
        val job = launch {
            runCatching { gate.withLoad { error("解码器不兼容") } }
        }
        advanceUntilIdle()
        job.join()
        assertFalse(gate.isLoadInProgress)
    }

    @Test
    fun 排队回执_区分有无模型名() {
        val plain = "模型载入中：指令已排队，载入完成后自动执行。"
        assertEquals(plain, queuedPromptNotice(null))
        assertEquals(plain, queuedPromptNotice("  "))
        assertEquals(
            "模型载入中（Qwen3-4B）：指令已排队，载入完成后自动执行。",
            queuedPromptNotice("Qwen3-4B"),
        )
    }
}
