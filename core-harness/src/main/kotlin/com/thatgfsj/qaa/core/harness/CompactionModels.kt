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
        /** 压缩后保留的历史节点条数（不含摘要段）。 */
        val retainedNodeCount: Int = 0,
        /** 本次新写摘要的字符总数。 */
        val summaryChars: Int = 0,
        /** 本次新摘要吸收的既有摘要节点数（替换而非叠加）。 */
        val replacedSummaryCount: Int = 0,
    ) : CompactionResult {
        /** 本次压缩追加的最后一个 summary 节点 id。 */
        val summaryNodeId: String get() = ranges.last().summaryNodeId

        /** 本次压缩省下的 token 数（before/after 同口径估算，恒为非负）。 */
        val savedTokens: Int get() = (estimatedTokensBefore - estimatedTokensAfter).coerceAtLeast(0)
    }

    /**
     * 未触发、无需压缩或压缩无收益。
     *
     * [currentTokens]/[contextBudget]/[targetTokens] 用于回执说明“当前多少、目标多少”，
     * 未知时为 0（不改变既有等值语义）。
     */
    data class Skipped(
        val reason: SkipReason,
        val currentTokens: Int = 0,
        val contextBudget: Int = 0,
        val targetTokens: Int = 0,
    ) : CompactionResult

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

    /** 手动压缩时占用已不高于目标水位：再压一次只会增加摘要开销，故不改树。 */
    ALREADY_BELOW_TARGET,

    /** 有可压缩节点，但摘要开销不低于被覆盖内容：压缩无收益，不改树。 */
    NOT_WORTHWHILE,
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
