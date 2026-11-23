package com.thatgfsj.qaa.feature.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 命令路由集成单测（Issue #10 验收）：解析器分发后 [TerminalViewModel] 的状态变化。
 * 覆盖顶层命令回显、会话进入 / 退出、prompt 回调、后端锁定与 start window 重启回调。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalShellControllerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun controller(
        viewModel: TerminalViewModel = TerminalViewModel(),
        onPrompt: (String, TerminalMode) -> Unit = { _, _ -> },
        onStartNormalWindow: () -> Unit = {},
    ): TerminalShellController = TerminalShellController(viewModel, onPrompt, onStartNormalWindow)

    @Test
    fun helpCommandEchoesInputAndAppendsChineseTable() {
        val viewModel = TerminalViewModel()
        controller(viewModel).onInput("help")
        val entries = viewModel.uiState.value.entries
        assertEquals(TerminalRole.USER, entries.first().role)
        assertEquals("help", entries.first().text)
        assertEquals(TerminalRole.SYSTEM, entries.last().role)
        assertTrue(entries.last().text.contains("start window"))
    }

    @Test
    fun chatCommandClearsConversationAndSwitchesTopBar() {
        val viewModel = TerminalViewModel()
        viewModel.onAiChunk("旧会话内容")
        val shell = controller(viewModel)
        shell.onInput("chat 1")
        val state = viewModel.uiState.value
        assertEquals(TerminalMode.CHAT, state.mode)
        assertEquals(LocalModelCatalog.SMOKE_MODEL_NAME, state.modelInfo.name)
        assertEquals(ShellStage.SESSION, shell.stage)
        assertFalse(state.entries.any { it.text == "旧会话内容" })
        assertTrue(state.entries.any { it.text.contains("CHAT 会话") })
    }

    @Test
    fun agentCommandSwitchesToAgentMode() {
        val viewModel = TerminalViewModel()
        val shell = controller(viewModel)
        shell.onInput("agent")
        assertEquals(TerminalMode.AGENT, viewModel.uiState.value.mode)
        assertEquals(ShellStage.SESSION, shell.stage)
    }

    @Test
    fun promptInSessionIsEchoedAndForwardedToCallback() {
        val viewModel = TerminalViewModel()
        var forwarded: Pair<String, TerminalMode>? = null
        val shell = controller(viewModel, onPrompt = { text, mode -> forwarded = text to mode })
        shell.onInput("chat 1")
        shell.onInput("你好，骁龙")
        assertEquals("你好，骁龙" to TerminalMode.CHAT, forwarded)
        val entries = viewModel.uiState.value.entries
        assertTrue(entries.any { it.role == TerminalRole.USER && it.text == "你好，骁龙" })
    }

    @Test
    fun promptNoticeIsShownOnlyOncePerSession() {
        val viewModel = TerminalViewModel()
        val shell = controller(viewModel)
        shell.onInput("chat 1")
        shell.onInput("第一句")
        shell.onInput("第二句")
        val notices = viewModel.uiState.value.entries.count { it.text == TerminalShellController.PROMPT_NOTICE }
        assertEquals(1, notices)
    }

    @Test
    fun exitReturnsToShellStateAndLaterTextIsNoLongerPrompt() {
        val viewModel = TerminalViewModel()
        var prompts = 0
        val shell = controller(viewModel, onPrompt = { _, _ -> prompts += 1 })
        shell.onInput("chat 1")
        shell.onInput("/exit")
        assertEquals(ShellStage.SHELL, shell.stage)
        shell.onInput("你好")
        assertEquals(0, prompts)
        assertTrue(viewModel.uiState.value.entries.last().text.contains("未知命令：你好"))
    }

    @Test
    fun backendCommandLocksBackendAndTracksRequestedValue() {
        val viewModel = TerminalViewModel()
        val shell = controller(viewModel)
        shell.onInput("backend cpu")
        assertEquals(BackendRequest.CPU, shell.requestedBackend)
        assertEquals(TerminalBackend.CPU, viewModel.uiState.value.backend)
    }

    @Test
    fun backendAutoRestoresFallbackWithoutOverwritingActiveBackend() {
        val viewModel = TerminalViewModel()
        val shell = controller(viewModel)
        shell.onInput("backend hybrid")
        shell.onInput("backend auto")
        assertEquals(BackendRequest.AUTO, shell.requestedBackend)
        assertEquals(TerminalBackend.HYBRID, viewModel.uiState.value.backend)
    }

    @Test
    fun statusReflectsLockedBackendAfterRoute() {
        val viewModel = TerminalViewModel()
        val shell = controller(viewModel)
        shell.onInput("backend hybrid")
        shell.onInput("/status")
        val status = viewModel.uiState.value.entries.last().text
        assertTrue(status.contains("请求后端：HYBRID"))
        assertTrue(status.contains("实际命中：HYBRID"))
    }

    @Test
    fun startWindowInvokesRestartCallback() {
        val viewModel = TerminalViewModel()
        var restarts = 0
        val shell = controller(viewModel, onStartNormalWindow = { restarts += 1 })
        shell.onInput("start window")
        assertEquals(1, restarts)
        assertEquals(ShellStage.SHELL, shell.stage)
        assertTrue(viewModel.uiState.value.entries.last().text.contains("立即重启"))
    }

    @Test
    fun clearCommandEmptiesConversationButKeepsStage() {
        val viewModel = TerminalViewModel()
        val shell = controller(viewModel)
        viewModel.appendOutput("旧输出")
        shell.onInput("clear")
        val entries = viewModel.uiState.value.entries
        assertEquals(1, entries.size)
        assertTrue(entries.single().text.contains("已清空当前会话区"))
        assertEquals(ShellStage.SHELL, shell.stage)
    }

    @Test
    fun modelCommandListsModelsInConversation() {
        val viewModel = TerminalViewModel()
        controller(viewModel).onInput("model")
        assertTrue(viewModel.uiState.value.entries.last().text.contains("Supra2.5-Pro-Instruct(Q4_0)"))
    }

    @Test
    fun unknownShellCommandShowsChineseHint() {
        val viewModel = TerminalViewModel()
        controller(viewModel).onInput("chatx")
        val text = viewModel.uiState.value.entries.last().text
        assertTrue(text.contains("未知命令：chatx"))
        assertTrue(text.contains("help"))
    }

    @Test
    fun emptyInputIsIgnoredByRouter() {
        val viewModel = TerminalViewModel()
        controller(viewModel).onInput("   ")
        assertTrue(viewModel.uiState.value.entries.isEmpty())
    }
}
