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
    HelpRow("model delete <序号>", "顶层", "删除模型（需 --force 确认）"),
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
    HelpRow("/export [路径]", "会话", "导出当前分支轨迹"),
    HelpRow("/logs [行数]", "全部", "导出应用内日志（默认 200 行）"),
    HelpRow("/ctx [token]", "全部", "查看 / 设置上下文长度"),
    HelpRow("/system [文本|reset]", "全部", "查看 / 设置系统提示词"),
    HelpRow("/workspace", "全部", "工作区概览 / 管理（list/new/use/rename/rm）"),
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
    appendLine("· model delete <序号|名称> 先回显删除影响，再次带 --force 才真正删除；内置 0 号与当前加载模型不可删。")
    appendLine("· chat / agent 支持模型名或序号（内置模型为 0 号，下载 / 导入从 1 起）；输入 model 查看本地模型清单。")
    appendLine("· backend 可选值：auto、npu、hybrid、cpu；无参数查看请求与实际命中。")
    appendLine("· /new 新建空会话并清屏，旧会话记录保留；/status 可查看当前会话 id。")
    appendLine("· /ctx 长度：建议 4096 / 8192 / 16384 / 32768，亦可自定义 1024~1048576（支持 20k / 64k / 1m）。")
    appendLine("· ≤8192 且模型已加载时 /ctx 自动重载生效，更大值需重新载入模型；长上下文可能因内存溢出闪退（进程级崩溃），建议先用小长度验证。")
    appendLine("· /system 无参查看系统提示词全文与来源；带文本整体替换，/system reset 恢复默认；提示词每轮组装，下一轮生效。")
    appendLine("· /workspace 无参查看概览；list 列出全部；new <名称> 新建内置工作区；use <序号|id> 切换；rename <序号|id> <新名>；rm <序号|id> [--force]。")
    append("· 轮次运行中可输入 /steer 打断，或直接输入文字进 Follow-up 队列。")
}

/** 模型体积文案（一位小数 MB；未知时显示「未知」），供 model delete 二次确认回显。 */
internal fun modelSizeText(bytes: Long?): String =
    if (bytes == null) "未知" else "%.1f MB".format(java.util.Locale.ROOT, bytes / 1024f / 1024f)

/** 本地模型清单：内置模型 0 号、下载 / 导入从 1 起，供 chat / agent 选型。 */
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

/**
 * 工作区清单文案（/workspace list）：序号、名称、来源、会话数、当前标记。
 * 空清单只可能是快照尚未接线，给出可操作提示。
 */
internal fun workspaceListText(entries: List<WorkspaceEntry>): String =
    if (entries.isEmpty()) {
        "工作区清单为空：请执行 /workspace new <名称> 新建内置工作区。"
    } else {
        buildString {
            appendLine("工作区清单（共 ${entries.size} 个）：")
            entries.forEach { entry ->
                appendLine(
                    "  ${entry.index}. ${entry.displayText} · ${entry.sourceLabel} · 根：${entry.rootDisplay} · 会话 ${entry.sessionCount} 个",
                )
            }
            append("使用 /workspace use <序号|id> 切换。")
        }
    }

/** 工作区概览文案（/workspace 无参）：名称 / 来源 / 根 / 可写 / 会话数。 */
internal fun workspaceOverviewText(entries: List<WorkspaceEntry>): String {
    val current = entries.firstOrNull { it.current }
        ?: return "当前工作区不可用：未读到工作区清单。执行 /workspace list 查看全部工作区。"
    return buildString {
        appendLine("当前工作区：${current.name}（${current.sourceLabel}，${current.id}）")
        appendLine("根目录：${current.rootDisplay}")
        appendLine("可写：${if (current.writable) "是" else "否（只读授权）"} · 会话：${current.sessionCount} 个")
        append("使用 /workspace list 查看全部工作区，/workspace use <序号|id> 切换。")
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
