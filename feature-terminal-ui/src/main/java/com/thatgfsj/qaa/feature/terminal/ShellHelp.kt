package com.thatgfsj.qaa.feature.terminal

/**
 * 全中文 help 表格与命令回显文案（Issue #10）。
 *
 * 表格按显示宽度对齐：中文按 2 列、ASCII 按 1 列，保证等宽字体下三列不歪斜。
 */

private const val COMMAND_COLUMN_WIDTH = 20
private const val SCOPE_COLUMN_WIDTH = 8
private const val COLUMN_GUTTER = "  "

/** CJK 起始码位：不小于该值的字符按两列计算。 */
private const val CJK_START_CODE = 0x2E80

private data class HelpRow(val command: String, val scope: String, val description: String)

private val HELP_ROWS = listOf(
    HelpRow("help / /help", "全部", "显示本帮助"),
    HelpRow("model", "顶层", "列出本地模型"),
    HelpRow("model pull <url>", "顶层", "下载模型（断点续传）"),
    HelpRow("model import", "顶层", "导入模型（系统文件选择器）"),
    HelpRow("chat <模型名|序号>", "顶层", "进入 CHAT 会话"),
    HelpRow("agent [模型名|序号]", "顶层", "进入 AGENT 会话"),
    HelpRow("start window", "顶层", "切回正常窗口并重启"),
    HelpRow("backend [值]", "顶层", "查看 / 锁定请求后端"),
    HelpRow("storage grant", "顶层", "授权外部存储目录（SAF）"),
    HelpRow("clear", "顶层", "清空当前会话区"),
    HelpRow("/status", "全部", "查看阶段与运行状态"),
    HelpRow("/exit", "会话", "退出会话回顶层态"),
    HelpRow("/new", "会话", "新建会话（清屏）"),
    HelpRow("/tree", "会话", "查看会话树"),
    HelpRow("/fork [节点ID]", "会话", "以节点为分叉点"),
    HelpRow("/compact", "会话", "压缩上下文"),
    HelpRow("/steer <指令>", "会话", "运行中干预"),
    HelpRow("/skills", "会话", "查看可用技能"),
)

/** 全中文命令表（命令、适用状态、说明三列）与输入约定提示。 */
internal fun shellHelpText(): String = buildString {
    appendLine("QAA 终端命令帮助（全中文）")
    appendLine()
    appendLine(helpLine("命令", "适用状态", "说明"))
    appendLine(helpLine("-".repeat(18), "--------", "----------"))
    HELP_ROWS.forEach { appendLine(helpLine(it.command, it.scope, it.description)) }
    appendLine()
    appendLine("提示：")
    appendLine("· 会话态下直接输入文字即为用户 prompt；输入 /exit 返回 Shell 顶层态。")
    appendLine("· chat / agent 支持模型名或从 1 起的序号；输入 model 查看本地模型清单。")
    appendLine("· backend 可选值：auto、npu、hybrid、cpu；无参数查看请求与实际命中。")
    appendLine("· /new 新建空会话并清屏，旧会话记录保留；/status 可查看当前会话 id。")
    append("· 轮次运行中可输入 /steer 打断，或直接输入文字进 Follow-up 队列。")
}

/** 本地模型清单：序号从 1 起，供 chat / agent 选型。 */
internal fun localModelListText(models: List<LocalModelEntry>, current: TerminalModelInfo): String =
    buildString {
        if (models.isEmpty()) {
            append("本地暂无模型，可执行 model pull <url> 下载，或用 model import 旁加载。")
        } else {
            appendLine("本地模型清单：")
            models.forEach { appendLine("  ${it.index}. ${it.displayText}") }
            append("当前模型：${current.displayText}。使用 chat <模型名|序号> 或 agent [模型名|序号] 进入会话。")
        }
    }

/** /status 输出：阶段、模型、请求 / 实际后端与统计栏快照。 */
internal fun shellStatusText(context: ShellContext): String = buildString {
    appendLine("运行状态")
    append("阶段：${context.stage.displayName}")
    if (context.stage == ShellStage.SESSION) append("（${context.mode.displayTag}）")
    appendLine()
    appendLine("模型：${context.modelInfo.displayText}")
    appendLine("请求后端：${context.requestedBackend.displayName}")
    appendLine("实际命中：${context.activeBackend.displayTag}")
    append("统计：${context.metrics.statsBarText()}")
}

/** 按显示宽度右补空格（中文 2 列、其余 1 列），宽度不足时原样返回。 */
internal fun padDisplayWidth(text: String, width: Int): String {
    val used = text.sumOf { if (it.code >= CJK_START_CODE) 2 else 1 }
    return text + " ".repeat((width - used).coerceAtLeast(0))
}

private fun helpLine(command: String, scope: String, description: String): String =
    padDisplayWidth(command, COMMAND_COLUMN_WIDTH) +
        COLUMN_GUTTER +
        padDisplayWidth(scope, SCOPE_COLUMN_WIDTH) +
        COLUMN_GUTTER +
        description
