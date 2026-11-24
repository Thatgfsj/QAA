package com.thatgfsj.qaa.feature.normal.chat

import com.thatgfsj.qaa.feature.normal.model.ChatRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 会话 fake 流式逻辑 JVM 单测（Issue #8 验收）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun 空白输入_不生成消息() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        vm.send("   ")
        advanceUntilIdle()
        assertTrue(vm.messages.value.isEmpty())
        assertFalse(vm.isGenerating.value)
    }

    @Test
    fun 发送后_用户消息立即入列() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        vm.send("你好")
        advanceUntilIdle()
        assertEquals(2, vm.messages.value.size)
        assertEquals(ChatRole.USER, vm.messages.value.first().role)
        assertEquals("你好", vm.messages.value.first().content)
    }

    @Test
    fun 流式回复_逐token追加() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        vm.send("测试")
        // 启动协程：AI 占位消息入列，内容为空。
        runCurrent()
        assertEquals(ChatRole.ASSISTANT, vm.messages.value.last().role)
        assertEquals("", vm.messages.value.last().content)
        assertTrue(vm.isGenerating.value)

        val tokens = vm.scriptedReplyTokens("测试")
        // 推进 3 个 token 周期：advanceTimeBy 不执行恰在目标时刻的任务，
        // 需补 runCurrent() 才会送达第 3 个 token。
        advanceTimeBy(10 * 3)
        runCurrent()
        assertEquals(tokens.take(3).joinToString(""), vm.messages.value.last().content)
        assertTrue(vm.isGenerating.value)

        // 全部推送完成后：内容完整、退出生成态。
        advanceUntilIdle()
        assertEquals(tokens.joinToString(""), vm.messages.value.last().content)
        assertFalse(vm.isGenerating.value)
    }

    @Test
    fun 生成中_忽略并发发送() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        vm.send("第一条")
        runCurrent()
        vm.send("第二条") // 生成中，应被忽略
        advanceUntilIdle()
        assertEquals(2, vm.messages.value.size)
        assertEquals("第一条", vm.messages.value.first().content)
    }

    @Test
    fun 待确认描述同步_空白视为无() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        assertNull(vm.toolConfirmPending.value)
        vm.setToolConfirmPending("write 路径=\"a.txt\"")
        assertEquals("write 路径=\"a.txt\"", vm.toolConfirmPending.value)
        vm.setToolConfirmPending("   ")
        assertNull(vm.toolConfirmPending.value)
    }

    @Test
    fun 真实端口_生成中逐token渲染且终稿以端口返回为准() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        val renders = mutableListOf<String>()
        val finished = CompletableDeferred<Unit>()
        val port = object : NormalAgentPort {
            override val assistantTokens: Flow<String> = flow {
                emit("片")
                renders += vm.messages.value.last().content
                emit("段")
                renders += vm.messages.value.last().content
                finished.complete(Unit)
            }

            override suspend fun ask(text: String): String {
                finished.await()
                return "最终答复"
            }
        }
        vm.bindAgent(port)
        vm.send("你好")
        advanceUntilIdle()

        assertEquals(listOf("片", "片段"), renders)
        assertEquals("最终答复", vm.messages.value.last().content)
        assertFalse(vm.isGenerating.value)
    }

    // ---- #91 指标条 ----

    @Test
    fun 未接线指标_显示占位文案() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)

        assertEquals("TTFT -- | 速度 -- tok/s | Token --", vm.metrics.value.metricsBarText())
    }

    @Test
    fun 端口指标流_映射到指标条且随生成更新() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        val source = MutableStateFlow(ChatMetrics())
        val port = object : NormalAgentPort {
            override suspend fun ask(text: String): String = "答复"
            override val chatMetrics: Flow<ChatMetrics> = source
        }
        vm.bindAgent(port)
        advanceUntilIdle()
        assertTrue(vm.metrics.value.isPlaceholder)

        source.value = ChatMetrics(ttftMs = 142, tokensPerSecond = 38.4, tokensUsed = 412, contextTokens = 4096)
        advanceUntilIdle()

        val bar = vm.metrics.value.metricsBarText()
        assertEquals(142L, vm.metrics.value.ttftMs)
        assertTrue(bar.contains("TTFT 142ms"))
        assertTrue(bar.contains("38.4 tok/s"))
        assertTrue(bar.contains("Token 412 / 4096"))
    }
}
