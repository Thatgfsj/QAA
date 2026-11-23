package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认挂起语义单测（设计文档 3.6 与附录 A.3）：
 * 挂起不执行 / Allow 恢复 / Deny 回注固定话术 / yoloMode 直通 / 免确认工具直通 /
 * 挂起中取消（协程取消即放弃执行，不回注）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopConfirmationTest {

    private val readJson = """{"tool":"read","args":{"path":"a.txt"}}"""
    private val writeJson = """{"tool":"write","args":{"path":"a.txt","content":"hi"}}"""
    private val editJson =
        """{"tool":"edit","args":{"path":"a.txt","oldText":"x","newText":"y"}}"""

    /** 手动放行的确认门：挂起到测试显式 resolve 为止。 */
    private class ManualGate : ConfirmationGate {
        val requests = mutableListOf<ToolCall>()
        private var pending: CompletableDeferred<ConfirmationDecision>? = null

        override suspend fun awaitConfirmation(call: ToolCall): ConfirmationDecision {
            requests += call
            val deferred = CompletableDeferred<ConfirmationDecision>()
            pending = deferred
            return deferred.await()
        }

        fun resolve(decision: ConfirmationDecision) {
            checkNotNull(pending) { "当前没有挂起的确认请求" }.complete(decision)
        }
    }

    private fun loop(
        engine: FakeLlmEngine,
        gate: ConfirmationGate,
        tools: FakeToolGateway = FakeToolGateway(),
        recorder: InMemoryTurnRecorder = InMemoryTurnRecorder(),
        config: AgentConfig = AgentConfig(),
    ) = AgentLoop(
        config = config,
        engine = engine,
        tools = tools,
        gate = gate,
        recorder = recorder,
    )

    @Test
    fun C1_挂起期间不执行_Allow恢复后执行() = runTest {
        val gate = ManualGate()
        val tools = FakeToolGateway()
        val engine = FakeLlmEngine(listOf(writeJson, "已完成写入。"))
        val recorder = InMemoryTurnRecorder()
        val job = launch { loop(engine, gate, tools, recorder).runTurn("写文件") }
        runCurrent()

        // 挂起：确认请求已送达，工具未执行、无 tool 节点、无第二次生成。
        assertEquals(1, gate.requests.size)
        assertEquals("write", gate.requests.single().tool)
        assertTrue(tools.executed.isEmpty())
        assertTrue(recorder.nodes.none { it.role == TurnRole.TOOL })
        assertEquals(1, engine.receivedMessages.size)

        gate.resolve(ConfirmationDecision.ALLOW)
        job.join()

        assertEquals(listOf("write"), tools.executed.map { it.call.tool })
        assertEquals(0, tools.executed.single().stepIndex)
        val toolNode = recorder.nodes.single { it.role == TurnRole.TOOL }
        assertEquals(ToolOutcomeStatus.SUCCESS, toolNode.toolStatus)
        assertEquals(2, engine.receivedMessages.size)
    }

    @Test
    fun C2_Deny回注固定话术且不执行() = runTest {
        val gate = ManualGate()
        val tools = FakeToolGateway()
        val engine = FakeLlmEngine(listOf(writeJson, "已改用文字说明。"))
        val recorder = InMemoryTurnRecorder()
        val job = launch { loop(engine, gate, tools, recorder).runTurn("写文件") }
        runCurrent()
        gate.resolve(ConfirmationDecision.DENY)
        job.join()

        assertTrue(tools.executed.isEmpty())
        val toolNode = recorder.nodes.single { it.role == TurnRole.TOOL }
        assertEquals(AgentLoop.DENIED_REPLY, toolNode.content)
        assertEquals(ToolOutcomeStatus.DENIED, toolNode.toolStatus)
        assertNull(toolNode.errorCode)
        assertEquals(0L, toolNode.durationMs)
        // 拒绝结果以 TOOL 消息回注下一次生成。
        assertTrue(
            engine.receivedMessages.last().any {
                it.role == PromptRole.TOOL && it.content == AgentLoop.DENIED_REPLY
            }
        )
    }

    @Test
    fun C3_yoloMode直通_不经过确认门() = runTest {
        val gate = ManualGate()
        val tools = FakeToolGateway()
        val engine = FakeLlmEngine(listOf(writeJson, "完成。"))
        loop(
            engine = engine,
            gate = gate,
            tools = tools,
            config = AgentConfig(yoloMode = true),
        ).runTurn("写文件")

        assertTrue(gate.requests.isEmpty())
        assertEquals(listOf("write"), tools.executed.map { it.call.tool })
    }

    @Test
    fun C4_免确认工具不进入挂起() = runTest {
        val gate = ManualGate()
        val tools = FakeToolGateway()
        val engine = FakeLlmEngine(listOf(readJson, "读完了。"))
        loop(engine, gate, tools).runTurn("读文件")

        assertTrue(gate.requests.isEmpty())
        assertEquals(listOf("read"), tools.executed.map { it.call.tool })
    }

    @Test
    fun C5_挂起中取消_工具不执行且任务取消() = runTest {
        val gate = ManualGate()
        val tools = FakeToolGateway()
        val engine = FakeLlmEngine(listOf(writeJson, "不该走到这里。"))
        val recorder = InMemoryTurnRecorder()
        val job = launch { loop(engine, gate, tools, recorder).runTurn("写文件") }
        runCurrent()
        assertEquals(1, gate.requests.size)

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(tools.executed.isEmpty())
        assertEquals(1, engine.receivedMessages.size)
        assertTrue(recorder.nodes.none { it.role == TurnRole.TOOL })
    }

    @Test
    fun C6_多个调用逐次挂起_决策按序生效() = runTest {
        val gate = ManualGate()
        val tools = FakeToolGateway()
        val engine = FakeLlmEngine(listOf(writeJson, editJson, "收尾。"))
        val recorder = InMemoryTurnRecorder()
        val job = launch { loop(engine, gate, tools, recorder).runTurn("先写再改") }

        runCurrent()
        assertEquals("write", gate.requests.single().tool)
        gate.resolve(ConfirmationDecision.ALLOW)
        runCurrent()

        assertEquals(2, gate.requests.size)
        assertEquals("edit", gate.requests.last().tool)
        assertEquals(listOf("write"), tools.executed.map { it.call.tool })
        gate.resolve(ConfirmationDecision.DENY)
        job.join()

        assertEquals(listOf("write"), tools.executed.map { it.call.tool })
        assertEquals(
            listOf(ToolOutcomeStatus.SUCCESS, ToolOutcomeStatus.DENIED),
            recorder.nodes.filter { it.role == TurnRole.TOOL }.map { it.toolStatus },
        )
        assertEquals(listOf(0, 1), recorder.nodes.filter { it.role == TurnRole.TOOL }.map { it.stepIndex })
    }
}
