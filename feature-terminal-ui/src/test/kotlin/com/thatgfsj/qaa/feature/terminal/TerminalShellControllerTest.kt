package com.thatgfsj.qaa.feature.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
        agent: TerminalAgentPort? = null,
        modelList: TerminalModelListPort? = null,
        modelPull: TerminalModelPullPort? = null,
        onRequestModelImport: () -> Unit = {},
        onRequestStorageGrant: () -> Unit = {},
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    ): TerminalShellController = TerminalShellController(
        viewModel = viewModel,
        onPrompt = onPrompt,
        onStartNormalWindow = onStartNormalWindow,
        agent = agent,
        modelList = modelList,
        modelPull = modelPull,
        onRequestModelImport = onRequestModelImport,
        onRequestStorageGrant = onRequestStorageGrant,
        scope = scope,
    )

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
        val notices = viewModel.uiState.value.entries.count { it.text == TerminalShellController.UNWIRED_NOTICE }
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

    // ---- Issue #19 执行体接线 ----

    @Test
    fun promptStartsRealTurnWhenIdle() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 1")
        shell.onInput("帮我读一下 README")
        advanceUntilIdle()
        assertEquals(listOf("帮我读一下 README"), port.turns)
    }

    @Test
    fun promptWhileRunningBecomesFollowUp() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort(initial = TerminalAgentState.RUNNING)
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 1")
        shell.onInput("补充一句")
        advanceUntilIdle()
        assertEquals(listOf("补充一句"), port.followUps)
        assertTrue(port.turns.isEmpty())
        assertTrue(viewModel.uiState.value.entries.any { it.text.contains("Follow-up") })
    }

    @Test
    fun treeCommandPrintsSessionTreeFromPort() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 1")
        shell.onInput("/tree")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.entries.any { it.text == port.treeText })
    }

    @Test
    fun compactSkillsAndStatusRouteToAgentPort() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 1")
        shell.onInput("/compact")
        shell.onInput("/skills")
        shell.onInput("/status")
        advanceUntilIdle()
        val texts = viewModel.uiState.value.entries.map { it.text }
        assertTrue(texts.any { it == port.compactText })
        assertTrue(texts.any { it == port.skillsText })
        assertTrue(texts.any { it == port.statusText })
    }

    @Test
    fun forkCommandCarriesNodeIdToPort() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 1")
        shell.onInput("/fork n-000042")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.entries.any { it.text.contains("n-000042") })
    }

    @Test
    fun steerCommandSubmitsInstruction() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort(initial = TerminalAgentState.RUNNING)
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 1")
        shell.onInput("/steer 先解释再说")
        advanceUntilIdle()
        assertEquals(listOf("先解释再说"), port.steers)
    }

    @Test
    fun modelListComesFromInjectedCatalogAndChatResolvesOrdinal() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val models = listOf(
            LocalModelEntry(1, "Supra2.5-Pro-Instruct", "Q4_0"),
            LocalModelEntry(2, "Qwen3-4B", "Q4_K_M"),
        )
        val shell = controller(viewModel, agent = port, modelList = FakeTerminalModelList(models), scope = this)
        advanceUntilIdle()
        shell.onInput("model")
        val listText = viewModel.uiState.value.entries.last().text
        assertTrue(listText.contains("2. Qwen3-4B(Q4_K_M)"))
        shell.onInput("chat 2")
        advanceUntilIdle()
        assertEquals("Qwen3-4B(Q4_K_M)", viewModel.uiState.value.modelInfo.displayText)
        assertEquals(listOf("Qwen3-4B"), port.selectedModels)
    }

    @Test
    fun modelPullEchoesProgressAndCompletion() = runTest {
        val viewModel = TerminalViewModel()
        val events = listOf(
            TerminalPullEvent.Progress(bytes = 0, totalBytes = 100, percent = 0f),
            TerminalPullEvent.Progress(bytes = 55, totalBytes = 100, percent = 0.55f),
            TerminalPullEvent.Completed(name = "org/repo", path = "/data/models/repo.gguf"),
        )
        val shell = controller(
            viewModel,
            modelPull = FakeTerminalModelPull(events),
            scope = this,
        )
        shell.onInput("model pull https://huggingface.co/org/repo")
        advanceUntilIdle()
        val texts = viewModel.uiState.value.entries.map { it.text }
        assertTrue(texts.any { it.contains("0%") })
        assertTrue(texts.any { it.contains("55%") })
        assertTrue(texts.any { it.contains("模型下载完成：org/repo") && it.contains("/data/models/repo.gguf") })
    }

    @Test
    fun modelImportRequestsPickerFromHost() = runTest {
        val viewModel = TerminalViewModel()
        var requests = 0
        val shell = controller(viewModel, onRequestModelImport = { requests += 1 }, scope = this)
        shell.onInput("model import")
        advanceUntilIdle()
        assertEquals(1, requests)
        assertTrue(viewModel.uiState.value.entries.any { it.text.contains("系统文件选择器") })
    }

    @Test
    fun storageGrantRequestsPickerFromHost() = runTest {
        val viewModel = TerminalViewModel()
        var requests = 0
        val shell = controller(viewModel, onRequestStorageGrant = { requests += 1 }, scope = this)
        shell.onInput("storage grant")
        advanceUntilIdle()
        assertEquals(1, requests)
        assertTrue(viewModel.uiState.value.entries.any { it.text.contains("目录选择器") })
    }

    @Test
    fun backendCommandForwardsLockToAgentPort() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("backend cpu")
        advanceUntilIdle()
        assertEquals(listOf(BackendRequest.CPU), port.backends)
    }

    @Test
    fun newCommandClearsConversationAndEchoesNewSessionId() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 1")
        advanceUntilIdle()
        viewModel.appendOutput("旧会话内容")
        shell.onInput("/new")
        advanceUntilIdle()
        assertEquals(1, port.newSessionCount)
        val texts = viewModel.uiState.value.entries.map { it.text }
        assertFalse(texts.any { it == "旧会话内容" })
        assertTrue(texts.single().contains(port.newSessionText))
        assertEquals(ShellStage.SESSION, shell.stage)
    }
}
