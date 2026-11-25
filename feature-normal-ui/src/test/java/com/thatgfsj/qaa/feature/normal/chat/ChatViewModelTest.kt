package com.thatgfsj.qaa.feature.normal.chat

import com.thatgfsj.qaa.feature.normal.model.ChatRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

/** 会话页时间线状态机 JVM 单测（Issue #8 骨架 + #127 窗口 Agent 化）。 */
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

    private fun ChatViewModel.textSegments(): List<ChatSegment.Text> =
        segments.value.filterIsInstance<ChatSegment.Text>()

    @Test
    fun 空白输入_不生成任何段() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        vm.send("   ")
        advanceUntilIdle()
        assertTrue(vm.segments.value.isEmpty())
        assertFalse(vm.isGenerating.value)
    }

    @Test
    fun 发送后_用户文本段立即入列() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        vm.send("你好")
        advanceUntilIdle()
        val texts = vm.textSegments()
        assertEquals(2, texts.size)
        assertEquals(ChatRole.USER, texts.first().role)
        assertEquals("你好", texts.first().content)
    }

    @Test
    fun fake流式_逐token追加到同一文本段() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        vm.send("测试")
        runCurrent()
        assertEquals(ChatRole.ASSISTANT, vm.textSegments().last().role)
        assertEquals("", vm.textSegments().last().content)
        assertTrue(vm.isGenerating.value)

        val tokens = vm.scriptedReplyTokens("测试")
        advanceTimeBy(10 * 3)
        runCurrent()
        assertEquals(tokens.take(3).joinToString(""), vm.textSegments().last().content)

        advanceUntilIdle()
        assertEquals(tokens.joinToString(""), vm.textSegments().last().content)
        assertFalse(vm.isGenerating.value)
    }

    @Test
    fun 生成中_忽略并发发送() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        vm.send("第一条")
        runCurrent()
        vm.send("第二条")
        advanceUntilIdle()
        assertEquals(1, vm.textSegments().count { it.role == ChatRole.USER })
        assertEquals("第一条", vm.textSegments().first { it.role == ChatRole.USER }.content)
    }

    @Test
    fun 真实端口_流式预览后以返回值为终稿() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        val finished = CompletableDeferred<Unit>()
        val port = object : NormalAgentPort {
            override val assistantTokens: Flow<String> = flow {
                emit("片")
                emit("段")
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

        assertEquals("最终答复", vm.textSegments().last().content)
        assertFalse(vm.isGenerating.value)
    }

    @Test
    fun 工具段_同id更新不追加新项() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        val events = MutableSharedFlow<ChatSegment>(extraBufferCapacity = 16)
        val port = object : NormalAgentPort {
            override suspend fun ask(text: String): String = "答复"
            override val chatSegments: Flow<ChatSegment> = events
        }
        vm.bindAgent(port)
        runCurrent()

        val toolId = 7L shl 40
        events.tryEmit(ChatSegment.ToolCall(toolId, "read", "{}", ToolSegmentStatus.RUNNING))
        advanceUntilIdle()
        assertEquals(1, vm.segments.value.count { it is ChatSegment.ToolCall })

        events.tryEmit(
            ChatSegment.ToolCall(toolId, "read", "{}", ToolSegmentStatus.SUCCESS, result = "ok"),
        )
        advanceUntilIdle()

        val tools = vm.segments.value.filterIsInstance<ChatSegment.ToolCall>()
        assertEquals(1, tools.size)
        assertEquals(ToolSegmentStatus.SUCCESS, tools.single().status)
        assertEquals("ok", tools.single().result)
    }

    @Test
    fun 内联确认_允许与拒绝直达端口() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        val events = MutableSharedFlow<ChatSegment>(extraBufferCapacity = 16)
        var allowCount = 0
        var denyCount = 0
        val port = object : NormalAgentPort {
            override suspend fun ask(text: String): String = "答复"
            override val chatSegments: Flow<ChatSegment> = events

            override fun allowPendingTool() {
                allowCount++
            }

            override fun denyPendingTool() {
                denyCount++
            }
        }
        vm.bindAgent(port)
        runCurrent()
        events.tryEmit(ChatSegment.Confirm(1L shl 40, 2L shl 40, "write 路径=\"a.txt\""))
        advanceUntilIdle()

        assertEquals("write 路径=\"a.txt\"", vm.toolConfirmPending.value)
        vm.allowTool()
        vm.denyTool()
        assertEquals(1, allowCount)
        assertEquals(1, denyCount)
    }

    @Test
    fun 终稿文本_排在先到的执行链之后() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        val events = MutableSharedFlow<ChatSegment>(extraBufferCapacity = 16)
        val port = object : NormalAgentPort {
            override suspend fun ask(text: String): String {
                events.tryEmit(ChatSegment.ToolCall(1L shl 40, "read", "{}", ToolSegmentStatus.SUCCESS))
                return "最终答复"
            }

            override val chatSegments: Flow<ChatSegment> = events
        }
        vm.bindAgent(port)
        vm.send("你好")
        advanceUntilIdle()

        val timeline = vm.segments.value
        val toolIndex = timeline.indexOfFirst { it is ChatSegment.ToolCall }
        val textIndex = timeline.indexOfFirst { it is ChatSegment.Text && it.content == "最终答复" }
        assertTrue(toolIndex >= 0)
        assertTrue(textIndex > toolIndex)
    }

    @Test
    fun 停止_取消本轮并进入可重试态() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        val hang = CompletableDeferred<String>()
        val port = object : NormalAgentPort {
            override suspend fun ask(text: String): String = hang.await()
        }
        vm.bindAgent(port)
        vm.send("你好")
        runCurrent()
        assertTrue(vm.isGenerating.value)

        vm.stop()
        advanceUntilIdle()

        assertFalse(vm.isGenerating.value)
        assertTrue(vm.canRetry.value)
        assertTrue(vm.segments.value.any { it is ChatSegment.Error && it.message == ChatViewModel.STOPPED_MESSAGE })
    }

    @Test
    fun 重试_不重复用户气泡并重跑同一条消息() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        var asked = 0
        val events = MutableSharedFlow<ChatSegment>(extraBufferCapacity = 16)
        val port = object : NormalAgentPort {
            override suspend fun ask(text: String): String {
                asked++
                return "答复$asked"
            }

            override val chatSegments: Flow<ChatSegment> = events
        }
        vm.bindAgent(port)
        vm.send("你好")
        advanceUntilIdle()
        assertEquals("答复1", vm.textSegments().last().content)

        events.tryEmit(ChatSegment.Error(1L shl 40, "本轮执行失败：超时"))
        advanceUntilIdle()
        assertTrue(vm.canRetry.value)

        vm.retry()
        advanceUntilIdle()

        assertEquals(2, asked)
        assertEquals(1, vm.textSegments().count { it.role == ChatRole.USER })
        assertEquals("答复2", vm.textSegments().last().content)
        assertFalse(vm.segments.value.any { it is ChatSegment.Error })
    }

    @Test
    fun 指标_模型载入中前置状态文案() = runTest {
        val vm = ChatViewModel(tokenIntervalMs = 10)
        val source = MutableStateFlow(ChatMetrics(loading = true))
        val port = object : NormalAgentPort {
            override suspend fun ask(text: String): String = "答复"
            override val chatMetrics: Flow<ChatMetrics> = source
        }
        vm.bindAgent(port)
        advanceUntilIdle()

        assertTrue(vm.metrics.value.loading)
        assertTrue(vm.metrics.value.metricsBarText().startsWith("模型载入中… | "))

        source.value = ChatMetrics(ttftMs = 142, tokensPerSecond = 38.4, tokensUsed = 412, contextTokens = 4096)
        advanceUntilIdle()
        assertEquals(142L, vm.metrics.value.ttftMs)
        assertTrue(vm.metrics.value.metricsBarText().contains("Token 412 / 4096"))
    }

    @Test
    fun 端口指标流_映射到指标条() = runTest {
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

        assertEquals(142L, vm.metrics.value.ttftMs)
        assertTrue(vm.metrics.value.metricsBarText().contains("TTFT 142ms"))
        assertTrue(vm.metrics.value.metricsBarText().contains("Token 412 / 4096"))
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
}
