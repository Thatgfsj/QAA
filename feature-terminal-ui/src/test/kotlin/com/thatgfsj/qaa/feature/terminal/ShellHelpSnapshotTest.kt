package com.thatgfsj.qaa.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 全中文 help 文案快照（Issue #10 验收）：命令表逐字符锁定，防中英文混排对齐回退。 */
class ShellHelpSnapshotTest {

    @Test
    fun helpTextMatchesSnapshot() {
        assertEquals(EXPECTED_HELP, shellHelpText())
    }

    @Test
    fun helpTextListsEveryPhaseOneCommand() {
        val commands = listOf(
            "help / /help",
            "model",
            "model pull <url>",
            "model import",
            "chat <模型名|序号>",
            "agent [模型名|序号]",
            "start window",
            "backend [值]",
            "storage grant",
            "clear",
            "/status",
            "/exit",
            "/new",
            "/tree",
            "/fork [节点ID]",
            "/compact",
            "/steer <指令>",
            "/skills",
            "/export [路径]",
            "/logs [行数]",
            "/ctx [档位]",
            "/system [文本|reset]",
        )
        val text = shellHelpText()
        commands.forEach { command -> assertTrue("缺少命令：$command", text.contains(command)) }
        assertEquals(22, rowsOf(text).size)
    }

    @Test
    fun helpTextStatesSessionAndShellScope() {
        val text = shellHelpText()
        assertTrue(text.contains("会话态下直接输入文字即为用户 prompt"))
        assertTrue(text.contains("命令") && text.contains("适用状态") && text.contains("说明"))
    }

    @Test
    fun padDisplayWidthCountsChineseAsTwoColumns() {
        assertEquals("模型  ", padDisplayWidth("模型", 6))
        assertEquals("help  ", padDisplayWidth("help", 6))
        assertEquals("超长文本", padDisplayWidth("超长文本", 2))
    }

    /** 命令表行：状态列两侧为双空格，据此排除标题、表头、分隔线与提示行。 */
    private fun rowsOf(text: String): List<String> = text.lines().filter { row ->
        row.contains("  顶层  ") || row.contains("  会话  ") || row.contains("  全部  ")
    }

    private companion object {
        val EXPECTED_HELP = """
    QAA 终端命令帮助（全中文）

    命令                  适用状态  说明
    ------------------    --------  ----------
    help / /help          全部      显示本帮助
    model                 顶层      列出本地模型
    model pull <url>      顶层      下载模型（断点续传）
    model import          顶层      导入模型（系统文件选择器）
    chat <模型名|序号>    顶层      进入 CHAT 会话
    agent [模型名|序号]   顶层      进入 AGENT 会话
    start window          顶层      切回正常窗口并重启
    backend [值]          顶层      查看 / 锁定请求后端
    storage grant         顶层      授权外部存储目录（SAF）
    clear                 顶层      清空当前会话区
    /status               全部      查看阶段与运行状态
    /exit                 会话      退出会话回顶层态
    /new                  会话      新建会话（清屏）
    /tree                 会话      查看会话树
    /fork [节点ID]        会话      以节点为分叉点
    /compact              会话      压缩上下文
    /steer <指令>         会话      运行中干预
    /skills               会话      查看可用技能
    /export [路径]        会话      导出当前分支轨迹
    /logs [行数]          全部      导出应用内日志（默认 200 行）
    /ctx [档位]           全部      查看 / 设置上下文长度
    /system [文本|reset]  全部      查看 / 设置系统提示词

    提示：
    · 会话态下直接输入文字即为用户 prompt；输入 /exit 返回 Shell 顶层态。
    · chat / agent 支持模型名或序号（内置模型为 0 号，下载 / 导入从 1 起）；输入 model 查看本地模型清单。
    · backend 可选值：auto、npu、hybrid、cpu；无参数查看请求与实际命中。
    · /new 新建空会话并清屏，旧会话记录保留；/status 可查看当前会话 id。
    · /ctx 档位：4096 / 8192 / 16384 / 32768；≤8192 且模型已加载时自动重载生效，更大档位需重新载入模型。
    · /system 无参查看系统提示词全文与来源；带文本整体替换，/system reset 恢复默认；提示词每轮组装，下一轮生效。
    · 长上下文（≥16384）在 HTP/NPU 上可能触发 abort，建议先用小档位验证。
    · 轮次运行中可输入 /steer 打断，或直接输入文字进 Follow-up 队列。
        """.trimIndent().trimEnd()
    }
}
