package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.CompactionResult
import com.thatgfsj.qaa.core.harness.CompactionTrigger
import com.thatgfsj.qaa.core.harness.SkipReason
import com.thatgfsj.qaa.core.inference.CatalogEntry
import com.thatgfsj.qaa.core.inference.ComputeBackend
import com.thatgfsj.qaa.core.inference.ModelCompatibility
import com.thatgfsj.qaa.core.inference.ModelCompatibilityGate
import com.thatgfsj.qaa.core.inference.ModelPullEvent
import java.io.File
import com.thatgfsj.qaa.feature.terminal.BackendRequest
import com.thatgfsj.qaa.feature.terminal.LocalModelEntry
import com.thatgfsj.qaa.feature.terminal.TerminalPullEvent

/**
 * 装配层纯映射与回显文案（[AgentRuntime] 端口实现的投影函数）。
 *
 * 独立成文件避免 AgentRuntime 越界 1000 行，同时便于 JVM 单测（RuntimeMappingTest）；
 * 全部为纯函数，不持有运行时状态。
 */

/** 模型清单条目 → 终端展示条目（序号与量化对齐 UI 模型；#102 追加兼容性标注）。 */
internal fun catalogToLocalEntries(entries: List<CatalogEntry>): List<LocalModelEntry> =
    entries.map {
        LocalModelEntry(
            index = it.ordinal,
            name = it.name,
            quantization = it.quantization ?: "未知",
            sizeBytes = it.sizeBytes,
            path = it.path,
            note = compatibilityOf(it).displayNote,
        )
    }

/** 清单条目的兼容性结论：清单已评估则复用，否则按文件头补评（不可读 → 未知档位放行）。 */
internal fun compatibilityOf(entry: CatalogEntry): ModelCompatibility =
    entry.compatibility ?: ModelCompatibilityGate.assessFile(entry.path?.let(::File))

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

/** 保留一位小数的百分比文案（回执与 /status 共用，整数时省略小数位）。 */
internal fun formatPercent(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toInt().toDouble()) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}

/**
 * /status 与 /compact 共用的“当前上下文占用”文案（#100）：估算值与压缩回执的
 * before/after 同出 [com.thatgfsj.qaa.core.harness.ContextCompactor] 的估算，
 * 有实测 prompt token 时一并给出，便于对照估算与真实分词。
 */
internal fun describeContextUsage(
    estimatedTokens: Int,
    contextBudget: Int,
    lastPromptTokens: Int? = null,
): String {
    val budgetText = if (contextBudget > 0) {
        "，预算 $contextBudget（约 ${formatPercent(estimatedTokens * 100.0 / contextBudget)}%）"
    } else {
        ""
    }
    val measuredText = lastPromptTokens?.let { "；上次 prompt $it token" }.orEmpty()
    return "上下文估算：$estimatedTokens token$budgetText$measuredText"
}

/**
 * 压缩结果 → 中文回显（#100）：压缩前后 token、省量与百分比、触发原因、保留规模，
 * 数值与 /status 的“上下文估算”同一口径，便于用户核对压缩是否真的变小。
 */
internal fun describeCompaction(result: CompactionResult): String = when (result) {
    is CompactionResult.Compacted -> {
        val percent = if (result.estimatedTokensBefore > 0) {
            result.savedTokens * 100.0 / result.estimatedTokensBefore
        } else {
            0.0
        }
        val trigger = when (result.trigger) {
            CompactionTrigger.AUTO -> "自动（达阈值）"
            CompactionTrigger.MANUAL -> "手动 /compact"
        }
        "已压缩上下文：${result.ranges.size} 段，约 " +
            "${result.estimatedTokensBefore} → ${result.estimatedTokensAfter} token，" +
            "省 ${result.savedTokens}（${formatPercent(percent)}%）；触发：$trigger；" +
            "保留 ${result.retainedNodeCount} 条历史 + ${result.summaryChars} 字摘要。"
    }
    is CompactionResult.Skipped -> when (result.reason) {
        SkipReason.BELOW_THRESHOLD -> "上下文占用未达压缩阈值，本次无需压缩。"
        SkipReason.NOTHING_TO_COMPACT -> "保护窗口之外没有可压缩节点。"
        SkipReason.ALREADY_BELOW_TARGET -> "上下文估算约 ${result.currentTokens} token，" +
            "已不高于目标水位 ${result.targetTokens} token，本次无需压缩。"
        SkipReason.NOT_WORTHWHILE -> "可压缩内容不足以抵消摘要开销（当前约 " +
            "${result.currentTokens} token），未改动历史。"
    }
    CompactionResult.DeferredUntilSafePoint -> "轮次进行中：压缩已排队到最近安全点。"
    is CompactionResult.Failed -> "压缩失败：" + result.reason
}
