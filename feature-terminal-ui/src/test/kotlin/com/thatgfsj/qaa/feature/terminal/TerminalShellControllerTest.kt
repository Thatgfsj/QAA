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
        shell.onInput("chat 0")
        val state = viewModel.uiState.value
        assertEquals(TerminalMode.CHAT, state.mode)
        assertEquals(LocalModelCatalog.SMOKE_MODEL_NAME, state.modelInfo.name)
        assertEquals(ShellStage.SESSION, shell.stage)
        // 清屏即干净空态：无「进入会话」类系统回显，顶栏承担模式告知。
        assertTrue(state.entries.isEmpty())
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
        shell.onInput("chat 0")
        shell.onInput("你好，骁龙")
        assertEquals("你好，骁龙" to TerminalMode.CHAT, forwarded)
        val entries = viewModel.uiState.value.entries
        assertTrue(entries.any { it.role == TerminalRole.USER && it.text == "你好，骁龙" })
    }

    @Test
    fun promptNoticeIsShownOnlyOncePerSession() {
        val viewModel = TerminalViewModel()
        val shell = controller(viewModel)
        shell.onInput("chat 0")
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
        shell.onInput("chat 0")
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
        shell.onInput("chat 0")
        shell.onInput("帮我读一下 README")
        advanceUntilIdle()
        assertEquals(listOf("帮我读一下 README"), port.turns)
    }

    @Test
    fun promptWhileRunningBecomesFollowUp() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort(initial = TerminalAgentState.RUNNING)
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
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
        shell.onInput("chat 0")
        shell.onInput("/tree")
        advanceUntilIdle()
        val text = viewModel.uiState.value.entries.joinToString("\n") { it.text }
        assertTrue(text.contains(port.treeText))
        assertTrue(text.contains(TREE_FORK_HINT))
    }

    @Test
    fun treeCommandEmptyTreeShowsExplicitHint() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort().apply { treeText = "" }
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
        shell.onInput("/tree")
        advanceUntilIdle()
        val text = viewModel.uiState.value.entries.joinToString("\n") { it.text }
        assertTrue(text.contains("会话树为空"))
        assertTrue(text.contains("/fork"))
    }

    @Test
    fun treeCommandRenderFailureBecomesChineseReceipt() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort().apply { treeError = AssertionError("渲染崩溃") }
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
        shell.onInput("/tree")
        advanceUntilIdle()
        val text = viewModel.uiState.value.entries.joinToString("\n") { it.text }
        assertTrue(text.contains("命令执行失败"))
        assertTrue(text.contains("渲染崩溃"))
    }

    @Test
    fun compactSkillsAndStatusRouteToAgentPort() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
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
        shell.onInput("chat 0")
        shell.onInput("/fork n-000042")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.entries.any { it.text.contains("n-000042") })
    }

    @Test
    fun steerCommandSubmitsInstruction() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort(initial = TerminalAgentState.RUNNING)
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
        shell.onInput("/steer 先解释再说")
        advanceUntilIdle()
        assertEquals(listOf("先解释再说"), port.steers)
    }

    @Test
    fun modelListComesFromInjectedCatalogAndChatResolvesOrdinal() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val models = listOf(
            LocalModelEntry(0, "Supra2.5-Pro-Instruct", "Q4_0"),
            LocalModelEntry(1, "Qwen3-4B", "Q4_K_M"),
        )
        val shell = controller(viewModel, agent = port, modelList = FakeTerminalModelList(models), scope = this)
        advanceUntilIdle()
        shell.onInput("model")
        val listText = viewModel.uiState.value.entries.last().text
        assertTrue(listText.contains("1. Qwen3-4B(Q4_K_M)"))
        shell.onInput("chat 1")
        advanceUntilIdle()
        assertEquals("Qwen3-4B(Q4_K_M)", viewModel.uiState.value.modelInfo.displayText)
        assertEquals(listOf("Qwen3-4B"), port.selectedModels)
    }

    @Test
    fun modelDeleteWithoutForceOnlyEchoesAndSkipsPort() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val models = listOf(
            LocalModelEntry(0, "Supra2.5-Pro-Instruct", "Q4_0"),
            LocalModelEntry(1, "Qwen3-4B", "Q4_K_M", sizeBytes = 2_000_000L, path = "/data/models/qwen3.gguf"),
        )
        val shell = controller(viewModel, agent = port, modelList = FakeTerminalModelList(models), scope = this)
        advanceUntilIdle()

        shell.onInput("model delete 1")
        advanceUntilIdle()

        // 无 --force 只回显影响，不得触达运行链。
        assertTrue(port.deletedModels.isEmpty())
        val text = viewModel.uiState.value.entries.joinToString("\n") { it.text }
        assertTrue(text.contains("确认请再次执行：model delete 1 --force"))
        assertTrue(text.contains("/data/models/qwen3.gguf"))
    }

    @Test
    fun modelDeleteWithForceCallsPortAndRefreshesList() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val models = listOf(
            LocalModelEntry(0, "Supra2.5-Pro-Instruct", "Q4_0"),
            LocalModelEntry(1, "Qwen3-4B", "Q4_K_M"),
        )
        var calls = 0
        val listPort = object : TerminalModelListPort {
            override suspend fun entries(): List<LocalModelEntry> {
                calls += 1
                // 删除成功后宿主清单少一项：第二次刷新不再返回被删模型（#98 不复活）。
                return if (calls == 1) models else models.dropLast(1)
            }
        }
        val shell = controller(viewModel, agent = port, modelList = listPort, scope = this)
        advanceUntilIdle()

        shell.onInput("model delete 1 --force")
        advanceUntilIdle()

        assertEquals(listOf(1 to "Qwen3-4B"), port.deletedModels)
        assertTrue(viewModel.uiState.value.entries.map { it.text }.any { it.contains("已删除模型：Qwen2.5-1.5B") })
        assertEquals(2, calls)
        // 删除后立即按新清单解析：被删模型不再列出，剩余序号仍可选中。
        shell.onInput("model")
        val listText = viewModel.uiState.value.entries.last().text
        assertFalse("清单仍含被删模型：$listText", listText.contains("Qwen3-4B"))
        assertTrue(listText.contains("0. Supra2.5-Pro-Instruct(Q4_0)"))
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
        shell.onInput("chat 0")
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

    // ---- #90 /export 轨迹导出 ----

    @Test
    fun exportCommandCarriesPathAndEchoesChineseResult() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
        shell.onInput("/export exports/session-main.jsonl")
        advanceUntilIdle()

        assertEquals(listOf<String?>("exports/session-main.jsonl"), port.exportedPaths)
        assertTrue(viewModel.uiState.value.entries.any { it.text == port.exportText })
    }

    // ---- #81 清屏净化 / #79 快捷键依赖的模型载入路径 ----

    @Test
    fun chatEntryKeepsConversationEmptyAndFirstInputCreatesUserLine() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.entries.isEmpty())
        assertEquals(listOf(LocalModelCatalog.SMOKE_MODEL_NAME), port.selectedModels)

        shell.onInput("你好，骁龙")
        advanceUntilIdle()
        val entries = viewModel.uiState.value.entries
        assertEquals(1, entries.size)
        assertEquals(TerminalRole.USER, entries.single().role)
        assertEquals("你好，骁龙", entries.single().text)
    }

    @Test
    fun modelLoadFailureOnEntryIsEchoed() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort().apply { selectModelText = "模型加载失败：解码器不兼容" }
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
        advanceUntilIdle()
        val entries = viewModel.uiState.value.entries
        assertEquals(1, entries.size)
        assertTrue(entries.single().text.contains("模型加载失败"))
    }

    // ---- #94 /logs 应用内日志导出 ----

    @Test
    fun logsCommandCarriesLineCountAndEchoesReceipt() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
        shell.onInput("/logs 120")
        advanceUntilIdle()

        assertEquals(listOf<Int?>(120), port.exportedLogLines)
        assertTrue(viewModel.uiState.value.entries.any { it.text == port.logsExportText })
    }

    @Test
    fun logsCommandWithoutCountUsesRuntimeDefault() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
        shell.onInput("/logs")
        advanceUntilIdle()

        assertEquals(listOf<Int?>(null), port.exportedLogLines)
    }

    // ---- #93 会话级确认记忆：/exit 离开会话即恢复询问 ----

    @Test
    fun exitSessionRestoresAskingPolicy() = runTest {
        val viewModel = TerminalViewModel()
        val shell = controller(viewModel, agent = FakeTerminalAgentPort(), scope = this)
        shell.onInput("chat 0")
        viewModel.rememberAutoAllow(enabled = true)
        assertEquals(ToolConfirmPolicy.AUTO_ALLOW, viewModel.uiState.value.confirmPolicy)

        shell.onInput("/exit")
        advanceUntilIdle()
        assertEquals(ShellStage.SHELL, shell.stage)
        assertEquals(ToolConfirmPolicy.ASK, viewModel.uiState.value.confirmPolicy)
    }

    // ---- #106 /ctx 上下文长度路由 ----

    @Test
    fun ctxWithoutArgumentEchoesContextReceipt() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
        shell.onInput("/ctx")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.entries.any { it.text == port.contextLengthText })
    }

    @Test
    fun ctxWithFreeValueCarriesValueToPort() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
        shell.onInput("/ctx 5000")
        advanceUntilIdle()

        assertEquals(listOf(5000), port.contextLengths)
        assertTrue(viewModel.uiState.value.entries.any { it.text == port.contextLengthSetText })
    }

    @Test
    fun ctxWithSuffixCarriesNormalizedValueToPort() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
        shell.onInput("/ctx 20k")
        advanceUntilIdle()

        assertEquals(listOf(20480), port.contextLengths)
    }

    @Test
    fun ctxWithInvalidValueDoesNotReachPort() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
        shell.onInput("/ctx 1000")
        advanceUntilIdle()

        assertTrue(port.contextLengths.isEmpty())
        assertTrue(viewModel.uiState.value.entries.last().text.contains("上下文长度无效"))
    }

    // ---- #105 /system 系统提示词路由 ----

    @Test
    fun systemCommandQueryEchoesFullPrompt() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
        shell.onInput("/system")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.entries.any { it.text == port.systemPromptText })
    }

    @Test
    fun systemCommandWithTextForwardsWholeReplacement() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("chat 0")
        shell.onInput("/system 你是简洁助手，只回答中文")
        advanceUntilIdle()

        assertEquals(listOf<String?>("你是简洁助手，只回答中文"), port.systemPromptSets)
        assertTrue(viewModel.uiState.value.entries.any { it.text == port.systemPromptSetText })
    }

    @Test
    fun systemResetForwardsNullAndWorksInShellStage() = runTest {
        val viewModel = TerminalViewModel()
        val port = FakeTerminalAgentPort()
        val shell = controller(viewModel, agent = port, scope = this)
        shell.onInput("/system reset")
        advanceUntilIdle()

        assertEquals(listOf<String?>(null), port.systemPromptSets)
        assertTrue(viewModel.uiState.value.entries.any { it.text == port.systemPromptSetText })
    }
}
