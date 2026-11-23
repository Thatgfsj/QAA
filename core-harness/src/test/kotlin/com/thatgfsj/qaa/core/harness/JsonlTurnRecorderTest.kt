package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JsonlTurnRecorder 单测（设计文档 5.3 字段透传、callId 回链、轮次标记）。
 */
class JsonlTurnRecorderTest {

    @Test
    fun append映射_工具字段透传与callId回链() = runTest {
        val store = TreeSessionStore("s1")
        val recorder = JsonlTurnRecorder(store)
        val readCall = ToolCall("read", "{\"path\":\"a.txt\"}")
        val editCall = ToolCall("edit", "{\"path\":\"a.txt\",\"oldText\":\"x\",\"newText\":\"y\"}")

        recorder.appendUser("处理 a.txt")
        recorder.appendAssistant(
            text = "我先读取。",
            toolCalls = listOf(readCall, editCall),
            parseRetries = 0,
            metrics = GenerationMetrics(backend = "NPU", promptTokens = 10),
            stepIndex = 0,
        )
        recorder.appendTool(readCall, ToolOutcome(ToolOutcomeStatus.SUCCESS, "内容", durationMs = 12L), 0)
        recorder.appendTool(
            editCall,
            ToolOutcome(ToolOutcomeStatus.FAILURE, "写入失败", errorCode = "E_IO", durationMs = 34L),
            1,
        )

        val nodes = store.allNodes()
        assertEquals(
            listOf(SessionRole.USER, SessionRole.ASSISTANT, SessionRole.TOOL, SessionRole.TOOL),
            nodes.map { it.role },
        )
        val assistant = nodes[1]
        assertEquals(2, assistant.toolCalls.size)
        assertEquals("c-" + assistant.id.removePrefix("n-") + "-0", assistant.toolCalls[0].callId)
        assertEquals("c-" + assistant.id.removePrefix("n-") + "-1", assistant.toolCalls[1].callId)

        val firstTool = nodes[2]
        assertEquals(assistant.toolCalls[0].callId, firstTool.callId)
        assertEquals("read", firstTool.tool)
        assertEquals(ToolOutcomeStatus.SUCCESS, firstTool.toolStatus)
        assertEquals(12L, firstTool.durationMs!!)
        assertEquals(0, firstTool.stepIndex)
        assertNull(firstTool.errorCode)

        val secondTool = nodes[3]
        assertEquals(assistant.toolCalls[1].callId, secondTool.callId)
        assertEquals(ToolOutcomeStatus.FAILURE, secondTool.toolStatus)
        assertEquals("E_IO", secondTool.errorCode)
        assertEquals(34L, secondTool.durationMs!!)
        assertEquals(1, secondTool.stepIndex)
    }

    @Test
    fun snapshot_与内存替身形态一致() = runTest {
        val store = TreeSessionStore("s1")
        val recorder = JsonlTurnRecorder(store)
        val inMemory = InMemoryTurnRecorder()
        val scenario: suspend (TurnRecorder) -> Unit = { sink ->
            sink.appendUser("你好", listOf("steer"))
            sink.appendAssistant(
                text = "稍等",
                toolCalls = listOf(ToolCall("read", "{\"path\":\"a\"}")),
                metrics = GenerationMetrics("NPU", 100L, 20f, 5, 6, 700L),
                stepIndex = 0,
            )
            sink.appendTool(
                ToolCall("read", "{\"path\":\"a\"}"),
                ToolOutcome(ToolOutcomeStatus.SUCCESS, "内容", durationMs = 9L),
                0,
            )
            sink.appendAssistant(text = "完成", parseRetries = 1, metrics = null, stepIndex = 1)
            sink.appendSystemNotice("纠错提示")
        }
        scenario(recorder)
        scenario(inMemory)

        assertEquals(inMemory.snapshot(), recorder.snapshot())
    }

    @Test
    fun 轮次标记_进行中拒绝fork() = runTest {
        val store = TreeSessionStore("s1")
        val recorder = JsonlTurnRecorder(store)
        recorder.appendUser("一")

        recorder.beginTurn()
        assertTrue(store.isTurnActive)
        assertEquals(ForkResult.TurnInProgress, store.fork())
        recorder.endTurn()
        assertFalse(store.isTurnActive)
        assertTrue(store.fork() is ForkResult.Success)
    }

    @Test
    fun 系统纠错回注_落为correction标签的tool节点() = runTest {
        val store = TreeSessionStore("s1")
        val recorder = JsonlTurnRecorder(store)
        recorder.appendSystemNotice("上一条不是合法 JSON")

        val node = store.allNodes().single()
        assertEquals(SessionRole.TOOL, node.role)
        assertEquals(listOf(JsonlTurnRecorder.TAG_CORRECTION), node.tags)
        assertNull(node.toolStatus)
        assertNull(node.callId)
        assertNull(node.tool)
    }

    @Test
    fun 未回链的工具调用_callId为空不崩溃() = runTest {
        val store = TreeSessionStore("s1")
        val recorder = JsonlTurnRecorder(store)
        recorder.appendTool(
            ToolCall("read", "{}"),
            ToolOutcome(ToolOutcomeStatus.DENIED, AgentLoop.DENIED_REPLY, durationMs = 0L),
            0,
        )

        val node = store.allNodes().single()
        assertNull(node.callId)
        assertEquals(ToolOutcomeStatus.DENIED, node.toolStatus)
        assertEquals(AgentLoop.DENIED_REPLY, node.content)
    }
}
