package com.thatgfsj.qaa.feature.terminal

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

/**
 * TerminalViewModel 状态逻辑单测（Issue #9 验收项）：
 * 流式文本缓冲、滚动跟随开关、[TOOL] 确认区状态机、reset 清屏能力、fake metrics 心跳。
 * 全部纯 JVM 运行，不依赖 Android 模拟器。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- 流式文本缓冲 ----

    @Test
    fun consecutiveAiChunksMergeIntoLastAiEntry() {
        val vm = TerminalViewModel()
        vm.onAiChunk("你")
        vm.onAiChunk("好，")
        vm.onAiChunk("骁龙")
        val entries = vm.uiState.value.entries
        assertEquals(1, entries.size)
        assertEquals(TerminalRole.AI, entries.single().role)
        assertEquals("你好，骁龙", entries.single().text)
    }

    @Test
    fun aiChunkAfterUserInputStartsNewAiEntry() {
        val vm = TerminalViewModel()
        vm.onUserInput("你好")
        vm.onAiChunk("在")
        vm.onAiChunk("吗")
        val entries = vm.uiState.value.entries
        assertEquals(2, entries.size)
        assertEquals(TerminalRole.USER, entries[0].role)
        assertEquals("你好", entries[0].text)
        assertEquals(TerminalRole.AI, entries[1].role)
        assertEquals("在吗", entries[1].text)
    }

    @Test
    fun blankUserInputIsIgnored() {
        val vm = TerminalViewModel()
        vm.onUserInput("   ")
        assertTrue(vm.uiState.value.entries.isEmpty())
    }

    // ---- 滚动跟随 ----

    @Test
    fun followOutputDefaultsOnAndToggles() {
        val vm = TerminalViewModel()
        assertTrue(vm.uiState.value.followOutput)
        vm.setFollowOutput(false)
        assertFalse(vm.uiState.value.followOutput)
        vm.setFollowOutput(true)
        assertTrue(vm.uiState.value.followOutput)
    }

    // ---- [TOOL] 确认区状态机 ----

    @Test
    fun toolRequestBecomesPendingAndAllowResolves() {
        val vm = TerminalViewModel()
        vm.requestToolConfirmation("read(\".\")")
        val pending = vm.uiState.value.toolConfirm
        assertTrue(pending is ToolConfirmState.Pending)
        assertEquals("read(\".\")", (pending as ToolConfirmState.Pending).description)
        vm.onToolAllow()
        val resolved = vm.uiState.value.toolConfirm
        assertTrue(resolved is ToolConfirmState.Resolved)
        assertTrue((resolved as ToolConfirmState.Resolved).allowed)
    }

    @Test
    fun toolDenyResolvesNotAllowed() {
        val vm = TerminalViewModel()
        vm.requestToolConfirmation("write(\"a.txt\")")
        vm.onToolDeny()
        val resolved = vm.uiState.value.toolConfirm as ToolConfirmState.Resolved
        assertFalse(resolved.allowed)
    }

    @Test
    fun resolveWithoutPendingIsNoOp() {
        val vm = TerminalViewModel()
        vm.onToolAllow()
        vm.onToolDeny()
        assertEquals(ToolConfirmState.Idle, vm.uiState.value.toolConfirm)
    }

    @Test
    fun secondToolRequestReplacesPreviousWithFreshId() {
        val vm = TerminalViewModel()
        vm.requestToolConfirmation("read(\".\")")
        vm.onToolAllow()
        vm.requestToolConfirmation("edit(\"b.kt\")")
        val pending = vm.uiState.value.toolConfirm as ToolConfirmState.Pending
        assertEquals("edit(\"b.kt\")", pending.description)
        assertTrue(pending.requestId > 1L)
    }

    @Test
    fun dismissToolConfirmationClearsPendingOnly() {
        val vm = TerminalViewModel()
        vm.requestToolConfirmation("write 路径=\"a.txt\"")
        vm.dismissToolConfirmation()
        assertEquals(ToolConfirmState.Idle, vm.uiState.value.toolConfirm)
        // 已决策结果不因取消清理被抹掉。
        vm.requestToolConfirmation("edit 路径=\"b.kt\"")
        vm.onToolAllow()
        vm.dismissToolConfirmation()
        assertTrue(vm.uiState.value.toolConfirm is ToolConfirmState.Resolved)
    }

    // ---- reset（清屏再渲染能力，Issue #10 接线） ----

    @Test
    fun resetSessionClearsConversationToolAndRestoresFollow() {
        val vm = TerminalViewModel()
        vm.onUserInput("hi")
        vm.onAiChunk("hi back")
        vm.requestToolConfirmation("read(\".\")")
        vm.setFollowOutput(false)
        vm.resetSession()
        val state = vm.uiState.value
        assertTrue(state.entries.isEmpty())
        assertEquals(ToolConfirmState.Idle, state.toolConfirm)
        assertTrue(state.followOutput)
        // 清屏后 AI 片段应新起一行，不复用旧缓冲。
        vm.onAiChunk("新会话")
        val after = vm.uiState.value.entries
        assertEquals(1, after.size)
        assertEquals(TerminalRole.AI, after.single().role)
        assertEquals("新会话", after.single().text)
    }

    // ---- 顶部常驻栏接线（命令回显 setters） ----

    @Test
    fun commandWiringSettersUpdateTopBarState() {
        val vm = TerminalViewModel()
        vm.setMode(TerminalMode.AGENT)
        vm.setBackend(TerminalBackend.CPU)
        vm.setModelInfo("Llama-3.2-3B", "Q4_0")
        val state = vm.uiState.value
        assertEquals(listOf("[MODEL] Llama-3.2-3B(Q4_0)", "[AGENT]", "[CPU]"), state.topBarSegments())
    }

    // ---- fake metrics 心跳（Issue #5 接线形状验证） ----

    @Test
    fun fakeTickerDrivesMetricsOverVirtualTime() = runTest {
        // 在本测试作用域内把 Main 换成共享同一虚拟时钟的调度器，驱动 viewModelScope。
        // 注意：不能在体内提前 resetMain——ticker 心跳仍以 Main 派遣后续任务，
        // Main 的重置统一交给类级 tearDown。
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = TerminalViewModel(FakeMetricsTicker(intervalMs = 100))
        runCurrent()
        val before = vm.uiState.value.metrics
        assertNull(before.ttftMs)
        assertEquals(0, before.tokensUsed)
        advanceTimeBy(350)
        runCurrent()
        val after = vm.uiState.value.metrics
        assertEquals(142L, after.ttftMs)
        // 350ms / 100ms 间隔 -> 3 次心跳，每次 +24 Token。
        assertEquals(72, after.tokensUsed)
        assertEquals(38.4, after.tokensPerSecond ?: -1.0, 1e-9)
        // 收尾：显式取消 viewModelScope（心跳与收集协程），否则 ticker 会
        // 无限向测试调度器重投任务，runTest 收尾循环永不满足退出条件。
        vm.viewModelScope.cancel()
        runCurrent()
    }

    @Test
    fun fakeTickerSnapshotSequenceIsDeterministic() {
        val ticker = FakeMetricsTicker()
        val snapshots = buildList {
            repeat(6) { add(ticker.nextSnapshot()) }
        }
        assertEquals(listOf(36.0, 37.2, 38.4, 39.6, 40.8, 36.0), snapshots.map { it.tokensPerSecond!! })
        assertEquals(listOf(24, 48, 72, 96, 120, 144), snapshots.map { it.tokensUsed })
        assertEquals(142L, snapshots.first().ttftMs)
    }

    @Test
    fun fakeTickerCapsTokensAtContextWindow() {
        val ticker = FakeMetricsTicker(contextTokens = 48)
        val tick1 = ticker.nextSnapshot()
        val tick2 = ticker.nextSnapshot()
        val tick3 = ticker.nextSnapshot()
        assertEquals(24, tick1.tokensUsed)
        assertEquals(36.0, tick1.tokensPerSecond!!, 1e-9)
        // 达到 nCtx 后 Token 封顶、速度归零（生成结束语义）。
        assertEquals(48, tick2.tokensUsed)
        assertEquals(0.0, tick2.tokensPerSecond!!, 1e-9)
        assertEquals(48, tick3.tokensUsed)
    }

    // ---- #93 会话级确认记忆（yes / no 长按） ----

    @Test
    fun rememberAllowStoresSessionPolicyAndResolvesPending() {
        val vm = TerminalViewModel()
        vm.requestToolConfirmation("write(\"a.txt\")")
        vm.rememberToolDecision(allowed = true)
        val state = vm.uiState.value
        assertEquals(ToolConfirmPolicy.AUTO_ALLOW, state.confirmPolicy)
        val resolved = state.toolConfirm as ToolConfirmState.Resolved
        assertTrue(resolved.allowed)
    }

    @Test
    fun rememberDenyStoresSessionPolicyEvenWithoutPending() {
        val vm = TerminalViewModel()
        vm.rememberToolDecision(allowed = false)
        assertEquals(ToolConfirmPolicy.AUTO_DENY, vm.uiState.value.confirmPolicy)
        // 无挂起项时只记策略，不伪造一次确认结果。
        assertEquals(ToolConfirmState.Idle, vm.uiState.value.toolConfirm)
    }

    @Test
    fun sessionMemoryResetsOnClearScreenAndOnExit() {
        val vm = TerminalViewModel()
        vm.rememberToolDecision(allowed = true)
        vm.resetSession()
        assertEquals(ToolConfirmPolicy.ASK, vm.uiState.value.confirmPolicy)

        vm.rememberToolDecision(allowed = false)
        vm.resetToolConfirmPolicy()
        assertEquals(ToolConfirmPolicy.ASK, vm.uiState.value.confirmPolicy)
        assertEquals(ToolConfirmState.Idle, vm.uiState.value.toolConfirm)
    }
}
