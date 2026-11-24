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
}
