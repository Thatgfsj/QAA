package com.thatgfsj.qaa.feature.normal.chat

import com.thatgfsj.qaa.feature.normal.model.ChatRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
