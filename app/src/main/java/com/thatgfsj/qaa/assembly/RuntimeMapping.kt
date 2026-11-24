package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.CompactionResult
import com.thatgfsj.qaa.core.harness.SkipReason
import com.thatgfsj.qaa.core.inference.CatalogEntry
import com.thatgfsj.qaa.core.inference.ComputeBackend
import com.thatgfsj.qaa.core.inference.ModelPullEvent
import com.thatgfsj.qaa.feature.terminal.BackendRequest
import com.thatgfsj.qaa.feature.terminal.LocalModelEntry
import com.thatgfsj.qaa.feature.terminal.TerminalPullEvent

/**
 * 装配层纯映射与回显文案（[AgentRuntime] 端口实现的投影函数）。
 *
 * 独立成文件避免 AgentRuntime 越界 1000 行，同时便于 JVM 单测（RuntimeMappingTest）；
 * 全部为纯函数，不持有运行时状态。
 */

/** 模型清单条目 → 终端展示条目（序号与量化对齐 UI 模型）。 */
internal fun catalogToLocalEntries(entries: List<CatalogEntry>): List<LocalModelEntry> =
    entries.map { LocalModelEntry(index = it.ordinal, name = it.name, quantization = it.quantization ?: "未知") }

/** 拉取事件 → 终端投影。 */
internal fun ModelPullEvent.toTerminalEvent(): TerminalPullEvent = when (this) {
    is ModelPullEvent.Progress -> TerminalPullEvent.Progress(bytes, totalBytes, percent)
    is ModelPullEvent.Completed -> TerminalPullEvent.Completed(modelName, resolvedPath)
    is ModelPullEvent.Failed -> TerminalPullEvent.Failed(code, message, resumable)
}

/** 请求后端 → 引擎后端（AUTO 交给降级链）。 */
internal fun BackendRequest.toComputeBackend(): ComputeBackend = when (this) {
    BackendRequest.AUTO -> ComputeBackend.AUTO
    BackendRequest.NPU -> ComputeBackend.NPU
    BackendRequest.HYBRID -> ComputeBackend.HYBRID
    BackendRequest.CPU -> ComputeBackend.CPU
}

/** /status 会话段：当前会话 id、节点规模、活动分支与摘要段数。 */
internal fun sessionStatusText(
    sessionId: String,
    nodeCount: Int,
    branchNodes: Int,
    summaries: Int,
): String = "会话：$sessionId，节点 $nodeCount，活动分支 $branchNodes 节点，摘要 $summaries 段"

/** 压缩结果 → 中文回显。 */
internal fun describeCompaction(result: CompactionResult): String = when (result) {
    is CompactionResult.Compacted -> "已压缩上下文：${result.ranges.size} 段，约 " +
        "${result.estimatedTokensBefore} → ${result.estimatedTokensAfter} token。"
    is CompactionResult.Skipped -> when (result.reason) {
        SkipReason.BELOW_THRESHOLD -> "上下文占用未达压缩阈值，本次无需压缩。"
        SkipReason.NOTHING_TO_COMPACT -> "保护窗口之外没有可压缩节点。"
    }
    CompactionResult.DeferredUntilSafePoint -> "轮次进行中：压缩已排队到最近安全点。"
    is CompactionResult.Failed -> "压缩失败：" + result.reason
}
