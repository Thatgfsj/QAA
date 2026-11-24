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
 *
 * 多轮压缩遵循“替换而非叠加”（#100）：新的压缩范围可以吸收既有 summary 节点，其内容经
 * “已有摘要”段并入新摘要，被吸收的旧摘要随即从视图中消失；若估算本次压缩无收益（摘要
 * 开销不低于被覆盖内容），则不改树并返回 [SkipReason.NOT_WORTHWHILE]。
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
        // 被新摘要范围吸收的旧摘要不再前置（替换语义）：否则多轮压缩会把摘要层层叠加，
        // 压后 token 反而比压前更大（#100）。
        val summaries = chain
            .filterIndexed { index, node ->
                node.role == SessionRole.SUMMARY && index !in covered
            }
            .map { it.content }
        val kept = chain.filterIndexed { index, node ->
            node.role != SessionRole.SUMMARY && index !in covered
        }
        return CompactedView(history = toTurnNodes(kept), summaries = summaries)
    }

    /**
     * 按当前视图估算摘要段 + 历史段的 token 占用。
     *
     * [prefixTokens] 为 PromptAssembler 稳定前缀（system + skills 段）的 token 数（#106）：
     * 视图估算不含前缀，压缩判定与回执需加上它才能与真实 prompt 同口径；默认 0 保持既有行为。
     */
    fun estimateContextTokens(
        store: TreeSessionStore,
        leafId: String? = null,
        prefixTokens: Int = 0,
    ): Int = prefixTokens.coerceAtLeast(0) + view(store, leafId).estimatedTokens(counter)

    /** 汇总实测与估算占用；[measuredPromptTokens] 为空时完全依赖估算。 */
    fun usage(
        store: TreeSessionStore,
        measuredPromptTokens: Int? = null,
        leafId: String? = null,
        prefixTokens: Int = 0,
    ): ContextUsage = ContextUsage(
        measuredPromptTokens = measuredPromptTokens?.coerceAtLeast(0),
        estimatedTokens = estimateContextTokens(store, leafId, prefixTokens),
    )

    /** /compact 手动入口：语义与 [compact] 相同，但跳过阈值判定。 */
    suspend fun compactManually(
        store: TreeSessionStore,
        contextBudget: Int,
        prefixTokens: Int = 0,
    ): CompactionResult = compact(
        store = store,
        contextBudget = contextBudget,
        trigger = CompactionTrigger.MANUAL,
        prefixTokens = prefixTokens,
    )

    /**
     * 执行一次压缩。
     *
     * [usedTokens] 为上游实测的上下文占用（如最近一次生成的 promptTokens），为空时
     * 按 store 估算；自动触发取实测与估算的较大者，避免任一来源低估。自动触发仅在
     * 达到阈值时执行；手动触发忽略阈值，但占用已不高于目标水位时返回
     * [SkipReason.ALREADY_BELOW_TARGET] 且不改树（#100）。[contextBudget] 为模型上下文
     * 窗口扣除输出预留与系统段固定开销后的预算。[prefixTokens] 为稳定前缀 token 数，
     * 回执 before/after 与判定使用同一口径（#106）。
     */
    suspend fun compact(
        store: TreeSessionStore,
        contextBudget: Int,
        usedTokens: Int? = null,
        trigger: CompactionTrigger = CompactionTrigger.AUTO,
        prefixTokens: Int = 0,
    ): CompactionResult {
        require(contextBudget > 0) { "contextBudget 必须为正数" }
        if (trigger == CompactionTrigger.MANUAL && store.isTurnActive) {
            return CompactionResult.DeferredUntilSafePoint
        }
        val before = estimateContextTokens(store, prefixTokens = prefixTokens)
        val used = maxOf(usedTokens ?: 0, before)
        val target = targetTokens(contextBudget)
        if (trigger == CompactionTrigger.AUTO && !shouldCompact(used, contextBudget)) {
            return CompactionResult.Skipped(
                reason = SkipReason.BELOW_THRESHOLD,
                currentTokens = before,
                contextBudget = contextBudget,
                targetTokens = target,
            )
        }
        // 手动入口只在确有收益时动手：占用已不高于目标水位时，再压一次只是用摘要开销
        // 换掉少量历史，回执就会出现“压后比压前更大”（#100）。
        if (trigger == CompactionTrigger.MANUAL && before <= target) {
            return CompactionResult.Skipped(
                reason = SkipReason.ALREADY_BELOW_TARGET,
                currentTokens = before,
                contextBudget = contextBudget,
                targetTokens = target,
            )
        }

        val ranges = mutableListOf<CompactedRange>()
        var after = before
        var summaryChars = 0
        var replacedSummaries = 0
        var sawPlan = false
        while (ranges.size < maxPasses) {
            val chain = store.branchTo()
            val plan = planCompaction(chain) ?: break
            sawPlan = true
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
            // 先估算“替换后”的占用：摘要开销不低于将被覆盖的内容时不写树，保证
            // estimatedTokensAfter ≤ estimatedTokensBefore，连续压缩不会越压越大（#100）。
            val removedTokens = messageTokens(covered.map { it.content })
            val summaryTokens = messageTokens(listOf(summaryText))
            if (summaryTokens >= removedTokens) break
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
            summaryChars += summaryText.length
            replacedSummaries += covered.count { it.role == SessionRole.SUMMARY }
            after = estimateContextTokens(store, prefixTokens = prefixTokens)
            if (!exceedsWatermark(after, contextBudget)) break
        }
        if (ranges.isEmpty()) {
            return CompactionResult.Skipped(
                reason = if (sawPlan) SkipReason.NOT_WORTHWHILE else SkipReason.NOTHING_TO_COMPACT,
                currentTokens = before,
                contextBudget = contextBudget,
                targetTokens = target,
            )
        }
        val ratio = after.toFloat() / contextBudget.toFloat()
        return CompactionResult.Compacted(
            ranges = ranges,
            trigger = trigger,
            estimatedTokensBefore = before,
            estimatedTokensAfter = after,
            postWatermarkRatio = ratio,
            watermarkSatisfied = ratio <= targetWatermarkRatio,
            retainedNodeCount = view(store).history.size,
            summaryChars = summaryChars,
            replacedSummaryCount = replacedSummaries,
        )
    }

    /**
     * 规划本次可压缩的连续链段：从已覆盖范围之后开始，到保护窗口之前。
     *
     * 既有摘要节点允许被并入本次范围（替换语义）：其内容经 requestSummary 的“已有摘要”
     * 段衔接进新摘要，覆盖后旧摘要从视图消失；否则连续压缩会不断新增摘要节点，压后
     * token 反而变大（#100）。范围必须至少含一个非摘要节点，避免只重写摘要而无收益。
     */
    private fun planCompaction(chain: List<SessionNode>): CompactionPlan? {
        if (chain.isEmpty()) return null
        val covered = resolveCoverage(chain)
        val protected = protectedIndices(chain)
        var start = (covered.maxOrNull() ?: -1) + 1
        while (start < chain.size && start in protected) {
            start++
        }
        if (start >= chain.size) return null
        var end = start - 1
        var index = start
        var historyNodes = 0
        while (index < chain.size && index !in protected) {
            if (chain[index].role != SessionRole.SUMMARY) historyNodes++
            end = index
            index++
        }
        if (end < start || historyNodes == 0) return null
        return CompactionPlan(start = start, end = end)
    }

    /**
     * 保护窗口索引（设计文档 6.3）：最近两个 user 轮次（当前轮全部 + 上一轮全部，
     * 是“上一轮 user/assistant 文本”的超集）与最新一条工具输出。
     *
     * 既有 summary 节点不在此列：它由 [planCompaction] 按替换语义并入下一次压缩（#100）。
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
        // 被本次范围吸收的旧摘要走“待压缩历史”的 [摘要] 段，这里不再重复拼接。
        val coveredIds = covered.mapTo(HashSet()) { it.id }
        val priorSummaries = chain
            .filter { it.role == SessionRole.SUMMARY && it.id !in coveredIds }
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

    /** 目标水位 token 数：压缩后占用应不高于该值（0.5·预算，设计文档 6.4）。 */
    private fun targetTokens(contextBudget: Int): Int =
        (contextBudget * targetWatermarkRatio).toInt().coerceAtLeast(1)

    /**
     * 按 [CompactedView.messages] 的口径估算一组正文的 token 数（角色不影响计数）。
     * 用于在写树之前复算“去掉覆盖内容 + 加入新摘要”的压后占用。
     */
    private fun messageTokens(contents: List<String>): Int = counter.countMessages(
        contents.map { PromptMessage(PromptRole.SYSTEM, it) }
    )

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
