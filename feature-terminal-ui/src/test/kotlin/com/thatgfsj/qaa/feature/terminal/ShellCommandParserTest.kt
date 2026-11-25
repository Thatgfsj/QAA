package com.thatgfsj.qaa.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shell 命令解析器全覆盖单测（Issue #10 验收）：
 * 全部命令、双状态机切换、非法输入、参数边界（无参 / 序号 / 值域 / 多余参数）。
 */
class ShellCommandParserTest {

    private val shell = ShellContext()
    private val session = shell.copy(stage = ShellStage.SESSION, mode = TerminalMode.CHAT)
    private val agentSession = shell.copy(stage = ShellStage.SESSION, mode = TerminalMode.AGENT)
    private val smokeModel = LocalModelCatalog.entries.single()

    private fun parse(input: String, context: ShellContext = shell): ShellParseResult =
        ShellCommandParser.parse(input, context)

    private fun ShellParseResult.text(): String = output.joinToString("\n")

    // ---- help ----

    @Test
    fun helpPrintsChineseTableInShellState() {
        val result = parse("help")
        assertEquals(ShellStage.SHELL, result.stage)
        assertTrue(result.effects.isEmpty())
        assertTrue(result.text().contains("QAA 终端命令帮助（全中文）"))
        assertTrue(result.text().contains("适用状态"))
        assertTrue(result.text().contains("start window"))
    }

    @Test
    fun helpAcceptsSlashAndUppercaseForms() {
        val canonical = parse("help").text()
        assertEquals(canonical, parse("/help").text())
        assertEquals(canonical, parse("HELP").text())
    }

    @Test
    fun helpWorksInSessionStateOnlyWithSlashForm() {
        assertTrue(parse("/help", session).text().contains("QAA 终端命令帮助"))
        assertEquals(listOf(ShellEffect.ForwardPrompt("help")), parse("help", session).effects)
    }

    // ---- 基础输入 ----

    @Test
    fun blankInputProducesNoOutputOrEffects() {
        val result = parse("   ")
        assertEquals(ShellStage.SHELL, result.stage)
        assertTrue(result.output.isEmpty())
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun commandTokensAreCaseInsensitiveAndWhitespaceTolerant() {
        assertTrue(parse("  MODEL  ").text().contains("本地模型清单"))
        assertEquals(ShellStage.SESSION, parse("  CHAT 0  ").stage)
    }

    // ---- model ----

    @Test
    fun modelListsBuiltInSmokeModel() {
        val text = parse("model").text()
        assertTrue(text.contains("0. Supra2.5-Pro-Instruct(Q4_0)"))
        assertTrue(text.contains("当前模型：Supra2.5-Pro-Instruct(Q4_0)"))
    }

    @Test
    fun modelPullEmitsPullEffectAndHintsResume() {
        val url = "https://example.com/supra.gguf"
        val result = parse("model pull $url")
        assertEquals(listOf(ShellEffect.PullModel(url)), result.effects)
        assertTrue(result.text().contains(url))
        assertTrue(result.text().contains("断点续传"))
    }

    @Test
    fun modelPullRequiresUrl() {
        assertTrue(parse("model pull").text().contains("用法：model pull <url>"))
    }

    @Test
    fun modelPullRejectsNonHttpUrl() {
        assertTrue(parse("model pull example.com/x.gguf").text().contains("下载地址无效"))
    }

    @Test
    fun modelPullRejectsExtraArguments() {
        assertTrue(
            parse("model pull https://a.example/1 https://b.example/2")
                .text()
                .contains("只接受一个下载地址参数"),
        )
    }

    // ---- model delete（#111）----

    private val deletableModel = LocalModelEntry(
        index = 1,
        name = "Qwen2.5-1.5B",
        quantization = "Q4_K_M",
        sizeBytes = 900L * 1024 * 1024,
        path = "/data/models/Qwen2.5-1.5B-Q4_K_M.gguf",
    )
    private val withDeletableModel = shell.copy(models = LocalModelCatalog.entries + deletableModel)

    @Test
    fun modelDeleteWithoutArgumentsShowsUsage() {
        val text = parse("model delete", withDeletableModel).text()
        assertTrue(text.contains("用法：model delete <序号|名称> [--force]"))
        assertTrue(text.contains("先不带 --force 执行可查看删除影响"))
    }

    @Test
    fun modelDeleteWithoutForceEchoesImpactAndRequiresForce() {
        val result = parse("model delete 1", withDeletableModel)
        assertTrue(result.effects.isEmpty())
        val text = result.text()
        assertTrue(text.contains("将删除模型：1. Qwen2.5-1.5B(Q4_K_M)"))
        assertTrue(text.contains("体积：900.0 MB"))
        assertTrue(text.contains("/data/models/Qwen2.5-1.5B-Q4_K_M.gguf"))
        assertTrue(text.contains("操作不可撤销"))
        assertTrue(text.contains("确认请再次执行：model delete 1 --force"))
    }

    @Test
    fun modelDeleteWithForceEmitsDeleteEffect() {
        val result = parse("model delete 1 --force", withDeletableModel)
        assertEquals(listOf(ShellEffect.DeleteModel(1, "Qwen2.5-1.5B")), result.effects)
        assertTrue(result.text().contains("正在删除模型：1. Qwen2.5-1.5B(Q4_K_M)"))
    }

    @Test
    fun modelDeleteAcceptsNameAndForceBeforeTarget() {
        assertTrue(parse("model delete qwen2.5-1.5b", withDeletableModel).text().contains("将删除模型：1."))
        assertEquals(
            listOf(ShellEffect.DeleteModel(1, "Qwen2.5-1.5B")),
            parse("model delete --force qwen2.5-1.5b", withDeletableModel).effects,
        )
    }

    @Test
    fun modelDeleteRefusesBuiltInSmokeModelEvenWithForce() {
        assertTrue(parse("model delete 0", withDeletableModel).text().contains("内置冒烟模型不可删除"))
        val forced = parse("model delete 0 --force", withDeletableModel)
        assertTrue(forced.text().contains("内置冒烟模型不可删除"))
        assertTrue(forced.effects.isEmpty())
    }

    @Test
    fun modelDeleteRejectsUnknownOptionAndMultipleTargets() {
        assertTrue(parse("model delete 1 --yes", withDeletableModel).text().contains("model delete 只支持 --force"))
        assertTrue(parse("model delete 1 2", withDeletableModel).text().contains("只接受一个模型参数"))
    }

    @Test
    fun modelDeleteRejectsUnknownModel() {
        assertTrue(parse("model delete ghost", withDeletableModel).text().contains("未找到本地模型：ghost"))
    }

    @Test
    fun modelImportRequestsSystemFilePicker() {
        val result = parse("model import")
        assertEquals(listOf(ShellEffect.ImportModel), result.effects)
        assertTrue(result.text().contains("系统文件选择器"))
        assertTrue(result.text().contains(".gguf"))
    }

    @Test
    fun modelImportRejectsArguments() {
        assertTrue(parse("model import /sdcard/model.gguf").text().contains("不接受参数"))
    }

    @Test
    fun unknownModelSubcommandShowsUsage() {
        assertTrue(parse("model list").text().contains("未知 model 子命令：list"))
    }

    // ---- chat / agent 进入会话 ----

    @Test
    fun chatByZeroEntersSessionAndClearsScreenWithoutEcho() {
        val result = parse("chat 0")
        assertEquals(ShellStage.SESSION, result.stage)
        assertEquals(
            listOf(
                ShellEffect.ClearScreen,
                ShellEffect.SetMode(TerminalMode.CHAT),
                ShellEffect.SelectModel(smokeModel),
            ),
            result.effects,
        )
        // 清屏后不追加任何系统回显；模式告知由顶栏 [CHAT] 承担。
        assertTrue(result.output.isEmpty())
    }

    @Test
    fun chatAcceptsModelNameCaseInsensitively() {
        val result = parse("chat supra2.5-pro-instruct")
        assertEquals(ShellStage.SESSION, result.stage)
        assertTrue(result.effects.contains(ShellEffect.SelectModel(smokeModel)))
    }

    @Test
    fun chatWithoutModelShowsUsage() {
        val result = parse("chat")
        assertEquals(ShellStage.SHELL, result.stage)
        assertTrue(result.effects.isEmpty())
        assertTrue(result.text().contains("用法：chat <模型名|序号>"))
    }

    @Test
    fun chatRejectsUnknownModelName() {
        val result = parse("chat 不存在模型")
        assertEquals(ShellStage.SHELL, result.stage)
        assertTrue(result.effects.isEmpty())
        assertTrue(result.text().contains("未找到本地模型：不存在模型"))
    }

    @Test
    fun chatRejectsOutOfRangeIndexes() {
        assertTrue(parse("chat 1").text().contains("未找到本地模型：1"))
        assertTrue(parse("chat 9").text().contains("未找到本地模型：9"))
    }

    @Test
    fun chatRejectsExtraArguments() {
        assertTrue(parse("chat 1 2").text().contains("只接受一个模型参数"))
    }

    @Test
    fun agentWithoutModelKeepsCurrentModelAndSwitchesMode() {
        val result = parse("agent")
        assertEquals(ShellStage.SESSION, result.stage)
        assertTrue(result.effects.contains(ShellEffect.ClearScreen))
        assertTrue(result.effects.contains(ShellEffect.SetMode(TerminalMode.AGENT)))
        assertFalse(result.effects.any { it is ShellEffect.SelectModel })
        assertTrue(result.output.isEmpty())
    }

    @Test
    fun agentWithIndexSelectsModel() {
        val result = parse("agent 0")
        assertTrue(result.effects.contains(ShellEffect.SelectModel(smokeModel)))
    }

    // ---- start window ----

    @Test
    fun startWindowRequestsNormalWindowRestart() {
        val result = parse("start window")
        assertEquals(ShellStage.SHELL, result.stage)
        assertEquals(listOf(ShellEffect.StartNormalWindow), result.effects)
        assertTrue(result.text().contains("立即重启"))
    }

    @Test
    fun startWindowRejectsMissingOrExtraArguments() {
        val message = "start 仅支持 start window"
        assertTrue(parse("start").text().contains(message))
        assertTrue(parse("start terminal").text().contains(message))
        assertTrue(parse("start window now").text().contains(message))
    }

    // ---- backend ----

    @Test
    fun backendWithoutArgumentShowsRequestAndActiveBackend() {
        val text = parse("backend").text()
        assertTrue(text.contains("请求后端：auto（自动降级链）"))
        assertTrue(text.contains("实际命中：NPU"))
    }

    @Test
    fun backendLocksRequestedValue() {
        val result = parse("backend npu")
        assertEquals(listOf(ShellEffect.SetBackend(BackendRequest.NPU)), result.effects)
        assertTrue(result.text().contains("已锁定请求后端：NPU"))
    }

    @Test
    fun backendAcceptsWholeValueDomainCaseInsensitively() {
        listOf("auto", "npu", "hybrid", "cpu").forEach { value ->
            val result = parse("backend $value")
            assertTrue("值域 $value", result.effects.single() is ShellEffect.SetBackend)
        }
        assertEquals(
            listOf(ShellEffect.SetBackend(BackendRequest.HYBRID)),
            parse("backend HYBRID").effects,
        )
    }

    @Test
    fun backendRejectsValuesOutsideDomain() {
        val text = parse("backend gpu").text()
        assertTrue(text.contains("无效的后端参数：gpu"))
        assertTrue(text.contains("auto、npu、hybrid、cpu"))
    }

    @Test
    fun backendRejectsExtraArguments() {
        assertTrue(parse("backend npu cpu").text().contains("只接受一个参数"))
    }

    // ---- storage grant ----

    @Test
    fun storageGrantRequestsSystemDirectoryPicker() {
        val result = parse("storage grant")
        assertEquals(listOf(ShellEffect.GrantStorage), result.effects)
        assertTrue(result.text().contains("目录选择器"))
        assertTrue(result.text().contains("授权"))
    }

    @Test
    fun storageRejectsUnknownOrMissingSubcommand() {
        val usage = "用法：storage grant"
        assertTrue(parse("storage").text().contains(usage))
        assertTrue(parse("storage list").text().contains(usage))
    }

    // ---- clear / status ----

    @Test
    fun clearRequestsScreenClear() {
        val result = parse("clear")
        assertEquals(listOf(ShellEffect.ClearScreen), result.effects)
        assertTrue(result.text().contains("已清空当前会话区"))
    }

    @Test
    fun clearRejectsArguments() {
        assertTrue(parse("clear all").text().contains("不接受参数"))
    }

    @Test
    fun statusReportsStageModelAndBackend() {
        val text = parse("/status").text()
        assertTrue(text.contains("阶段：Shell 顶层态"))
        assertTrue(text.contains("模型：Supra2.5-Pro-Instruct(Q4_0)"))
        assertTrue(text.contains("实际命中：NPU"))
    }

    @Test
    fun statusInSessionReportsMode() {
        val text = parse("/status", session).text()
        assertTrue(text.contains("阶段：会话态（CHAT）"))
    }

    // ---- 顶层非法输入 ----

    @Test
    fun unknownShellCommandSuggestsHelp() {
        val text = parse("foo").text()
        assertTrue(text.contains("未知命令：foo"))
        assertTrue(text.contains("输入 help 查看可用命令"))
    }

    @Test
    fun sessionCommandsAreRejectedInShellState() {
        val text = parse("/tree").text()
        assertTrue(text.contains("未知命令：/tree"))
        assertTrue(text.contains("chat / agent"))
    }

    @Test
    fun exitIsRejectedInShellState() {
        val text = parse("/exit").text()
        assertTrue(text.contains("当前已在 Shell 顶层态"))
        assertEquals(ShellStage.SHELL, parse("/exit").stage)
    }

    // ---- 会话态 ----

    @Test
    fun plainTextInSessionBecomesUserPrompt() {
        val result = parse("你好，骁龙", session)
        assertEquals(ShellStage.SESSION, result.stage)
        assertEquals(listOf(ShellEffect.ForwardPrompt("你好，骁龙")), result.effects)
        assertTrue(result.output.isEmpty())
    }

    @Test
    fun promptIsTrimmedBeforeForwarding() {
        assertEquals(listOf(ShellEffect.ForwardPrompt("你好")), parse("  你好  ", session).effects)
    }

    @Test
    fun promptKeepsInnerSpacingAndPunctuation() {
        val input = "解释一下 /tree 和 /fork 的区别"
        assertEquals(listOf(ShellEffect.ForwardPrompt(input)), parse(input, agentSession).effects)
    }

    @Test
    fun exitReturnsToShellStage() {
        val result = parse("/exit", session)
        assertEquals(ShellStage.SHELL, result.stage)
        assertTrue(result.effects.isEmpty())
        assertTrue(result.text().contains("返回 Shell 顶层态"))
    }

    @Test
    fun exitRejectsArguments() {
        assertTrue(parse("/exit now", session).text().contains("不接受参数"))
    }

    @Test
    fun newSessionEmitsNewSessionEffect() {
        val result = parse("/new", session)
        assertEquals(ShellStage.SESSION, result.stage)
        assertEquals(listOf(ShellEffect.NewSession), result.effects)
        assertTrue(result.output.isEmpty())
    }

    @Test
    fun newSessionRejectsArgumentsAndShellState() {
        assertTrue(parse("/new now", session).text().contains("不接受参数"))
        assertTrue(parse("/new").text().contains("未知命令：/new"))
    }

    @Test
    fun treeEmitsQueryEffect() {
        val result = parse("/tree", session)
        assertEquals(listOf(ShellEffect.QueryTree), result.effects)
        assertTrue(result.output.isEmpty())
    }

    @Test
    fun treeRejectsArguments() {
        assertTrue(parse("/tree 1", session).text().contains("不接受参数"))
    }

    @Test
    fun forkWithoutNodeTargetsActiveLeaf() {
        val result = parse("/fork", session)
        assertEquals(listOf(ShellEffect.ForkNode(null)), result.effects)
        assertTrue(result.output.isEmpty())
    }

    @Test
    fun forkWithNodeCarriesNodeId() {
        val result = parse("/fork node-42", session)
        assertEquals(listOf(ShellEffect.ForkNode("node-42")), result.effects)
    }

    @Test
    fun forkRejectsExtraArguments() {
        assertTrue(parse("/fork a b", session).text().contains("最多接受一个节点 ID"))
    }

    @Test
    fun compactEmitsCompactEffect() {
        val result = parse("/compact", session)
        assertEquals(listOf(ShellEffect.CompactContext), result.effects)
        assertTrue(result.output.isEmpty())
    }

    @Test
    fun steerRequiresInstruction() {
        assertTrue(parse("/steer", session).text().contains("需要附带干预指令"))
    }

    @Test
    fun steerEmitsInstructionEffect() {
        val result = parse("/steer 改成中文回答", session)
        assertEquals(listOf(ShellEffect.SteerInstruction("改成中文回答")), result.effects)
        assertTrue(result.text().contains("改成中文回答"))
        assertTrue(result.text().contains("工具步结束"))
    }

    @Test
    fun skillsEmitsQueryEffect() {
        val result = parse("/skills", session)
        assertEquals(listOf(ShellEffect.QuerySkills), result.effects)
        assertTrue(result.output.isEmpty())
    }

    @Test
    fun exportWithoutPathUsesDefaultTarget() {
        val result = parse("/export", session)
        assertEquals(listOf(ShellEffect.ExportTrace(null)), result.effects)
        assertTrue(result.output.isEmpty())
    }

    @Test
    fun exportCarriesCustomPathAndRejectsExtraArguments() {
        assertEquals(
            listOf(ShellEffect.ExportTrace("exports/a.jsonl")),
            parse("/export exports/a.jsonl", session).effects,
        )
        assertTrue(parse("/export a b", session).text().contains("用法：/export [路径]"))
    }

    @Test
    fun exportRequiresSessionState() {
        val result = parse("/export", shell)
        assertTrue(result.effects.isEmpty())
        assertTrue(result.text().contains("未知命令：/export"))
        assertTrue(result.text().contains("chat / agent"))
    }

    // ---- #94 /logs 应用内日志导出 ----

    @Test
    fun logsWithoutCountDefersToRuntimeDefault() {
        // 缺省行数交给运行链（默认 200 行），解析层不猜具体数字。
        assertEquals(listOf(ShellEffect.ExportLogs(null)), parse("/logs", session).effects)
        assertEquals(listOf(ShellEffect.ExportLogs(null)), parse("/logs", shell).effects)
    }

    @Test
    fun logsWithCountCarriesLineLimit() {
        assertEquals(listOf(ShellEffect.ExportLogs(200)), parse("/logs 200", session).effects)
        assertEquals(listOf(ShellEffect.ExportLogs(1)), parse("/logs 1", shell).effects)
        assertEquals(listOf(ShellEffect.ExportLogs(APP_LOG_MAX_LINES)), parse("/logs 500", session).effects)
    }

    @Test
    fun logsRejectsInvalidLineCount() {
        listOf("/logs 0", "/logs 501", "/logs abc", "/logs -3").forEach { input ->
            val result = parse(input, session)
            assertTrue("$input 应被拒绝", result.effects.isEmpty())
            assertTrue(result.text().contains("行数无效"))
        }
        assertTrue(parse("/logs 1 2", session).text().contains("用法：/logs [行数]"))
    }

    @Test
    fun statusKeepsSnapshotTextAndEmitsExtraQuery() {
        val result = parse("/status", session)
        assertEquals(listOf(ShellEffect.QueryStatusExtra), result.effects)
        assertTrue(result.text().contains("请求后端：auto"))
    }

    @Test
    fun unknownSlashCommandInSessionIsRejectedWithoutForwarding() {
        val result = parse("/foo", session)
        assertEquals(ShellStage.SESSION, result.stage)
        assertTrue(result.effects.isEmpty())
        assertTrue(result.text().contains("未知会话命令：/foo"))
    }

    // ---- #106 /ctx 上下文长度 ----

    @Test
    fun ctxWithoutArgumentQueriesInBothStages() {
        val sessionResult = parse("/ctx", session)
        assertEquals(listOf(ShellEffect.QueryContextLength), sessionResult.effects)
        assertTrue(sessionResult.output.isEmpty())
        assertEquals(listOf(ShellEffect.QueryContextLength), parse("/ctx", shell).effects)
    }

    @Test
    fun ctxAcceptsFreeValuesInBothStages() {
        assertEquals(listOf(ShellEffect.SetContextLength(1024)), parse("/ctx 1024", session).effects)
        assertEquals(listOf(ShellEffect.SetContextLength(3072)), parse("/ctx 3072", session).effects)
        assertEquals(listOf(ShellEffect.SetContextLength(8192)), parse("/ctx 8192", shell).effects)
        assertEquals(listOf(ShellEffect.SetContextLength(1048576)), parse("/ctx 1048576", session).effects)
    }

    @Test
    fun ctxAcceptsSuffixUnits() {
        assertEquals(listOf(ShellEffect.SetContextLength(20480)), parse("/ctx 20k", session).effects)
        assertEquals(listOf(ShellEffect.SetContextLength(20480)), parse("/ctx 20K", session).effects)
        assertEquals(listOf(ShellEffect.SetContextLength(32768)), parse("/ctx 32k", session).effects)
        assertEquals(listOf(ShellEffect.SetContextLength(65536)), parse("/ctx 64k", session).effects)
        assertEquals(listOf(ShellEffect.SetContextLength(1048576)), parse("/ctx 1m", session).effects)
        // 注意：shell 按空白切分参数，"20 k" 会被当成两个参数而报用法（空格容错只在设置页输入框生效）。
    }

    @Test
    fun ctxRejectsInvalidValues() {
        listOf("/ctx 0", "/ctx -1", "/ctx abc", "/ctx 1g", "/ctx 1023", "/ctx 1048577", "/ctx 1m1")
            .forEach { input ->
                val result = parse(input, session)
                assertTrue("$input 应被拒绝", result.effects.isEmpty())
                assertTrue(result.text().contains("上下文长度无效"))
                assertTrue(result.text().contains("1024、8192、20k、64k、1m"))
            }
    }

    @Test
    fun ctxRejectsExtraArguments() {
        assertTrue(parse("/ctx 4096 8192", session).text().contains("用法：/ctx [token]"))
    }

    @Test
    fun ctxIsListedInUnknownSessionCommandHint() {
        val text = parse("/foo", session).text()
        assertTrue(text.contains("/ctx"))
    }

    // ---- #105 /system 系统提示词 ----

    @Test
    fun systemWithoutArgumentQueriesInBothStages() {
        val sessionResult = parse("/system", session)
        assertEquals(listOf(ShellEffect.QuerySystemPrompt), sessionResult.effects)
        assertTrue(sessionResult.output.isEmpty())
        assertEquals(listOf(ShellEffect.QuerySystemPrompt), parse("/system", shell).effects)
    }

    @Test
    fun systemWithTextReplacesWholePromptAndKeepsSpaces() {
        assertEquals(
            listOf(ShellEffect.SetSystemPrompt("你是简洁助手，只回答中文")),
            parse("/system 你是简洁助手，只回答中文", session).effects,
        )
        assertEquals(
            listOf(ShellEffect.SetSystemPrompt("两段文本 保留中间空格")),
            parse("/system 两段文本 保留中间空格", session).effects,
        )
    }

    @Test
    fun systemResetCarriesNullToRestoreDefault() {
        assertEquals(listOf(ShellEffect.SetSystemPrompt(null)), parse("/system reset", session).effects)
        assertEquals(listOf(ShellEffect.SetSystemPrompt(null)), parse("/system RESET", session).effects)
    }

    @Test
    fun systemResetFollowedByTextIsTreatedAsLiteralPrompt() {
        assertEquals(
            listOf(ShellEffect.SetSystemPrompt("reset 之后还有文本")),
            parse("/system reset 之后还有文本", session).effects,
        )
    }

    @Test
    fun systemIsListedInUnknownSessionCommandHint() {
        assertTrue(parse("/foo", session).text().contains("/system"))
    }

    // ---- #126 /workspace 工作区 ----

    private val workspaces = listOf(
        WorkspaceEntry(
            index = 0,
            id = "default",
            name = "默认工作区",
            source = com.thatgfsj.qaa.core.harness.WorkspaceSource.INTERNAL,
            rootDisplay = "files",
            sessionCount = 1,
            writable = true,
            current = true,
        ),
        WorkspaceEntry(
            index = 1,
            id = "ws-1",
            name = "资料库",
            source = com.thatgfsj.qaa.core.harness.WorkspaceSource.INTERNAL,
            rootDisplay = "files/workspaces/ws-1",
            sessionCount = 2,
            writable = true,
            current = false,
        ),
        WorkspaceEntry(
            index = 2,
            id = "ws-2",
            name = "手机目录",
            source = com.thatgfsj.qaa.core.harness.WorkspaceSource.SAF,
            rootDisplay = "手机目录（SAF）",
            sessionCount = 0,
            writable = false,
            current = false,
        ),
    )
    private val workspaceShell = shell.copy(workspaces = workspaces)

    @Test
    fun workspaceWithoutArgumentShowsCurrentOverviewInBothStages() {
        val result = parse("/workspace", workspaceShell)
        assertTrue(result.effects.isEmpty())
        assertTrue(result.text().contains("当前工作区：默认工作区（内置目录，default）"))
        assertTrue(result.text().contains("根目录：files"))
        assertTrue(result.text().contains("可写：是"))
        assertTrue(parse("/workspace", workspaceShell.copy(stage = ShellStage.SESSION)).text().contains("当前工作区：默认工作区"))
    }

    @Test
    fun workspaceListShowsAllEntriesAndCurrentMark() {
        val text = parse("/workspace list", workspaceShell).text()
        assertTrue(text.contains("工作区清单（共 3 个）"))
        assertTrue(text.contains("1. 资料库（内置目录，ws-1）"))
        assertTrue(text.contains("2. 手机目录（外部目录，ws-2）"))
        assertTrue(text.contains("0. 默认工作区（内置目录，default） · 当前"))
    }

    @Test
    fun workspaceNewEmitsCreateWithSpacedName() {
        assertEquals(
            listOf(ShellEffect.Workspace(WorkspaceRequest.Create("资料 库 A"))),
            parse("/workspace new 资料 库 A", workspaceShell).effects,
        )
        assertTrue(parse("/workspace new", workspaceShell).text().contains("用法：/workspace new <名称>"))
    }

    @Test
    fun workspaceUseIsRefusedInSessionStage() {
        val result = parse("/workspace use 1", workspaceShell.copy(stage = ShellStage.SESSION))
        assertTrue(result.effects.isEmpty())
        assertTrue(result.text().contains("请先 /exit"))
    }

    @Test
    fun workspaceUseResolvesIndexAndIdAndSkipsCurrent() {
        assertEquals(
            listOf(ShellEffect.Workspace(WorkspaceRequest.Use("ws-1"))),
            parse("/workspace use 1", workspaceShell).effects,
        )
        assertEquals(
            listOf(ShellEffect.Workspace(WorkspaceRequest.Use("ws-2"))),
            parse("/workspace use ws-2", workspaceShell).effects,
        )
        assertTrue(parse("/workspace use 0", workspaceShell).effects.isEmpty())
        assertTrue(parse("/workspace use 0", workspaceShell).text().contains("当前已是工作区"))
        assertTrue(parse("/workspace use 9", workspaceShell).text().contains("未找到工作区：9"))
    }

    @Test
    fun workspaceRenameRequiresTargetAndNewName() {
        assertEquals(
            listOf(ShellEffect.Workspace(WorkspaceRequest.Rename("ws-1", "资料 库"))),
            parse("/workspace rename ws-1 资料 库", workspaceShell).effects,
        )
        assertTrue(parse("/workspace rename ws-1", workspaceShell).text().contains("用法：/workspace rename"))
    }

    @Test
    fun workspaceRemoveRefusesCurrentWorkspace() {
        val result = parse("/workspace rm 0", workspaceShell)
        assertTrue(result.effects.isEmpty())
        assertTrue(result.text().contains("当前工作区不可删除"))
    }

    @Test
    fun workspaceRemoveWithSessionsNeedsForceConfirmation() {
        val preview = parse("/workspace rm 1", workspaceShell)
        assertTrue(preview.effects.isEmpty())
        assertTrue(preview.text().contains("会话：2 个"))
        assertTrue(preview.text().contains("确认请再次执行：/workspace rm ws-1 --force"))

        assertEquals(
            listOf(ShellEffect.Workspace(WorkspaceRequest.Remove("ws-1", true))),
            parse("/workspace rm ws-1 --force", workspaceShell).effects,
        )
    }

    @Test
    fun workspaceRemoveWithoutSessionsNeedsNoForce() {
        assertEquals(
            listOf(ShellEffect.Workspace(WorkspaceRequest.Remove("ws-2", false))),
            parse("/workspace rm 2", workspaceShell).effects,
        )
    }

    @Test
    fun workspaceRejectsUnknownOptionAndSubcommand() {
        assertTrue(parse("/workspace rm 2 --all", workspaceShell).text().contains("只支持 --force"))
        assertTrue(parse("/workspace switch 1", workspaceShell).text().contains("未知 /workspace 子命令"))
    }

    @Test
    fun workspaceOverviewWithoutSnapshotReportsUnavailable() {
        assertTrue(parse("/workspace").text().contains("当前工作区不可用"))
    }

    @Test
    fun helpListsWorkspaceCommand() {
        assertTrue(parse("help").text().contains("/workspace"))
    }
}
