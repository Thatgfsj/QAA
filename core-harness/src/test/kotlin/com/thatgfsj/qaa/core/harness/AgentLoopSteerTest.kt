package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行中干预接线单测（设计文档 7.1-7.3 与附录 A.1 L6/L7、A.7 Q1/Q3/Q4/Q5）：
 * 工具执行中入队后当前工具完成即打断、确认挂起中 steer 等价 Deny、纯文本生成中
 * 轮末优先注入、多条 steer 逐条成轮、Follow-up FIFO 衔接、空闲态直通、取消清理。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopSteerTest {

    private val readJson = """{"tool":"read","args":{"path":"a.txt"}}"""
    private val writeJson = """{"tool":"write","args":{"path":"a.txt","content":"hi"}}"""
    private val twoReadsJson =
        """[{"tool":"read","args":{"path":"a.txt"}},{"tool":"read","args":{"path":"b.txt"}}]"""

    /** 按需接线的 Loop：queue 非空时同时接 interruptCheck 与 confirmationInterrupt。 */
    private fun loop(
        engine: LlmEngineGateway,
        tools: ToolExecutorGateway = FakeToolGateway(),
        gate: ConfirmationGate = FakeConfirmationGate(listOf(ConfirmationDecision.ALLOW)),
        recorder: InMemoryTurnRecorder = InMemoryTurnRecorder(),
        queue: SteerQueue? = null,
        config: AgentConfig = AgentConfig(),
    ): AgentLoop {
        val interrupt: (suspend () -> String?)? =
            if (queue != null) ({ queue.interruptCheck() }) else null
        return AgentLoop(
            config = config,
            engine = engine,
            tools = tools,
            gate = gate,
            recorder = recorder,
            interruptCheck = interrupt,
            confirmationInterrupt = queue,
        )
    }

    /** 确认挂起替身：记录进入挂起后一直等待（由竞速落败方取消）。 */
    private class HangingGate : ConfirmationGate {
        val entered = CompletableDeferred<ToolCall>()

        override suspend fun awaitConfirmation(call: ToolCall): ConfirmationDecision {
            entered.complete(call)
            return CompletableDeferred<ConfirmationDecision>().await()
        }
    }

    private fun InMemoryTurnRecorder.userTexts(): List<String> =
        nodes.filter { it.role == TurnRole.USER }.map { it.content }

    @Test
    fun L6_工具执行中入队_当前工具完成后打断转向() = runTest {
        val queue = SteerQueue()
        val tools = BlockingToolGateway()
        val engine = FakeLlmEngine(listOf(twoReadsJson, "不应再生成。"))
        val recorder = InMemoryTurnRecorder()
        val job = launch {
            loop(engine, tools, recorder = recorder, queue = queue).runTurn("读取两个文件")
        }

        tools.awaitStarted()
        assertEquals(1, tools.executed.size)
        // 工具执行中入队：当前工具完成前不打断。
        queue.requestSteer("先别读第二个，改用文字回答")
        tools.releaseAll()
        job.join()

        // 当前工具完成即停：同一响应中的第二个调用未执行，且不再生成。
        assertEquals(1, tools.executed.size)
        assertEquals(1, engine.receivedMessages.size)
        assertEquals(1, recorder.nodes.count { it.role == TurnRole.TOOL })
        val steerNode = recorder.nodes.last()
        assertEquals(TurnRole.USER, steerNode.role)
        assertEquals("先别读第二个，改用文字回答", steerNode.content)
        assertEquals(listOf(AgentLoop.TAG_STEER), steerNode.tags)
        // 边界 steer 已消费：等待装配层续跑，而非再作为新输入注入。
        assertEquals(TurnAction.ResumeAfterSteer, queue.nextTurnAction())
    }

    @Test
    fun L6_转向后续跑生成_不重复入树() = runTest {
        val queue = SteerQueue()
        val tools = BlockingToolGateway()
        val engine = FakeLlmEngine(listOf(readJson, "按新指令回答。"))
        val recorder = InMemoryTurnRecorder()
        val job = launch {
            SteerTurnDriver(queue).drive(
                loop(engine, tools, recorder = recorder, queue = queue),
                "读文件",
            )
        }

        tools.awaitStarted()
        queue.requestSteer("改用文字总结")
        tools.releaseAll()
        job.join()

        assertEquals(
            listOf(
                TurnRole.USER,
                TurnRole.ASSISTANT,
                TurnRole.TOOL,
                TurnRole.USER,
                TurnRole.ASSISTANT,
            ),
            recorder.nodes.map { it.role },
        )
        // 转向文本只入树一次（由 Loop 在边界入树），续跑不再追加 user 节点。
        assertEquals(1, recorder.nodes.count { it.role == TurnRole.USER && AgentLoop.TAG_STEER in it.tags })
        assertEquals("按新指令回答。", recorder.nodes.last().content)
        assertEquals(2, engine.receivedMessages.size)
        assertNull(queue.nextTurnAction())
    }

    @Test
    fun L7_确认挂起中steer_等价Deny并转向() = runTest {
        val queue = SteerQueue()
        val gate = HangingGate()
        val tools = FakeToolGateway()
        val engine = FakeLlmEngine(listOf(writeJson, "好的，按新指令回答。"))
        val recorder = InMemoryTurnRecorder()
        val job = launch {
            SteerTurnDriver(queue).drive(
                loop(engine, tools, gate, recorder, queue),
                "写文件",
            )
        }

        gate.entered.await()
        assertTrue(tools.executed.isEmpty())

        queue.requestSteer("不要写了，改为说明原因")
        job.join()

        // 确认挂起被转向取代：等价 Deny，工具不执行，拒绝话术回注。
        assertTrue(tools.executed.isEmpty())
        val toolNode = recorder.nodes.single { it.role == TurnRole.TOOL }
        assertEquals(ToolOutcomeStatus.DENIED, toolNode.toolStatus)
        assertEquals(AgentLoop.DENIED_REPLY, toolNode.content)
        // 转向文本以 tags=[steer] 入树，并按 steer 自当前上下文续跑生成。
        val steerNode = recorder.nodes.single {
            it.role == TurnRole.USER && AgentLoop.TAG_STEER in it.tags
        }
        assertEquals("不要写了，改为说明原因", steerNode.content)
        assertEquals("好的，按新指令回答。", recorder.nodes.last().content)
        assertEquals(2, engine.receivedMessages.size)
    }

    @Test
    fun L7_挂起前steer已到_不进入确认门() = runTest {
        val queue = SteerQueue()
        val gate = HangingGate()
        val tools = FakeToolGateway()
        val engine = FakeLlmEngine(listOf(writeJson, "按新指令回答。"))
        val recorder = InMemoryTurnRecorder()
        queue.requestSteer("先别写文件")

        loop(engine, tools, gate, recorder, queue).runTurn("写文件")

        assertFalse(gate.entered.isCompleted)
        assertTrue(tools.executed.isEmpty())
        val toolNode = recorder.nodes.single { it.role == TurnRole.TOOL }
        assertEquals(ToolOutcomeStatus.DENIED, toolNode.toolStatus)
        assertTrue(AgentLoop.TAG_STEER in recorder.nodes.last().tags)
        assertEquals("先别写文件", recorder.nodes.last().content)
    }

    @Test
    fun Q3_纯文本生成中steer不硬切_轮末优先注入() = runTest {
        val queue = SteerQueue()
        val engine = BlockingLlmEngine(listOf("第一段回答。", "转向后的回答。"))
        val recorder = InMemoryTurnRecorder()
        val job = launch {
            SteerTurnDriver(queue).drive(
                loop(engine, recorder = recorder, queue = queue),
                "你好",
            )
        }

        engine.awaitStarted()
        queue.requestSteer("换个话题")
        engine.releaseAll()
        job.join()

        // 不硬切：首轮自然结束并完整落树，转向在轮末以最高优先注入。
        assertEquals(
            listOf(TurnRole.USER, TurnRole.ASSISTANT, TurnRole.USER, TurnRole.ASSISTANT),
            recorder.nodes.map { it.role },
        )
        assertEquals("第一段回答。", recorder.nodes[1].content)
        assertEquals("换个话题", recorder.nodes[2].content)
        assertTrue(AgentLoop.TAG_STEER in recorder.nodes[2].tags)
        assertEquals("转向后的回答。", recorder.nodes.last().content)
        assertEquals(2, engine.receivedMessages.size)
        assertNull(queue.nextTurnAction())
    }

    @Test
    fun Q4_多条steer逐条独立成轮且FIFO() = runTest {
        val queue = SteerQueue()
        val tools = BlockingToolGateway()
        val engine = FakeLlmEngine(listOf(readJson, "回应A", "回应B"))
        val recorder = InMemoryTurnRecorder()
        val job = launch {
            SteerTurnDriver(queue).drive(
                loop(engine, tools, recorder = recorder, queue = queue),
                "读文件",
            )
        }

        tools.awaitStarted()
        queue.requestSteer("指令A")
        queue.requestSteer("指令B")
        tools.releaseAll()
        job.join()

        assertEquals(listOf("读文件", "指令A", "指令B"), recorder.userTexts())
        val steerNodes = recorder.nodes.filter {
            it.role == TurnRole.USER && AgentLoop.TAG_STEER in it.tags
        }
        assertEquals(listOf("指令A", "指令B"), steerNodes.map { it.content })
        assertEquals(3, engine.receivedMessages.size)
        assertNull(queue.nextTurnAction())
    }

    @Test
    fun Q1_steer与followUp并存_steer恒先() = runTest {
        val queue = SteerQueue()
        val tools = BlockingToolGateway()
        val engine = FakeLlmEngine(listOf(readJson, "回应转向", "回应后续"))
        val recorder = InMemoryTurnRecorder()
        val job = launch {
            SteerTurnDriver(queue).drive(
                loop(engine, tools, recorder = recorder, queue = queue),
                "读文件",
            )
        }

        tools.awaitStarted()
        queue.offerFollowUp("后续任务")
        queue.requestSteer("转向任务")
        tools.releaseAll()
        job.join()

        assertEquals(listOf("读文件", "转向任务", "后续任务"), recorder.userTexts())
        val steerNode = recorder.nodes.single {
            it.role == TurnRole.USER && it.content == "转向任务"
        }
        assertTrue(AgentLoop.TAG_STEER in steerNode.tags)
        val followUpNode = recorder.nodes.single {
            it.role == TurnRole.USER && it.content == "后续任务"
        }
        assertTrue(followUpNode.tags.isEmpty())
        assertEquals(3, engine.receivedMessages.size)
    }

    @Test
    fun Q5_followUp轮末FIFO衔接() = runTest {
        val queue = SteerQueue()
        val engine = FakeLlmEngine(listOf("首轮完成。", "任务二完成。", "任务三完成。"))
        val recorder = InMemoryTurnRecorder()
        queue.offerFollowUp("任务二")
        queue.offerFollowUp("任务三")

        SteerTurnDriver(queue).drive(
            loop(engine, recorder = recorder, queue = queue),
            "初始任务",
        )

        assertEquals(listOf("初始任务", "任务二", "任务三"), recorder.userTexts())
        val userNodes = recorder.nodes.filter { it.role == TurnRole.USER }
        assertTrue(userNodes.all { it.tags.isEmpty() })
        assertEquals(3, engine.receivedMessages.size)
        assertNull(queue.nextTurnAction())
    }

    @Test
    fun L6_空闲态steer直通_无挂起工具() = runTest {
        val queue = SteerQueue()
        val tools = FakeToolGateway()
        val engine = FakeLlmEngine(listOf("按转向指令回答。"))
        val recorder = InMemoryTurnRecorder()
        queue.requestSteer("空闲时的转向")

        SteerTurnDriver(queue).drive(loop(engine, tools, recorder = recorder, queue = queue))

        assertEquals(1, engine.receivedMessages.size)
        val userNode = recorder.nodes.single { it.role == TurnRole.USER }
        assertEquals("空闲时的转向", userNode.content)
        assertTrue(AgentLoop.TAG_STEER in userNode.tags)
        assertTrue(tools.executed.isEmpty())
        assertNull(queue.nextTurnAction())
    }

    @Test
    fun Q5_空队列无操作() = runTest {
        val queue = SteerQueue()
        val engine = FakeLlmEngine(listOf("不应生成。"))
        val recorder = InMemoryTurnRecorder()

        SteerTurnDriver(queue).drive(loop(engine, recorder = recorder, queue = queue))

        assertTrue(engine.receivedMessages.isEmpty())
        assertTrue(recorder.nodes.isEmpty())
        assertNull(queue.nextTurnAction())
    }

    @Test
    fun 取消传播_驱动被取消时清空队列() = runTest {
        val queue = SteerQueue()
        val tools = BlockingToolGateway()
        val engine = FakeLlmEngine(listOf(readJson, "不应生成。"))
        val recorder = InMemoryTurnRecorder()
        val job = launch {
            SteerTurnDriver(queue).drive(
                loop(engine, tools, recorder = recorder, queue = queue),
                "读文件",
            )
        }

        tools.awaitStarted()
        queue.requestSteer("未消费的转向")
        queue.offerFollowUp("未消费的后续")
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(queue.hasPendingSteer())
        assertFalse(queue.hasQueuedInput())
        assertNull(queue.nextTurnAction())
        assertTrue(recorder.nodes.none { AgentLoop.TAG_STEER in it.tags })
    }
}
