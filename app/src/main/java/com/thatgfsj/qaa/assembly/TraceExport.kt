package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.SessionNode
import com.thatgfsj.qaa.core.harness.SessionRole

/** /export 默认落点（沙盒相对路径）：exports/session-<会话id>.jsonl。 */
internal const val TRACE_EXPORT_DIR = "exports"
private const val TRACE_EXPORT_EXTENSION = ".jsonl"

/**
 * 解析导出目标：缺省为当前会话的默认文件；用户路径原样交给沙盒校验，
 * 未带 .jsonl 后缀时补齐（绝对路径 / `..` 由沙盒拒绝并回显中文原因）。
 */
internal fun traceExportPath(sessionId: String, requested: String?): String {
    val target = requested?.trim()?.takeIf { it.isNotEmpty() }
        ?: TRACE_EXPORT_DIR + "/session-" + sessionId + TRACE_EXPORT_EXTENSION
    return if (target.endsWith(TRACE_EXPORT_EXTENSION, ignoreCase = true)) {
        target
    } else {
        target + TRACE_EXPORT_EXTENSION
    }
}

/** 分支统计：导出时被剔除或旁注的节点规模（回显「行数 / 剔除统计」）。 */
internal data class TraceBranchStats(
    val nodes: Int,
    val summaries: Int,
    val parseRetries: Int,
    val toolResults: Int,
)

internal fun traceBranchStats(branch: List<SessionNode>): TraceBranchStats = TraceBranchStats(
    nodes = branch.size,
    summaries = branch.count { it.role == SessionRole.SUMMARY },
    parseRetries = branch.count { it.role == SessionRole.ASSISTANT && it.parseRetries > 0 },
    toolResults = branch.count { it.role == SessionRole.TOOL },
)

/**
 * 导出中文回执：成功给行数与剔除统计；不可用分支说明未写出文件的原因范围
 * （空分支 / 未完成工具调用 / 失败或拒绝步骤），不虚构导出结果。
 */
internal fun describeTraceExport(path: String, stats: TraceBranchStats, lines: Int): String =
    if (lines > 0) {
        "已导出当前分支轨迹：$path（$lines 行；分支 ${stats.nodes} 节点，" +
            "剔除 摘要 ${stats.summaries} 段 / 解析重试 ${stats.parseRetries} 节点，" +
            "工具结果 ${stats.toolResults} 条）。"
    } else {
        "当前分支暂不可用于训练：分支为空、存在未完成工具调用或失败步骤" +
            "（分支 ${stats.nodes} 节点，摘要 ${stats.summaries} 段 / 解析重试 ${stats.parseRetries} 节点）；" +
            "未写出文件。"
    }
