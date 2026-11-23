package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentLoop JVM 单测（设计附录 A.1 指定子集）：
 * L1 纯文本 / L3 多工具链 / L5 Deny / L4 maxToolSteps 收尾 / L8 解析降级 / L6 工具步打断。
 */
class AgentLoopTest {

    private val readJson = """{"tool":"read","args":{"path":"a.txt"}}"""
    private val editJson =
        """{"tool":"edit","args":{"path":"a.txt","oldText":"x","newText":"y"}}"""
    private val writeJson = """{"tool":"write","args":{"path":"b.txt","content":"hi"}}"""

    private fun loop(
        config: AgentConfig = AgentConfig(),
        engine: FakeLlmEngine,
        tools: FakeToolGateway = FakeToolGateway(),
        gate: FakeConfirmationGate = FakeConfirmationGate(listOf(ConfirmationDecision.ALLOW)),
        recorder: InMemoryTurnRecorder = InMemoryTurnRecorder(),
        interrupt: (suspend () -> String?)? = null,
    ) = AgentLoop(
        config = config,
        engine = engine,
        tools = tools,
        gate = gate,
        recorder = recorder,
        interruptCheck = interrupt,
    )

    @Test
    fun L1_纯文本回复_一轮即终() = runTest {
        val engine = FakeLlmEngine(listOf("你好，我是 QAA 助手。"))
        val recorder = InMemoryTurnRecorder()
        loop(engine = engine, recorder = recorder).runTurn("你好")

        assertEquals(1, engine.receivedMessages.size)
        assertEquals(2, recorder.nodes.size)
        assertEquals(TurnRole.USER, recorder.nodes[0].role)
        assertEquals(TurnRole.ASSISTANT, recorder.nodes[1].role)
        assertTrue(recorder.nodes.none { it.role == TurnRole.TOOL })
        // 稳定前缀在首：系统段必须是第一条消息。
        assertEquals(PromptRole.SYSTEM, engine.receivedMessages.first().first().role)
    }

    @Test
    fun L3_多工具链_read_edit_write() = runTest {
        val engine = FakeLlmEngine(
            listOf(readJson, editJson, writeJson, "已完成：读取、修改并写出了文件。")
        )
        val tools = FakeToolGateway()
        val gate = FakeConfirmationGate(
            listOf(ConfirmationDecision.ALLOW, ConfirmationDecision.ALLOW)
        )
        val recorder = InMemoryTurnRecorder()
        loop(engine = engine, tools = tools, gate = gate, recorder = recorder)
            .runTurn("处理 a.txt")

        assertEquals(listOf("read", "edit", "write"), tools.executed.map { it.tool })
        assertEquals(4, engine.receivedMessages.size)
        val toolNodes = recorder.nodes.filter { it.role == TurnRole.TOOL && it.tags.isEmpty() }
        assertEquals(3, toolNodes.size)
        assertTrue(toolNodes.all { it.content.startsWith("（模拟执行成功）") })
        assertEquals(2, gate.seen.size) // edit/write 需确认，read 免确认
    }

    @Test
    fun L5_Deny路径_步数照常累计() = runTest {
        val engine = FakeLlmEngine(listOf(writeJson, "好的，我改用文字说明。"))
        val tools = FakeToolGateway()
        val gate = FakeConfirmationGate(listOf(ConfirmationDecision.DENY))
        val recorder = InMemoryTurnRecorder()
        loop(engine = engine, tools = tools, gate = gate, recorder = recorder)
            .runTurn("写文件")

        assertTrue(tools.executed.isEmpty()) // Deny 不执行
        val toolNode = recorder.nodes.single { it.role == TurnRole.TOOL && it.tags.isEmpty() }
        assertEquals(AgentLoop.DENIED_REPLY, toolNode.content)
        assertEquals(2, engine.receivedMessages.size) // Deny 回注后终答
    }

    @Test
    fun L4_maxToolSteps硬顶_注入收尾提示() = runTest {
        val engine = FakeLlmEngine(
            List(12) { readJson } + listOf("基于已读取内容，整理如下。")
        )
        val tools = FakeToolGateway()
        val recorder = InMemoryTurnRecorder()
        loop(engine = engine, tools = tools, recorder = recorder).runTurn("连续读取")

        assertEquals(12, tools.executed.size) // 12 步硬顶
        assertEquals(13, engine.receivedMessages.size)
        // 第 13 次生成禁用工具解析，直接落树收尾。
        assertEquals(
            PromptAssembler.STEP_BUDGET_NOTICE,
            engine.receivedMessages.last().last().content,
        )
        val finalAssistant = recorder.nodes.last()
        assertEquals(TurnRole.ASSISTANT, finalAssistant.role)
        assertEquals(0, finalAssistant.toolCalls.size)
        assertEquals(
            12,
            recorder.nodes.count { it.role == TurnRole.TOOL && it.tags.isEmpty() }
        )
    }

    @Test
    fun L8_解析降级_重试耗尽后禁用工具() = runTest {
        val dirtyJson = """{"tool": "read", "args": {"path": "a.txt"""" // 截断脏 JSON
        val engine = FakeLlmEngine(
            listOf(dirtyJson, dirtyJson, dirtyJson, "好的，我直接用文字回答。")
        )
        val tools = FakeToolGateway()
        val recorder = InMemoryTurnRecorder()
        loop(engine = engine, tools = tools, recorder = recorder).runTurn("读文件")

        assertEquals(4, engine.receivedMessages.size) // 初次 + 重试2 + 降级收尾1
        assertEquals(
            PromptAssembler.DEMOTION_NOTICE,
            engine.receivedMessages.last().last().content,
        )
        // 两次纠错提示以系统回注入树；第三次失败后转降级。
        val corrections = recorder.nodes.filter { "correction" in it.tags }
        assertEquals(2, corrections.size)
        assertEquals(3, recorder.nodes.last { it.role == TurnRole.ASSISTANT }.parseRetries)
        assertTrue(tools.executed.isEmpty())
    }

    @Test
    fun L6_工具步边界打断_steer文本入树() = runTest {
        val engine = FakeLlmEngine(listOf(readJson, "unused"))
        val recorder = InMemoryTurnRecorder()
        loop(
            engine = engine,
            recorder = recorder,
            interrupt = { "请改用纯文字总结" },
        ).runTurn("读取 a.txt")

        assertEquals(1, engine.receivedMessages.size) // 打断后不再生成
        val steerNode = recorder.nodes.last()
        assertEquals(TurnRole.USER, steerNode.role)
        assertTrue(AgentLoop.TAG_STEER in steerNode.tags)
    }
}
