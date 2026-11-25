package com.thatgfsj.qaa.feature.normal.chat

import com.thatgfsj.qaa.feature.normal.model.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSegmentTest {

    private fun text(id: Long, content: String = "文本") = ChatSegment.Text(id, ChatRole.ASSISTANT, content)

    private fun tool(id: Long, status: ToolSegmentStatus = ToolSegmentStatus.SUCCESS) =
        ChatSegment.ToolCall(id, "read", "{}", status)

    private fun confirm(id: Long, toolId: Long, state: ConfirmSegmentState = ConfirmSegmentState.PENDING) =
        ChatSegment.Confirm(id, toolId, "read 路径=\"a.txt\"", state)

    @Test
    fun 连续工具步_聚合为一条执行链() {
        val timeline = chatTimeline(listOf(tool(1), tool(2), tool(3)))

        assertEquals(1, timeline.size)
        val chain = timeline.single() as ChatTimelineEntry.ToolChain
        assertEquals(3, chain.steps.size)
        assertNull(chain.confirm)
        assertEquals("执行链 · 3 步", chain.titleText())
    }

    @Test
    fun 文本段切断执行链_前后各自成链() {
        val timeline = chatTimeline(listOf(tool(1), tool(2), text(3), tool(4)))

        assertEquals(3, timeline.size)
        assertEquals(2, (timeline[0] as ChatTimelineEntry.ToolChain).steps.size)
        assertTrue(timeline[1] is ChatTimelineEntry.Text)
        assertEquals(1, (timeline[2] as ChatTimelineEntry.ToolChain).steps.size)
        assertEquals("工具调用", (timeline[2] as ChatTimelineEntry.ToolChain).titleText())
    }

    @Test
    fun 确认条_挂在所在执行链内而非单独成块() {
        val timeline = chatTimeline(listOf(tool(1, ToolSegmentStatus.WAITING_CONFIRM), confirm(2, 1L)))

        val chain = timeline.single() as ChatTimelineEntry.ToolChain
        assertEquals(1, chain.steps.size)
        assertEquals(2L, chain.confirm?.id)
    }

    @Test
    fun 仅确认条_单独成链且标题为工具确认() {
        val timeline = chatTimeline(listOf(confirm(9, 8)))

        val chain = timeline.single() as ChatTimelineEntry.ToolChain
        assertEquals("工具确认", chain.titleText())
    }

    @Test
    fun 错误段_切断执行链并单独成块() {
        val timeline = chatTimeline(listOf(tool(1), ChatSegment.Error(2, "失败")))

        assertEquals(2, timeline.size)
        assertTrue(timeline[1] is ChatTimelineEntry.Error)
    }

    @Test
    fun 耗时文案_毫秒与秒两种量纲() {
        assertEquals("842 ms", tool(1).copy(durationMs = 842).durationText())
        assertEquals("1.5 s", tool(1).copy(durationMs = 1500).durationText())
        assertNull(tool(1).durationText())
    }

    @Test
    fun 列表key_同id状态更新后保持不变() {
        val before = tool(7, ToolSegmentStatus.RUNNING)
        val after = before.copy(status = ToolSegmentStatus.SUCCESS)
        val beforeKey = ChatTimelineEntry.ToolChain(listOf(before), null).listKey()
        val afterKey = ChatTimelineEntry.ToolChain(listOf(after), null).listKey()
        assertEquals(beforeKey, afterKey)
        assertEquals("chain-7", afterKey)
    }
}
