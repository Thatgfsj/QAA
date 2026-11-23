package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentLoop JVM 单测（设计附录 A.1 指定子集）：
 * L1 纯文本 / L3 多工具链 / L5 Deny / L4 maxToolSteps 收尾 / L8 解析降级 / L6 工具步打断；
 * 另覆盖工具步序号透传、生成/工具指标落树、连续解析失败重置与 Deny 计步收尾。
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

        assertEquals(listOf("read", "edit", "write"), tools.executed.map { it.call.tool })
        assertEquals(listOf(0, 1, 2), tools.executed.map { it.stepIndex })
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
        assertEquals(ToolOutcomeStatus.DENIED, toolNode.toolStatus)
        assertEquals(2, engine.receivedMessages.size) // Deny 回注后终答
    }

    @Test
    fun L4_maxToolSteps硬顶_末次生成禁用工具解析() = runTest {
        // 第 13 条仍是工具调用 JSON，用于证明 toolsDisabled 生效而非自然收尾。
        val engine = FakeLlmEngine(List(12) { readJson } + listOf(readJson))
        val tools = FakeToolGateway()
        val recorder = InMemoryTurnRecorder()
        loop(engine = engine, tools = tools, recorder = recorder).runTurn("连续读取")

        assertEquals(12, tools.executed.size) // 12 步硬顶，第 13 条 JSON 不再执行
        assertEquals(13, engine.receivedMessages.size)
        // 收尾提示只注入一次（仅第 13 次生成）。
        val noticePrompts = engine.receivedMessages.count { messages ->
            messages.any { it.content == PromptAssembler.STEP_BUDGET_NOTICE }
        }
        assertEquals(1, noticePrompts)
        assertEquals(
            PromptAssembler.STEP_BUDGET_NOTICE,
            engine.receivedMessages.last().last().content,
        )
        // 第 13 次生成原样落树：内容仍是 JSON，但不解析、不产生 toolCalls。
        val finalAssistant = recorder.nodes.last()
        assertEquals(TurnRole.ASSISTANT, finalAssistant.role)
        assertEquals(readJson, finalAssistant.content)
        assertEquals(0, finalAssistant.toolCalls.size)
        assertEquals(
            12,
            recorder.nodes.count { it.role == TurnRole.TOOL && it.tags.isEmpty() }
        )
    }

    @Test
    fun L8_解析降级_重试耗尽后禁用工具() = runTest {
        // 4 层未闭合花括号 + 1 个未闭合字符串 = 需补 5 个记号，超出 3 个修补上限。
        val dirtyJson = "{\"tool\":\"read\",\"args\":{\"opts\":{\"a\":{\"b\":\"x"
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

    @Test
    fun B1_工具执行携带步序号_Deny占步且后续递增() = runTest {
        val engine = FakeLlmEngine(listOf(readJson, writeJson, readJson, "处理完成。"))
        val tools = FakeToolGateway()
        val gate = FakeConfirmationGate(listOf(ConfirmationDecision.DENY))
        val recorder = InMemoryTurnRecorder()
        loop(engine = engine, tools = tools, gate = gate, recorder = recorder)
            .runTurn("读取并写入")

        // write 被 Deny 不执行，但仍占第 1 步；后续 read 的步序号为 2。
        assertEquals(listOf("read", "read"), tools.executed.map { it.call.tool })
        assertEquals(listOf(0, 2), tools.executed.map { it.stepIndex })
        assertEquals(1, gate.seen.size)
        val toolNodes = recorder.nodes.filter { it.role == TurnRole.TOOL && it.tags.isEmpty() }
        assertEquals(listOf(0, 1, 2), toolNodes.map { it.stepIndex })
        assertEquals(ToolOutcomeStatus.DENIED, toolNodes[1].toolStatus)
    }

    @Test
    fun B2_生成与工具指标落树() = runTest {
        val metrics = GenerationMetrics(
            backend = "HYBRID",
            ttftMs = 210,
            tokensPerSec = 18.4f,
            promptTokens = 1350,
            completionTokens = 96,
            durationMs = 4200,
        )
        val engine = FakeLlmEngine(listOf(readJson, "完成。"), metrics = metrics)
        val tools = FakeToolGateway()
        val recorder = InMemoryTurnRecorder()
        loop(engine = engine, tools = tools, recorder = recorder).runTurn("读文件")

        val assistantNodes = recorder.nodes.filter { it.role == TurnRole.ASSISTANT }
        assertEquals(2, assistantNodes.size)
        assertTrue(assistantNodes.all { it.metrics == metrics })
        assertEquals(listOf(0, 1), assistantNodes.map { it.stepIndex })

        val toolNode = recorder.nodes.single { it.role == TurnRole.TOOL && it.tags.isEmpty() }
        assertEquals(0, toolNode.stepIndex)
        assertEquals(ToolOutcomeStatus.SUCCESS, toolNode.toolStatus)
        assertNotNull(toolNode.durationMs)
        assertTrue(toolNode.durationMs!! >= 0)
    }

    @Test
    fun S1_解析失败按连续计数_合法调用后重置() = runTest {
        // 括号错配（{ 被 ] 关闭）不可修补，同样走解析失败连续计数。
        val dirtyJson = "{\"tool\":\"read\",\"args\":{\"path\":\"a.txt\"]}"
        val engine = FakeLlmEngine(
            listOf(dirtyJson, dirtyJson, readJson, dirtyJson, "直接回答。")
        )
        val tools = FakeToolGateway()
        val recorder = InMemoryTurnRecorder()
        loop(engine = engine, tools = tools, recorder = recorder).runTurn("读文件")

        assertEquals(5, engine.receivedMessages.size)
        // 第 4 次生成时连续失败计数已从 0 重新开始，不应触发降级。
        assertTrue(
            engine.receivedMessages.none { messages ->
                messages.any { it.content == PromptAssembler.DEMOTION_NOTICE }
            }
        )
        assertEquals(1, tools.executed.size)
        assertEquals(
            listOf(1, 2, 0, 1, 0),
            recorder.nodes.filter { it.role == TurnRole.ASSISTANT }.map { it.parseRetries },
        )
    }

    @Test
    fun S9_Deny计步触发步数预算收尾() = runTest {
        val engine = FakeLlmEngine(listOf(writeJson, "好的，改用文字说明。"))
        val gate = FakeConfirmationGate(listOf(ConfirmationDecision.DENY))
        val recorder = InMemoryTurnRecorder()
        loop(
            config = AgentConfig(maxToolSteps = 1),
            engine = engine,
            gate = gate,
            recorder = recorder,
        ).runTurn("写文件")

        assertEquals(2, engine.receivedMessages.size)
        // Deny 记 1 步 → 第 2 次生成收到一次性预算收尾提示，证明 Deny 计步生效。
        assertEquals(
            PromptAssembler.STEP_BUDGET_NOTICE,
            engine.receivedMessages.last().last().content,
        )
        assertTrue(
            engine.receivedMessages.first().none {
                it.content == PromptAssembler.STEP_BUDGET_NOTICE
            }
        )
        val toolNode = recorder.nodes.single { it.role == TurnRole.TOOL && it.tags.isEmpty() }
        assertEquals(ToolOutcomeStatus.DENIED, toolNode.toolStatus)
        assertEquals(AgentLoop.DENIED_REPLY, toolNode.content)
        assertEquals(0, toolNode.stepIndex)
        assertNull(toolNode.errorCode)
        assertNotNull(toolNode.durationMs)
    }
}
