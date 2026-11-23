package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.CompactedView
import com.thatgfsj.qaa.core.harness.JsonlTurnRecorder
import com.thatgfsj.qaa.core.harness.SessionRole
import com.thatgfsj.qaa.core.harness.TreeSessionStore
import com.thatgfsj.qaa.core.harness.TurnRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话记录适配单测：本轮文本累积、工具调用 JSON 过滤、压缩视图转提示历史、流式步去重。 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingTurnRecorderTest {

    private fun recorder(store: TreeSessionStore): RecordingTurnRecorder =
        RecordingTurnRecorder(
            delegate = JsonlTurnRecorder(store),
            compactedView = { CompactedView(history = store.snapshot(), summaries = emptyList()) },
        )

    @Test
    fun 纯文本作答累积为本轮文本并落树() = runTest {
        val store = TreeSessionStore("test-session")
        val recorder = recorder(store)
        recorder.beginTurn()
        recorder.appendAssistant("这是最终回答", emptyList(), 0, null, 0)
        recorder.endTurn()

        assertEquals("这是最终回答", recorder.turnAssistantText())
        assertEquals(1, store.nodeCount())
    }

    @Test
    fun 工具调用JSON不进入对话区() = runTest {
        val store = TreeSessionStore("test-session")
        val recorder = recorder(store)
        recorder.beginTurn()
        recorder.appendAssistant(
            """{"tool":"read","args":{"path":"a.txt"}}""",
            emptyList(),
            0,
            null,
            0,
        )
        assertEquals("", recorder.turnAssistantText())
    }

    @Test
    fun 一轮内多段作答按序拼接() = runTest {
        val store = TreeSessionStore("test-session")
        val recorder = recorder(store)
        recorder.beginTurn()
        recorder.appendAssistant("第一段", emptyList(), 0, null, 0)
        recorder.appendAssistant("第二段", emptyList(), 0, null, 1)
        assertEquals("第一段\n第二段", recorder.turnAssistantText())
    }

    @Test
    fun 压缩视图转提示历史_摘要段前置并带标签() = runTest {
        val store = TreeSessionStore("test-session")
        store.append(SessionRole.USER, "问题")
        store.append(SessionRole.ASSISTANT, "回答")
        val view = CompactedView(history = store.snapshot(), summaries = listOf("摘要：用户提问"))

        val prompt = view.toPromptHistory()

        assertEquals(3, prompt.size)
        assertEquals(TurnRole.TOOL, prompt.first().role)
        assertEquals(listOf(TAG_SUMMARY), prompt.first().tags)
        assertEquals("摘要：用户提问", prompt.first().content)
        assertEquals("问题", prompt[1].content)
        assertEquals("回答", prompt[2].content)
    }

    @Test
    fun 空白摘要段被过滤() = runTest {
        val store = TreeSessionStore("test-session")
        store.append(SessionRole.USER, "问题")
        val prompt = CompactedView(history = store.snapshot(), summaries = listOf("  ")).toPromptHistory()
        assertEquals(1, prompt.size)
        assertEquals(TurnRole.USER, prompt.first().role)
    }

    @Test
    fun 工具结果事件携带工具摘要与终态() = runTest {
        val store = TreeSessionStore("test-session")
        val recorder = recorder(store)
        recorder.beginTurn()
        recorder.appendTool(
            com.thatgfsj.qaa.core.harness.ToolCall("write", """{"path":"a.txt","content":"x"}"""),
            com.thatgfsj.qaa.core.harness.ToolOutcome(
                status = com.thatgfsj.qaa.core.harness.ToolOutcomeStatus.SUCCESS,
                output = "已写入",
                durationMs = 3,
            ),
            0,
        )
        // 工具结果不进入本轮助手文本（由终端另行渲染 [TOOL] 行）。
        assertEquals("", recorder.turnAssistantText())
        assertTrue(store.nodeCount() == 1)
    }

    // ---- token 级流式（Issue #64） ----

    private fun streamingRecorder(
        store: TreeSessionStore,
        relay: AssistantTokenRelay,
    ): RecordingTurnRecorder = RecordingTurnRecorder(
        delegate = JsonlTurnRecorder(store),
        compactedView = { CompactedView(history = store.snapshot(), summaries = emptyList()) },
        tokenRelay = relay,
    )

    @Test
    fun 已流式的生成步不再整段重发助手事件() = runTest {
        val store = TreeSessionStore("test-session")
        val relay = AssistantTokenRelay()
        val recorder = streamingRecorder(store, relay)
        val events = mutableListOf<AgentEvent>()
        backgroundScope.launch { recorder.events.collect { events += it } }
        runCurrent()

        recorder.beginTurn()
        relay.emit("回答")
        recorder.appendAssistant("回答", emptyList(), 0, null, 0)
        runCurrent()

        assertTrue(events.none { it.kind == AgentEventKind.ASSISTANT })
        assertEquals("回答", recorder.turnAssistantText())
    }

    @Test
    fun 未流式的生成步仍整段回显助手事件() = runTest {
        val store = TreeSessionStore("test-session")
        val recorder = streamingRecorder(store, AssistantTokenRelay())
        val events = mutableListOf<AgentEvent>()
        backgroundScope.launch { recorder.events.collect { events += it } }
        runCurrent()

        recorder.beginTurn()
        recorder.appendAssistant("整段回答", emptyList(), 0, null, 0)
        runCurrent()

        assertEquals(
            listOf("整段回答"),
            events.filter { it.kind == AgentEventKind.ASSISTANT }.map { it.text },
        )
    }

    @Test
    fun 工具调用JSON步以空文本收口供UI丢弃() = runTest {
        val store = TreeSessionStore("test-session")
        val relay = AssistantTokenRelay()
        val recorder = streamingRecorder(store, relay)
        val streamEvents = mutableListOf<AssistantStreamEvent>()
        backgroundScope.launch { relay.events.collect { streamEvents += it } }
        runCurrent()

        recorder.beginTurn()
        relay.emit("工具调用中")
        recorder.appendAssistant(
            """{"tool":"read","args":{"path":"a.txt"}}""",
            emptyList(),
            0,
            null,
            0,
        )
        runCurrent()

        assertEquals(AssistantStreamEvent.Finished(1, ""), streamEvents.last())
        assertEquals("", recorder.turnAssistantText())
    }
}
