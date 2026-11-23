package com.thatgfsj.qaa.assembly

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** token 转发器单测：惰性开流、生成步收口、静默与异常收口。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantTokenRelayTest {

    @Test
    fun 首个片段惰性开流并逐片段转发() = runTest {
        val relay = AssistantTokenRelay()
        val events = mutableListOf<AssistantStreamEvent>()
        backgroundScope.launch { relay.events.collect { events += it } }
        runCurrent()

        relay.emit("你")
        relay.emit("好")
        runCurrent()

        assertEquals(
            listOf(
                AssistantStreamEvent.Started(1),
                AssistantStreamEvent.Delta(1, "你"),
                AssistantStreamEvent.Delta(1, "好"),
            ),
            events,
        )
    }

    @Test
    fun 生成步收口报告已流式并携带定稿文本() = runTest {
        val relay = AssistantTokenRelay()
        val events = mutableListOf<AssistantStreamEvent>()
        backgroundScope.launch { relay.events.collect { events += it } }
        runCurrent()

        relay.emit("你")
        assertTrue(relay.finishStep("你好"))
        assertFalse(relay.finishStep("你好"))
        runCurrent()

        assertEquals(
            listOf(
                AssistantStreamEvent.Started(1),
                AssistantStreamEvent.Delta(1, "你"),
                AssistantStreamEvent.Finished(1, "你好"),
            ),
            events,
        )
    }

    @Test
    fun 未流式的生成步不产生事件() = runTest {
        val relay = AssistantTokenRelay()
        val events = mutableListOf<AssistantStreamEvent>()
        backgroundScope.launch { relay.events.collect { events += it } }
        runCurrent()

        assertFalse(relay.finishStep("整段文本"))
        runCurrent()

        assertTrue(events.isEmpty())
    }

    @Test
    fun 静默期间片段丢弃且不开启新流() = runTest {
        val relay = AssistantTokenRelay()
        val events = mutableListOf<AssistantStreamEvent>()
        backgroundScope.launch { relay.events.collect { events += it } }
        runCurrent()

        relay.silenced = true
        relay.emit("摘要内容")
        assertFalse(relay.finishStep("摘要内容"))
        runCurrent()
        assertTrue(events.isEmpty())

        relay.silenced = false
        relay.emit("可见片段")
        runCurrent()
        assertEquals(AssistantStreamEvent.Started(1), events.first())
    }

    @Test
    fun 异常收口保留已流式内容并重开新流() = runTest {
        val relay = AssistantTokenRelay()
        val events = mutableListOf<AssistantStreamEvent>()
        backgroundScope.launch { relay.events.collect { events += it } }
        runCurrent()

        relay.emit("半截")
        relay.endTurn()
        relay.emit("下一轮")
        runCurrent()

        assertEquals(AssistantStreamEvent.Finished(1, null), events[2])
        assertEquals(AssistantStreamEvent.Started(2), events[3])
        assertEquals(AssistantStreamEvent.Delta(2, "下一轮"), events[4])
    }
}
