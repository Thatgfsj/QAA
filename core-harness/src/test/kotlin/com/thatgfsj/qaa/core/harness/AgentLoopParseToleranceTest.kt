package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentLoop 与 ToolCallParser 的集成单测（Issue #13）：
 * 围栏/截断 JSON 不再被当终答静默丢弃、数组多调用逐调用检查步数预算、
 * 未知工具原因与四工具白名单回注纠错话术、超深截断/括号错配走重试降级、
 * 完整值后缺闭合括号仍被修补救回。
 */
class AgentLoopParseToleranceTest {

    private val readJson = """{"tool":"read","args":{"path":"a.txt"}}"""
    private val writeJson = """{"tool":"write","args":{"path":"b.txt","content":"hi"}}"""

    private fun loop(
        config: AgentConfig = AgentConfig(),
        engine: FakeLlmEngine,
        tools: FakeToolGateway = FakeToolGateway(),
        gate: FakeConfirmationGate = FakeConfirmationGate(listOf(ConfirmationDecision.ALLOW)),
        recorder: InMemoryTurnRecorder = InMemoryTurnRecorder(),
    ) = AgentLoop(
        config = config,
        engine = engine,
        tools = tools,
        gate = gate,
        recorder = recorder,
    )

    @Test
    fun T1_围栏JSON被提取执行_不再当终答() = runTest {
        val engine = FakeLlmEngine(
            listOf("我先读取文件：\n```json\n$readJson\n```", "文件内容已读取。")
        )
        val tools = FakeToolGateway()
        val recorder = InMemoryTurnRecorder()
        loop(engine = engine, tools = tools, recorder = recorder).runTurn("读 a.txt")

        assertEquals(listOf("read"), tools.executed.map { it.call.tool })
        assertEquals(2, engine.receivedMessages.size)
        val assistant = recorder.nodes.first { it.role == TurnRole.ASSISTANT }
        assertEquals(1, assistant.toolCalls.size)
    }

    @Test
    fun T2_截断JSON修补后继续执行() = runTest {
        // 截断在字符串内：补 1 个引号 + 2 个花括号，属于 3 个闭合记号以内。
        val truncated = "{\"tool\":\"read\",\"args\":{\"path\":\"a.txt"
        val engine = FakeLlmEngine(listOf(truncated, "读取完成。"))
        val tools = FakeToolGateway()
        loop(engine = engine, tools = tools).runTurn("读 a.txt")

        assertEquals(listOf("read"), tools.executed.map { it.call.tool })
        assertEquals(2, engine.receivedMessages.size)
    }

    @Test
    fun T3_数组多调用_逐调用检查步数预算() = runTest {
        val batch = "[" + readJson + "," + writeJson + "," + readJson + "]"
        val engine = FakeLlmEngine(listOf(batch, "已按预算执行。"))
        val tools = FakeToolGateway()
        val recorder = InMemoryTurnRecorder()
        loop(
            config = AgentConfig(maxToolSteps = 2),
            engine = engine,
            tools = tools,
            recorder = recorder,
        ).runTurn("批量处理")

        // 3 个调用只执行前 2 个：步序号连续且不越顶。
        assertEquals(2, tools.executed.size)
        assertEquals(listOf("read", "write"), tools.executed.map { it.call.tool })
        assertEquals(listOf(0, 1), tools.executed.map { it.stepIndex })
        // 预算耗尽：注入一次性收尾提示，末次生成不做工具解析。
        assertEquals(2, engine.receivedMessages.size)
        assertEquals(
            PromptAssembler.STEP_BUDGET_NOTICE,
            engine.receivedMessages.last().last().content,
        )
        // 未执行的调用不落 tool 节点；assistant 节点记录本步解析出的 3 个调用（设计 5.3）。
        assertEquals(2, recorder.nodes.count { it.role == TurnRole.TOOL && it.tags.isEmpty() })
        assertEquals(3, recorder.nodes.first { it.role == TurnRole.ASSISTANT }.toolCalls.size)
        assertEquals(TurnRole.ASSISTANT, recorder.nodes.last().role)
    }

    @Test
    fun T4_未知工具_纠错话术含原因与白名单() = runTest {
        val engine = FakeLlmEngine(listOf("""{"tool":"hack","args":{}}""", "抱歉，我直接回答。"))
        val tools = FakeToolGateway()
        val recorder = InMemoryTurnRecorder()
        loop(engine = engine, tools = tools, recorder = recorder).runTurn("调用工具")

        assertTrue(tools.executed.isEmpty())
        assertEquals(2, engine.receivedMessages.size)
        val notice = engine.receivedMessages[1].last().content
        assertTrue(notice, notice.contains("未知工具：hack"))
        ToolCallParser.TOOL_WHITELIST.forEach { tool ->
            assertTrue("纠错提示缺少白名单工具 $tool：$notice", notice.contains(tool))
        }
        assertEquals(1, recorder.nodes.count { "correction" in it.tags })
    }

    @Test
    fun T5_超深截断_重试耗尽后降级收尾() = runTest {
        // 4 层未闭合花括号 + 1 个未闭合字符串，超出 3 个闭合记号的修补上限。
        val deep = "{\"tool\":\"write\",\"args\":{\"opts\":{\"a\":{\"b\":\"x"
        val engine = FakeLlmEngine(listOf(deep, deep, deep, "好的，我直接用文字回答。"))
        val tools = FakeToolGateway()
        loop(engine = engine, tools = tools).runTurn("写文件")

        assertTrue(tools.executed.isEmpty())
        assertEquals(4, engine.receivedMessages.size)
        assertEquals(
            PromptAssembler.DEMOTION_NOTICE,
            engine.receivedMessages.last().last().content,
        )
    }

    @Test
    fun T6_完整值后缺括号_修补救回并继续执行() = runTest {
        // 设计 4.2 第 4 级只要求深度未归零：缺 2 个花括号、补完即合法，应直接救回。
        val truncated = "{\"tool\":\"read\",\"args\":{\"path\":\"a.txt\""
        val engine = FakeLlmEngine(listOf(truncated, "读取完成。"))
        val tools = FakeToolGateway()
        val recorder = InMemoryTurnRecorder()
        loop(engine = engine, tools = tools, recorder = recorder).runTurn("读 a.txt")

        assertEquals(listOf("read"), tools.executed.map { it.call.tool })
        assertEquals(2, engine.receivedMessages.size)
        assertTrue(recorder.nodes.none { "correction" in it.tags })
    }

    @Test
    fun T7_真机XML方言_触发确认门并执行() = runTest {
        // Issue #107 真机形态：XML 方言必须走确认门执行，不得被当作纯文本终答。
        val xml = """<tool_call>write<arg_key>content</arg_key><arg_value>QAA-real-device-test</arg_value><arg_key>path</arg_key><arg_value>test.txt</arg_value></tool_call>"""
        val engine = FakeLlmEngine(listOf(xml, "已写入 test.txt。"))
        val tools = FakeToolGateway()
        val gate = FakeConfirmationGate(listOf(ConfirmationDecision.ALLOW))
        val recorder = InMemoryTurnRecorder()
        loop(engine = engine, tools = tools, gate = gate, recorder = recorder).runTurn("写 test.txt")

        assertEquals(listOf("write"), tools.executed.map { it.call.tool })
        assertEquals("write", gate.seen.single().tool)
        assertEquals(2, engine.receivedMessages.size)
        assertEquals(1, recorder.nodes.count { it.role == TurnRole.TOOL && it.tags.isEmpty() })
    }

    @Test
    fun T8_工具痕迹但解析失败_走纠错而非静默终答() = runTest {
        // XML 方言未知工具：返回 Invalid 回注纠错，而不是把工具调用当纯文本回显。
        val broken = """<tool_call>hack<arg_key>a</arg_key><arg_value>b</arg_value></tool_call>"""
        val engine = FakeLlmEngine(listOf(broken, "抱歉，我直接回答。"))
        val tools = FakeToolGateway()
        val recorder = InMemoryTurnRecorder()
        loop(engine = engine, tools = tools, recorder = recorder).runTurn("调用工具")

        assertTrue(tools.executed.isEmpty())
        assertEquals(2, engine.receivedMessages.size)
        val notice = engine.receivedMessages[1].last().content
        assertTrue(notice, notice.contains("未知工具：hack"))
        assertEquals(1, recorder.nodes.count { "correction" in it.tags })
    }
}
