package com.thatgfsj.qaa.core.harness

/** 压缩触发来源（设计文档 6.2）：安全点自动检查，或 /compact 手动入口。 */
enum class CompactionTrigger { AUTO, MANUAL }

/** 一次摘要节点回写的范围引用（与 [SessionNode.compaction] 对应）。 */
data class CompactedRange(
    val summaryNodeId: String,
    val coveredFrom: String,
    val coveredTo: String,
    val nodeCount: Int,
    val estimatedTokens: Long,
)

/** [ContextCompactor.compact] 的结果。 */
sealed interface CompactionResult {

    /** 已追加一组 summary 节点；[ranges] 按追加顺序排列。 */
    data class Compacted(
        val ranges: List<CompactedRange>,
        val trigger: CompactionTrigger,
        val estimatedTokensBefore: Int,
        val estimatedTokensAfter: Int,
        val postWatermarkRatio: Float,
        val watermarkSatisfied: Boolean,
    ) : CompactionResult {
        /** 本次压缩追加的最后一个 summary 节点 id。 */
        val summaryNodeId: String get() = ranges.last().summaryNodeId
    }

    /** 未触发或无需压缩。 */
    data class Skipped(val reason: SkipReason) : CompactionResult

    /** 运行中收到 /compact：装配层应在最近安全点重试，树未改动。 */
    data object DeferredUntilSafePoint : CompactionResult

    /** 摘要生成失败：树未改动，由装配层决定重试或告警。 */
    data class Failed(val reason: String) : CompactionResult
}

/** 未压缩原因。 */
enum class SkipReason {
    /** 自动检查时 usedTokens / contextBudget 低于阈值。 */
    BELOW_THRESHOLD,

    /** 保护窗口之外没有可压缩节点（会话过短或已全部压缩）。 */
    NOTHING_TO_COMPACT,
}

/**
 * 压缩后的提示视图：摘要段（设计文档 1.4 第 3 段）与保留历史（第 4 段）。
 *
 * [history] 已剔除被摘要范围覆盖的节点与 summary 节点本身；[summaries] 按活动
 * 分支的树序（parentId 链顺序）排列，可直接交给 [PromptAssembler.assemble]。
 */
data class CompactedView(
    val history: List<TurnNode>,
    val summaries: List<String>,
) {
    /** 按设计顺序组装提示：系统段 → skills 段 → 摘要段 → 会话历史。 */
    fun assemble(assembler: PromptAssembler, notice: String? = null): List<PromptMessage> =
        assembler.assemble(history, summaries, notice)

    /** 摘要段 + 历史段的提示消息（不含系统段与 skills 段）。 */
    fun messages(): List<PromptMessage> {
        val summaryMessages = summaries.map { PromptMessage(PromptRole.SYSTEM, it) }
        val historyMessages = history.map { PromptMessage(it.role.toPromptRole(), it.content) }
        return summaryMessages + historyMessages
    }

    /** 摘要段 + 历史段的 token 估算，用于压后水位校验。 */
    fun estimatedTokens(counter: TokenCounter): Int = counter.countMessages(messages())

    private fun TurnRole.toPromptRole(): PromptRole = when (this) {
        TurnRole.USER -> PromptRole.USER
        TurnRole.ASSISTANT -> PromptRole.ASSISTANT
        TurnRole.TOOL -> PromptRole.TOOL
    }
}
