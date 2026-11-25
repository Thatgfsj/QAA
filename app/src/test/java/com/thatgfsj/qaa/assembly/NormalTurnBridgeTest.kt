package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.ToolCall
import com.thatgfsj.qaa.core.harness.ToolOutcome
import com.thatgfsj.qaa.core.harness.ToolOutcomeStatus
import com.thatgfsj.qaa.feature.normal.chat.CHAT_SEGMENT_AGENT_ID_BASE
import com.thatgfsj.qaa.feature.normal.chat.ChatSegment
import com.thatgfsj.qaa.feature.normal.chat.ConfirmSegmentState
import com.thatgfsj.qaa.feature.normal.chat.ToolSegmentStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Normal 会话页时间线桥单测（#127）：确认挂起 → 决策 → 执行 → 收口的段状态机。 */
@OptIn(ExperimentalCoroutinesApi::class)
class NormalTurnBridgeTest {

    private fun writeCall(path: String = "a.txt") =
        ToolCall("write", "{\"path\":\"$path\",\"content\":\"你好\"}")

    /** 订阅段流：backgroundScope 随测试结束自动取消。 */
    private fun TestScope.collectSegments(bridge: NormalTurnBridge): MutableList<ChatSegment> {
        val events = mutableListOf<ChatSegment>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bridge.segments.collect { events += it } }
        return events
    }

    @Test
    fun 确认挂起_先出等待确认卡再出内联操作条且共用工具id() = runTest {
        val bridge = NormalTurnBridge()
        val events = collectSegments(bridge)

        bridge.toolPending(writeCall())

        assertEquals(2, events.size)
        val tool = events[0] as ChatSegment.ToolCall
        val confirm = events[1] as ChatSegment.Confirm
        assertEquals(ToolSegmentStatus.WAITING_CONFIRM, tool.status)
        assertEquals("write", tool.name)
        assertEquals(ConfirmSegmentState.PENDING, confirm.state)
        assertEquals(tool.id, confirm.toolSegmentId)
        assertTrue(confirm.description.contains("write"))
        assertTrue(tool.id >= CHAT_SEGMENT_AGENT_ID_BASE)
    }

    @Test
    fun 允许后_操作条固化且工具卡转执行中() = runTest {
        val bridge = NormalTurnBridge()
        val events = collectSegments(bridge)
        bridge.toolPending(writeCall())

        bridge.confirmResolved(allowed = true)

        val confirm = events.filterIsInstance<ChatSegment.Confirm>().last()
        assertEquals(ConfirmSegmentState.ALLOWED, confirm.state)
        // 执行开始复用同一张卡片：id 不变，只推进状态。
        bridge.toolStarted(writeCall())
        val tools = events.filterIsInstance<ChatSegment.ToolCall>()
        assertEquals(1, tools.map { it.id }.toSet().size)
        assertEquals(ToolSegmentStatus.RUNNING, tools.last().status)
    }

    @Test
    fun 拒绝后_工具卡转已拒绝且不再执行() = runTest {
        val bridge = NormalTurnBridge()
        val events = collectSegments(bridge)
        bridge.toolPending(writeCall())

        bridge.confirmResolved(allowed = false)

        val tools = events.filterIsInstance<ChatSegment.ToolCall>()
        assertEquals(1, tools.map { it.id }.toSet().size)
        assertEquals(ToolSegmentStatus.DENIED, tools.last().status)
        assertEquals(ConfirmSegmentState.DENIED, events.filterIsInstance<ChatSegment.Confirm>().last().state)
    }

    @Test
    fun 无需确认的工具_直接开卡并在结束时写回状态与耗时() = runTest {
        val bridge = NormalTurnBridge()
        val events = collectSegments(bridge)
        val call = ToolCall("read", "{\"path\":\"a.txt\"}")

        bridge.toolStarted(call)
        bridge.toolFinished(ToolOutcome(ToolOutcomeStatus.SUCCESS, "你好，世界", durationMs = 42))

        val tool = events.filterIsInstance<ChatSegment.ToolCall>().last()
        assertEquals(ToolSegmentStatus.SUCCESS, tool.status)
        assertEquals(42L, tool.durationMs)
        assertEquals("你好，世界", tool.result)
    }

    @Test
    fun 工具失败_状态为失败且结果附错误码() = runTest {
        val bridge = NormalTurnBridge()
        val events = collectSegments(bridge)
        bridge.toolStarted(ToolCall("edit", "{}"))

        bridge.toolFinished(
            ToolOutcome(ToolOutcomeStatus.FAILURE, "路径不存在", errorCode = "E_NO_PATH", durationMs = 5),
        )

        val tool = events.filterIsInstance<ChatSegment.ToolCall>().last()
        assertEquals(ToolSegmentStatus.FAILED, tool.status)
        assertTrue(tool.result!!.contains("E_NO_PATH"))
        assertTrue(tool.result!!.contains("路径不存在"))
    }

    @Test
    fun 轮次收口_把执行中的卡片标记为中止() = runTest {
        val bridge = NormalTurnBridge()
        val events = collectSegments(bridge)
        bridge.toolStarted(ToolCall("write", "{}"))

        bridge.endTurn()

        val tool = events.filterIsInstance<ChatSegment.ToolCall>().last()
        assertEquals(ToolSegmentStatus.FAILED, tool.status)
        assertTrue(tool.result!!.contains("中止"))
    }

    @Test
    fun 轮次错误_推可重试错误段() = runTest {
        val bridge = NormalTurnBridge()
        val events = collectSegments(bridge)

        bridge.turnFailed("  ")

        val error = events.filterIsInstance<ChatSegment.Error>().single()
        assertTrue(error.retryable)
        assertEquals("本轮执行失败", error.message)
    }

    @Test
    fun 新一轮开始_清掉上轮挂起状态() = runTest {
        val bridge = NormalTurnBridge()
        val events = collectSegments(bridge)
        bridge.toolPending(writeCall())

        val before = events.size
        bridge.beginTurn()
        bridge.confirmResolved(allowed = true) // 上轮挂起已清空，不应再产生段
        bridge.toolFinished(ToolOutcome(ToolOutcomeStatus.SUCCESS, "ok"))

        assertEquals(before, events.size)
        assertFalse(events.any { it is ChatSegment.ToolCall && it.status == ToolSegmentStatus.RUNNING })
    }

    @Test
    fun 结果摘要_超长截断且保留前段() {
        val outcome = ToolOutcome(ToolOutcomeStatus.SUCCESS, "字".repeat(5000))
        val summary = summarizeToolResult(outcome, maxLength = 100)
        assertEquals(101, summary.length) // 100 字 + 省略号
        assertTrue(summary.endsWith("…"))
    }
}
