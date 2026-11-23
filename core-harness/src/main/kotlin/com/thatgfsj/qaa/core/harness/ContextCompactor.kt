package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.CancellationException

/**
 * 上下文压缩器（设计文档第 6 章）：按 token 占用率判定是否压缩，把保护窗口之外的
 * 早期历史交给当前 LLM 生成固定模板摘要，并以 summary 节点追加回 [TreeSessionStore]。
 *
 * 时机由装配层掌握：自动压缩只在工具步边界或轮末安全点调用 [compact]；流式生成中
 * 与确认挂起中不得调用。手动 /compact 入口调用 [compactManually]，运行中返回
 * [CompactionResult.DeferredUntilSafePoint]，由装配层排队到最近安全点。
 *
 * 摘要范围用链段端点引用（coveredFrom/coveredTo）：从 coveredTo 沿 parentId 回溯到
 * coveredFrom（含两端）的节点视为已压缩，原节点不删除。分叉后新分支若不包含该
 * summary 节点，范围引用自然失效，新旧分支互不影响。
 */
class ContextCompactor(
    private val engine: LlmEngineGateway,
    private val counter: TokenCounter = HeuristicTokenCounter(),
    private val thresholdRatio: Float = AgentConfig().compactThresholdRatio,
    private val targetWatermarkRatio: Float = TARGET_WATERMARK_RATIO,
    private val maxPasses: Int = DEFAULT_MAX_PASSES,
    private val maxSummaryChars: Int = DEFAULT_MAX_SUMMARY_CHARS,
    private val maxNodeChars: Int = DEFAULT_MAX_NODE_CHARS,
    private val maxTranscriptChars: Int = DEFAULT_MAX_TRANSCRIPT_CHARS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onWarning: (String) -> Unit = {},
) {

    init {
        require(thresholdRatio in 0f..1f) { "thresholdRatio 必须在 0..1 之间" }
        require(targetWatermarkRatio > 0f && targetWatermarkRatio <= 1f) {
            "targetWatermarkRatio 必须在 (0,1] 之间"
        }
        require(maxPasses >= 1) { "maxPasses 至少为 1" }
        require(maxSummaryChars > 0) { "maxSummaryChars 必须为正数" }
        require(maxNodeChars > 0) { "maxNodeChars 必须为正数" }
        require(maxTranscriptChars > 0) { "maxTranscriptChars 必须为正数" }
    }

    /** usedTokens / contextBudget ≥ [thresholdRatio] 时返回 true（设计文档 6.2）。 */
    fun shouldCompact(usedTokens: Int, contextBudget: Int): Boolean {
        require(contextBudget > 0) { "contextBudget 必须为正数" }
        if (usedTokens <= 0) return false
        return usedTokens.toFloat() / contextBudget.toFloat() >= thresholdRatio
    }

    /** 活动分支（或指定叶）的压缩后视图：跳过被覆盖链段，摘要按树序前置。 */
    fun view(store: TreeSessionStore, leafId: String? = null): CompactedView {
        // branchTo(null) 会返回空链（显式空参不触发默认值），这里显式分流。
        val chain = if (leafId == null) store.branchTo() else store.branchTo(leafId)
        val covered = resolveCoverage(chain)
        val summaries = chain
            .filter { it.role == SessionRole.SUMMARY }
            .map { it.content }
        val kept = chain.filterIndexed { index, node ->
            node.role != SessionRole.SUMMARY && index !in covered
        }
        return CompactedView(history = toTurnNodes(kept), summaries = summaries)
    }

    /** 按当前视图估算摘要段 + 历史段的 token 占用。 */
    fun estimateContextTokens(store: TreeSessionStore, leafId: String? = null): Int =
        view(store, leafId).estimatedTokens(counter)

    /** 汇总实测与估算占用；[measuredPromptTokens] 为空时完全依赖估算。 */
    fun usage(
        store: TreeSessionStore,
        measuredPromptTokens: Int? = null,
        leafId: String? = null,
    ): ContextUsage = ContextUsage(
        measuredPromptTokens = measuredPromptTokens?.coerceAtLeast(0),
        estimatedTokens = estimateContextTokens(store, leafId),
    )

    /** /compact 手动入口：语义与 [compact] 相同，但跳过阈值判定。 */
    suspend fun compactManually(
        store: TreeSessionStore,
        contextBudget: Int,
    ): CompactionResult = compact(store, contextBudget, trigger = CompactionTrigger.MANUAL)

    /**
     * 执行一次压缩。
     *
     * [usedTokens] 为上游实测的上下文占用（如最近一次生成的 promptTokens），为空时
     * 按 store 估算；自动触发取实测与估算的较大者，避免任一来源低估。自动触发仅在
     * 达到阈值时执行，手动触发忽略阈值。[contextBudget] 为模型上下文窗口扣除输出
     * 预留与系统段固定开销后的预算。
     */
    suspend fun compact(
        store: TreeSessionStore,
        contextBudget: Int,
        usedTokens: Int? = null,
        trigger: CompactionTrigger = CompactionTrigger.AUTO,
    ): CompactionResult {
        require(contextBudget > 0) { "contextBudget 必须为正数" }
        if (trigger == CompactionTrigger.MANUAL && store.isTurnActive) {
            return CompactionResult.DeferredUntilSafePoint
        }
        val before = estimateContextTokens(store)
        val used = maxOf(usedTokens ?: 0, before)
        if (trigger == CompactionTrigger.AUTO && !shouldCompact(used, contextBudget)) {
            return CompactionResult.Skipped(SkipReason.BELOW_THRESHOLD)
        }

        val ranges = mutableListOf<CompactedRange>()
        var after = before
        while (ranges.size < maxPasses) {
            val chain = store.branchTo()
            val plan = planCompaction(chain) ?: break
            val covered = chain.subList(plan.start, plan.end + 1)
            val summaryText = try {
                requestSummary(chain, covered)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return CompactionResult.Failed(
                    "摘要生成失败：" + (error.message ?: error::class.simpleName.orEmpty())
                )
            }
            if (summaryText.isBlank()) {
                return CompactionResult.Failed("摘要生成返回空内容")
            }
            val node = store.append(
                role = SessionRole.SUMMARY,
                content = summaryText,
                compaction = SummaryCompaction(
                    coveredFrom = chain[plan.start].id,
                    coveredTo = chain[plan.end].id,
                    nodeCount = plan.end - plan.start + 1,
                    estimatedTokens = covered.sumOf { counter.count(it.content).toLong() },
                    createdAt = clock(),
                ),
            )
            ranges += CompactedRange(
                summaryNodeId = node.id,
                coveredFrom = chain[plan.start].id,
                coveredTo = chain[plan.end].id,
                nodeCount = plan.end - plan.start + 1,
                estimatedTokens = node.compaction?.estimatedTokens ?: 0L,
            )
            after = estimateContextTokens(store)
            if (!exceedsWatermark(after, contextBudget)) break
        }
        if (ranges.isEmpty()) {
            return CompactionResult.Skipped(SkipReason.NOTHING_TO_COMPACT)
        }
        val ratio = after.toFloat() / contextBudget.toFloat()
        return CompactionResult.Compacted(
            ranges = ranges,
            trigger = trigger,
            estimatedTokensBefore = before,
            estimatedTokensAfter = after,
            postWatermarkRatio = ratio,
            watermarkSatisfied = ratio <= targetWatermarkRatio,
        )
    }

    /**
     * 规划本次可压缩的连续链段：从已覆盖范围之后开始，到保护窗口或既有摘要节点之前。
     *
     * 既有摘要节点是压缩边界而非输入：跨过它就需要把历史摘要展开后再摘要一遍，
     * 违背“历史摘要不重复展开”；多轮压缩会逐段推进，不会因此长期停滞。
     */
    private fun planCompaction(chain: List<SessionNode>): CompactionPlan? {
        if (chain.isEmpty()) return null
        val covered = resolveCoverage(chain)
        val protected = protectedIndices(chain)
        var start = (covered.maxOrNull() ?: -1) + 1
        while (start < chain.size && (chain[start].role == SessionRole.SUMMARY || start in protected)) {
            start++
        }
        if (start >= chain.size) return null
        var end = start - 1
        var index = start
        while (index < chain.size && index !in protected && chain[index].role != SessionRole.SUMMARY) {
            end = index
            index++
        }
        if (end < start) return null
        return CompactionPlan(start = start, end = end)
    }

    /**
     * 保护窗口索引（设计文档 6.3）：最近两个 user 轮次（当前轮全部 + 上一轮全部，
     * 是“上一轮 user/assistant 文本”的超集）、最新一条工具输出、既有 summary 节点。
     */
    private fun protectedIndices(chain: List<SessionNode>): Set<Int> {
        val protected = HashSet<Int>()
        val turnStarts = chain.indices.filter { chain[it].role == SessionRole.USER }
        val protectedStart = if (turnStarts.size >= 2) {
            turnStarts[turnStarts.size - 2]
        } else {
            turnStarts.lastOrNull() ?: 0
        }
        for (index in protectedStart until chain.size) protected += index
        val lastTool = chain.indexOfLast { it.role == SessionRole.TOOL }
        if (lastTool >= 0) protected += lastTool
        chain.forEachIndexed { index, node ->
            if (node.role == SessionRole.SUMMARY) protected += index
        }
        return protected
    }

    /** 汇总当前分支上所有有效 summary 覆盖的索引集合。 */
    private fun resolveCoverage(chain: List<SessionNode>): Set<Int> {
        if (chain.isEmpty()) return emptySet()
        val indexById = HashMap<String, Int>(chain.size)
        chain.forEachIndexed { index, node -> indexById[node.id] = index }
        val covered = HashSet<Int>()
        chain.forEach { node ->
            if (node.role != SessionRole.SUMMARY) return@forEach
            val compaction = node.compaction ?: return@forEach
            val range = resolveRange(chain, indexById, compaction.coveredFrom, compaction.coveredTo)
            if (range == null) {
                onWarning("摘要节点 " + node.id + " 的范围引用不在当前分支上，已忽略")
                return@forEach
            }
            covered += range
        }
        return covered
    }

    /**
     * 按设计语义解析范围：从 coveredTo 沿 parentId 回溯，必须恰好经过 coveredFrom。
     * 端点不在当前分支、顺序颠倒或链路断裂时返回 null（分叉后的旧摘要即落入此情形）。
     */
    private fun resolveRange(
        chain: List<SessionNode>,
        indexById: Map<String, Int>,
        coveredFrom: String,
        coveredTo: String,
    ): IntRange? {
        val start = indexById[coveredFrom] ?: return null
        val end = indexById[coveredTo] ?: return null
        if (start > end) return null
        var cursor: String? = coveredTo
        val visited = HashSet<String>()
        while (cursor != null) {
            if (!visited.add(cursor)) return null
            if (cursor == coveredFrom) return start..end
            val index = indexById[cursor] ?: return null
            cursor = chain[index].parentId
        }
        return null
    }

    /** 生成摘要：固定极简模板 + 既有摘要衔接块（不重复展开原文）+ 截断后的待压缩历史。 */
    private suspend fun requestSummary(
        chain: List<SessionNode>,
        covered: List<SessionNode>,
    ): String {
        val priorSummaries = chain
            .filter { it.role == SessionRole.SUMMARY }
            .map { it.content }
        val messages = listOf(
            PromptMessage(PromptRole.SYSTEM, SUMMARY_PROMPT),
            PromptMessage(PromptRole.USER, buildTranscript(priorSummaries, covered)),
        )
        val output = engine.streamGenerate(messages)
        return truncateSummary(output.text.trim())
    }

    private fun buildTranscript(priorSummaries: List<String>, covered: List<SessionNode>): String {
        val builder = StringBuilder()
        if (priorSummaries.isNotEmpty()) {
            builder.append("已有摘要（更早压缩结果，供衔接参考）：\n")
            builder.append(priorSummaries.joinToString("\n---\n"))
            builder.append("\n\n")
        }
        builder.append("待压缩历史：\n")
        val quota = perNodeQuota(covered.size)
        covered.forEach { node -> builder.append(renderNode(node, quota)).append('\n') }
        return builder.toString().trimEnd()
    }

    /** 逐节点截断：单节点不超过 [maxNodeChars]，节点多时按总字数预算均摊。 */
    private fun perNodeQuota(nodeCount: Int): Int {
        if (nodeCount <= 0) return maxNodeChars
        return (maxTranscriptChars / nodeCount).coerceIn(1, maxNodeChars)
    }

    private fun renderNode(node: SessionNode, quota: Int): String {
        val label = when (node.role) {
            SessionRole.USER -> "[用户]"
            SessionRole.ASSISTANT -> "[助手]"
            SessionRole.TOOL -> "[工具" + (node.tool?.takeIf { it.isNotBlank() }?.let { "/" + it }.orEmpty()) + "]"
            SessionRole.SUMMARY -> "[摘要]"
            SessionRole.SYSTEM -> "[系统]"
        }
        val content = node.content.ifBlank { "（空）" }
        val clipped = if (content.length > quota) content.take(quota) + TRUNCATED_MARK else content
        val calls = if (node.role == SessionRole.ASSISTANT && node.toolCalls.isNotEmpty()) {
            "（请求工具：" + node.toolCalls.joinToString("、") { it.tool } + "）"
        } else {
            ""
        }
        return label + " " + clipped + calls
    }

    private fun truncateSummary(text: String): String =
        if (text.length <= maxSummaryChars) text else text.take(maxSummaryChars) + TRUNCATED_MARK

    private fun exceedsWatermark(tokens: Int, contextBudget: Int): Boolean =
        tokens.toFloat() / contextBudget.toFloat() > targetWatermarkRatio

    /** 活动分支节点转 [TurnNode] 视图（与 [TreeSessionStore.snapshot] 的字段映射一致）。 */
    private fun toTurnNodes(nodes: List<SessionNode>): List<TurnNode> {
        val callsById = HashMap<String, SessionToolCall>()
        nodes.filter { it.role == SessionRole.ASSISTANT }.forEach { assistant ->
            assistant.toolCalls.forEach { callsById[it.callId] = it }
        }
        return nodes.mapNotNull { node ->
            when (node.role) {
                SessionRole.USER -> TurnNode(
                    role = TurnRole.USER,
                    content = node.content,
                    tags = node.tags,
                )

                SessionRole.ASSISTANT -> TurnNode(
                    role = TurnRole.ASSISTANT,
                    content = node.content,
                    toolCalls = node.toolCalls.map { ToolCall(it.tool, it.argsJson) },
                    tags = node.tags,
                    parseRetries = node.parseRetries,
                    metrics = node.metrics,
                    stepIndex = node.stepIndex,
                )

                SessionRole.TOOL -> TurnNode(
                    role = TurnRole.TOOL,
                    content = node.content,
                    toolCalls = node.callId
                        ?.let { callsById[it] }
                        ?.let { listOf(ToolCall(it.tool, it.argsJson)) }
                        .orEmpty(),
                    tags = node.tags,
                    stepIndex = node.stepIndex,
                    toolStatus = node.toolStatus,
                    errorCode = node.errorCode,
                    durationMs = node.durationMs,
                )

                else -> null
            }
        }
    }

    private data class CompactionPlan(val start: Int, val end: Int)

    companion object {
        /** 压后目标水位（设计文档 6.4）：usedRatio ≤ 0.5。 */
        const val TARGET_WATERMARK_RATIO: Float = 0.5f

        /** 单次 [compact] 内最多追加的摘要节点数；正常场景一轮即达到目标水位。 */
        const val DEFAULT_MAX_PASSES: Int = 3

        /** 摘要正文长度上限（字符）。 */
        const val DEFAULT_MAX_SUMMARY_CHARS: Int = 500

        /** 送入摘要的单节点内容上限（字符）。 */
        const val DEFAULT_MAX_NODE_CHARS: Int = 400

        /** 送入摘要的历史总字符预算，超出时按节点均摊截断。 */
        const val DEFAULT_MAX_TRANSCRIPT_CHARS: Int = 16000

        /**
         * 固定短小摘要模板（设计文档 6.4 四段式）。作为 SYSTEM 消息发送，会话内
         * 字节级稳定；要求输出短且只依据给定历史，降低摘要二次幻觉。
         */
        const val SUMMARY_PROMPT: String =
            "你负责压缩对话历史。请把待压缩历史整理为四段式中文摘要，只输出摘要正文：" +
                "一、用户目标；二、已完成操作与结果；三、关键文件路径；四、未决事项。" +
                "要求：简洁客观，不超过 300 字；只依据给定历史，不要编造。"

        private const val TRUNCATED_MARK = "…（已截断）"
    }
}
