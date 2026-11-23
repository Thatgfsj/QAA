package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentLoop token 透传单测（Issue #64）：多片段逐 token 转发、未传回调时零破坏、
 * 确认挂起期间不外透、续跑生成同样透传。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopTokenTest {

    private val readJson = """{"tool":"read","args":{"path":"a.txt"}}"""
    private val writeJson = """{"tool":"write","args":{"path":"b.txt","content":"hi"}}"""

    /** 按生成步脚本化片段：每次 streamGenerate 弹出一组片段，并记录收到的回调。 */
    private class TokenScriptedEngine(
        private val steps: List<List<String>>,
    ) : LlmEngineGateway {

        val callbacks = mutableListOf<(String) -> Unit>()

        override suspend fun streamGenerate(
            messages: List<PromptMessage>,
            onToken: (String) -> Unit,
        ): GenerationOutput {
            callbacks += onToken
            val step = steps[minOf(callbacks.size - 1, steps.lastIndex)]
            step.forEach(onToken)
            return GenerationOutput(text = step.joinToString(""))
        }
    }

    /** 挂起式确认门：到达后一直等待，用于断言挂起窗口内没有新 token。 */
    private class SuspendingGate : ConfirmationGate {

        private val decision = CompletableDeferred<ConfirmationDecision>()

        @Volatile
        var suspended: Boolean = false
            private set

        override suspend fun awaitConfirmation(call: ToolCall): ConfirmationDecision {
            suspended = true
            return decision.await()
        }

        fun allow() {
            decision.complete(ConfirmationDecision.ALLOW)
        }
    }

    private fun loop(
        engine: LlmEngineGateway,
        recorder: TurnRecorder = InMemoryTurnRecorder(),
        gate: ConfirmationGate = FakeConfirmationGate(listOf(ConfirmationDecision.ALLOW)),
    ) = AgentLoop(engine = engine, tools = FakeToolGateway(), gate = gate, recorder = recorder)

    @Test
    fun 多生成步的片段按序逐个透传() = runTest {
        // 首步必须是工具调用，Loop 才会继续下一步生成（纯文本即终答）。
        val engine = TokenScriptedEngine(listOf(listOf(readJson), listOf("最终", "回答")))
        val tokens = mutableListOf<String>()

        loop(engine).runTurn("读文件", emptyList(), onToken = { tokens += it })

        assertEquals(listOf(readJson, "最终", "回答"), tokens)
    }

    @Test
    fun 未传回调时引擎仍拿到可安全调用的空实现() = runTest {
        val engine = TokenScriptedEngine(listOf(listOf("你好")))
        val recorder = InMemoryTurnRecorder()

        loop(engine, recorder).runTurn("你好", emptyList())

        assertEquals("你好", recorder.nodes.last().content)
        assertEquals(1, engine.callbacks.size)
        // 默认空实现：调用不抛错，也不改变落树结果。
        engine.callbacks.single().invoke("片段")
        assertEquals("你好", recorder.nodes.last().content)
    }

    @Test
    fun 确认挂起期间不产生额外token_放行后继续透传() = runTest {
        val engine = TokenScriptedEngine(listOf(listOf(readJson), listOf(writeJson), listOf("已完成")))
        val gate = SuspendingGate()
        val tokens = mutableListOf<String>()

        val job = launch {
            loop(engine, gate = gate).runTurn("读后再写", emptyList(), onToken = { tokens += it })
        }
        advanceUntilIdle()

        assertTrue(gate.suspended)
        assertEquals(listOf(readJson, writeJson), tokens)
        // 挂起窗口内轮次协程被确认门挡住，不产生任何新片段。
        advanceUntilIdle()
        assertEquals(listOf(readJson, writeJson), tokens)

        gate.allow()
        job.join()
        assertEquals(listOf(readJson, writeJson, "已完成"), tokens)
    }

    @Test
    fun 续跑生成同样透传token() = runTest {
        val engine = TokenScriptedEngine(listOf(listOf("续", "写")))
        val tokens = mutableListOf<String>()

        loop(engine).resumeTurn { tokens += it }

        assertEquals(listOf("续", "写"), tokens)
    }
}
