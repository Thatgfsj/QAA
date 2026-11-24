package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ContextCompactor JVM 单测（设计附录 A.6）：C1–C5、C7、C8，另含手动入口、
 * 摘要失败回滚、固定摘要 Prompt 与逐节点截断。
 */
class ContextCompactorTest {

    private val budget = 1_000

    private fun newEngine(replies: List<String> = listOf(RecordingSummaryEngine.DEFAULT_SUMMARY)) =
        RecordingSummaryEngine(replies)

    /** 预算 1000 下的目标水位 token 数（0.5 × 预算）。 */
    private val TARGET_TOKENS = 500

    /** 占用高于目标水位（500）但低于压缩阈值（800）：手动入口的“有收益”区间。 */
    private fun storeAboveTargetBelowThreshold(): TreeSessionStore {
        val store = TreeSessionStore("manual-compact")
        store.appendTurn(
            userText = "$T1_USER ${fillerTokens(200)}",
            assistantText = "$T1_ASSISTANT ${fillerTokens(200)}",
        )
        store.appendTurn("$T2_USER 继续整理。", "$T2_ASSISTANT 已整理完毕。")
        store.appendTurn(
            userText = "$T3_USER 再补一段结论。",
            assistantText = "$T3_ASSISTANT 结论如下。",
            toolOutput = "$T3_TOOL 最新工具输出",
        )
        return store
    }

    /** 沿 parentId 回溯摘要范围（含两端），用于校验旧摘要是否被新范围吸收。 */
    private fun coveredIds(store: TreeSessionStore, compaction: SummaryCompaction): List<String> {
        val ids = mutableListOf<String>()
        var cursor: String? = compaction.coveredTo
        while (cursor != null) {
            ids += cursor
            if (cursor == compaction.coveredFrom) break
            cursor = store.node(cursor)?.parentId
        }
        return ids
    }

    private fun storeWithThreeTurns(): TreeSessionStore {
        val store = TreeSessionStore("compaction")
        store.appendTurn(
            userText = "$T1_USER 目标：整理 notes.txt 并给出结论。",
            assistantText = "$T1_ASSISTANT 我先读取文件。",
            toolOutput = "$T1_TOOL 文件内容",
        )
        store.appendTurn(
            userText = "$T2_USER 继续整理。",
            assistantText = "$T2_ASSISTANT 已整理完毕。",
        )
        store.appendTurn(
            userText = "$T3_USER 再补一段结论。",
            assistantText = "$T3_ASSISTANT 结论如下。",
            // 最新工具输出落在当前轮，避免早前工具输出被“最新一条工具输出”保护规则挡在压缩范围外。
            toolOutput = "$T3_TOOL 最新工具输出",
        )
        return store
    }

    @Test
    fun C1_阈值下不触发且安全点检查无副作用() = runTest {
        val store = storeWithThreeTurns()
        val engine = newEngine()
        val compactor = ContextCompactor(engine)
        val nodeCount = store.nodeCount()

        assertFalse(compactor.shouldCompact(799, budget))
        assertTrue(compactor.shouldCompact(800, budget))

        val result = compactor.compact(store, budget, usedTokens = 799)
        assertTrue(result is CompactionResult.Skipped)
        assertEquals(SkipReason.BELOW_THRESHOLD, (result as CompactionResult.Skipped).reason)
        assertEquals(nodeCount, store.nodeCount())
        assertEquals(0, engine.invokeCount)
        assertTrue(compactor.view(store).summaries.isEmpty())
    }

    @Test
    fun C2_阈值上自动触发一次并回写摘要节点() = runTest {
        val store = storeWithThreeTurns()
        val engine = newEngine()
        val compactor = ContextCompactor(engine, clock = { FIXED_TIME })
        val firstTurn = store.branchTo().take(3)

        val result = compactor.compact(store, budget, usedTokens = 800)
        assertTrue(result is CompactionResult.Compacted)
        val compacted = result as CompactionResult.Compacted

        assertEquals(1, compacted.ranges.size)
        assertEquals(1, engine.invokeCount)
        assertEquals(CompactionTrigger.AUTO, compacted.trigger)
        assertEquals(firstTurn.first().id, compacted.ranges.single().coveredFrom)
        assertEquals(firstTurn.last().id, compacted.ranges.single().coveredTo)
        assertEquals(firstTurn.size, compacted.ranges.single().nodeCount)

        val summary = store.node(compacted.summaryNodeId)!!
        assertEquals(SessionRole.SUMMARY, summary.role)
        assertEquals(firstTurn.first().id, summary.compaction?.coveredFrom)
        assertEquals(firstTurn.last().id, summary.compaction?.coveredTo)
        assertEquals(firstTurn.size, summary.compaction?.nodeCount)
        assertEquals(FIXED_TIME, summary.compaction?.createdAt)
        assertTrue((summary.compaction?.estimatedTokens ?: 0L) > 0L)
    }

    @Test
    fun C2_无实测值时按估算兜底触发() = runTest {
        val store = TreeSessionStore("estimate")
        repeat(3) {
            store.appendTurn(fillerTokens(300), fillerTokens(300))
        }
        val engine = newEngine()
        val compactor = ContextCompactor(engine)
        assertTrue(compactor.estimateContextTokens(store) > 800)

        val result = compactor.compact(store, budget)
        assertTrue(result is CompactionResult.Compacted)
        assertEquals(1, engine.invokeCount)
    }

    @Test
    fun C3_保护窗口保留当前轮上轮与最新工具输出() = runTest {
        val store = TreeSessionStore("window")
        // 第一轮放入足量内容，确保摘要开销低于被覆盖内容（否则会被收益保护跳过）。
        val filler = fillerTokens(100)
        val first = store.appendTurn(
            "$T1_USER 第一轮 $filler",
            "$T1_ASSISTANT 第一轮回答 $filler",
            toolOutput = "$T1_TOOL 第一轮工具输出 $filler",
        )
        val second = store.appendTurn(
            "$T2_USER 第二轮",
            "$T2_ASSISTANT 第二轮回答",
            toolOutput = "$T2_TOOL 第二轮工具输出",
        )
        val third = store.appendTurn(
            "$T3_USER 第三轮",
            "$T3_ASSISTANT 第三轮回答",
            toolOutput = "$T3_TOOL 最新工具输出",
        )
        val compactor = ContextCompactor(newEngine())
        val compacted = compactor.compact(store, budget, usedTokens = 900) as CompactionResult.Compacted

        assertEquals(first.first().id, compacted.ranges.single().coveredFrom)
        assertEquals(first.last().id, compacted.ranges.single().coveredTo)

        val view = compactor.view(store)
        val historyContents = view.history.map { it.content }
        (second + third).forEach { node -> assertTrue(historyContents.contains(node.content)) }
        first.forEach { node -> assertFalse(historyContents.contains(node.content)) }
        assertEquals("$T3_TOOL 最新工具输出", view.history.last { it.role == TurnRole.TOOL }.content)
        assertEquals(1, view.summaries.size)
    }

    @Test
    fun C3_多轮压缩把既有摘要并入新范围_替换而非叠加() = runTest {
        val store = TreeSessionStore("existing-summary")
        store.appendTurn("$T1_USER 第一轮", "$T1_ASSISTANT 第一轮回答")
        store.appendTurn("$T2_USER 第二轮", "$T2_ASSISTANT 第二轮回答")
        store.appendTurn("$T3_USER 第三轮", "$T3_ASSISTANT 第三轮回答")
        val engine = newEngine(listOf("摘要一：$SUMMARY_ONE", "摘要二：$SUMMARY_TWO"))
        val compactor = ContextCompactor(engine)
        val first = compactor.compact(store, budget, usedTokens = 900) as CompactionResult.Compacted
        val firstSummaryId = first.summaryNodeId

        store.appendTurn("$T4_USER 第四轮", "$T4_ASSISTANT 第四轮回答")
        store.appendTurn("$T5_USER 第五轮", "$T5_ASSISTANT 第五轮回答")
        val second = compactor.compact(store, budget, usedTokens = 900) as CompactionResult.Compacted

        assertEquals(2, engine.invokeCount)
        // 旧摘要被第二次范围吸收后从视图消失：视图始终只留一个摘要段，避免叠加膨胀（#100）。
        val view = compactor.view(store)
        assertEquals(listOf("摘要二：$SUMMARY_TWO"), view.summaries)

        val secondCompaction = store.node(second.summaryNodeId)!!.compaction!!
        val secondRangeIds = coveredIds(store, secondCompaction)
        assertTrue(secondRangeIds.contains(secondCompaction.coveredFrom))
        assertTrue(secondRangeIds.contains(firstSummaryId))
        assertEquals(1, second.replacedSummaryCount)

        // 第二次摘要输入衔接既有摘要，但不重复展开第一轮原文。
        assertTrue(engine.calls[1].any { it.content.contains("摘要一：$SUMMARY_ONE") })
        assertFalse(engine.calls[1].any { it.content.contains(T1_USER) })
    }

    @Test
    fun 既有摘要形成分段时多轮压缩达到目标水位() = runTest {
        val store = TreeSessionStore("multi-pass")
        store.appendTurn("$T1_USER 第一轮", "$T1_ASSISTANT 第一轮回答")
        store.appendTurn("$T2_USER 第二轮", "$T2_ASSISTANT 第二轮回答")
        store.appendTurn("$T3_USER 第三轮", "$T3_ASSISTANT 第三轮回答")
        val engine = newEngine(
            listOf("摘要一：$SUMMARY_ONE", "摘要二：$SUMMARY_TWO", "摘要三：S3-MARK")
        )
        val compactor = ContextCompactor(engine)
        val first = compactor.compact(store, budget, usedTokens = 900) as CompactionResult.Compacted
        assertEquals(1, first.ranges.size)

        val big = fillerTokens(600)
        store.appendTurn("$T4_USER $big", "$T4_ASSISTANT $big")
        store.appendTurn("$T5_USER $big", "$T5_ASSISTANT $big")
        store.appendTurn("$T6_USER $big", "$T6_ASSISTANT $big")
        store.appendTurn("$T7_USER 第七轮", "$T7_ASSISTANT 第七轮回答")
        store.appendTurn("$T8_USER 第八轮", "$T8_ASSISTANT 第八轮回答")

        val second = compactor.compact(store, budget, usedTokens = 900) as CompactionResult.Compacted
        // 既有摘要不再是分段边界：一次扫描就把整段未保护历史连同旧摘要压成一段。
        assertEquals(1, second.ranges.size)
        assertTrue(second.watermarkSatisfied)
        assertTrue(second.estimatedTokensAfter < second.estimatedTokensBefore)
        assertEquals(1, compactor.view(store).summaries.size)
    }

    @Test
    fun C4_范围引用沿parentId回溯一致() = runTest {
        val store = storeWithThreeTurns()
        val compactor = ContextCompactor(newEngine())
        val compacted = compactor.compact(store, budget, usedTokens = 900) as CompactionResult.Compacted
        val compaction = store.node(compacted.summaryNodeId)!!.compaction!!

        val walked = mutableListOf<String>()
        var cursor: String? = compaction.coveredTo
        while (cursor != null) {
            walked += cursor
            if (cursor == compaction.coveredFrom) break
            cursor = store.node(cursor)?.parentId
        }
        assertEquals(compaction.nodeCount, walked.size)
        assertEquals(compaction.coveredFrom, walked.last())

        val branchIds = store.branchTo().map { it.id }
        assertTrue(branchIds.containsAll(walked))
        assertTrue(branchIds.indexOf(compaction.coveredFrom) < branchIds.indexOf(compaction.coveredTo))
    }

    @Test
    fun C5_组装跳过被压缩链段且摘要前置() = runTest {
        val store = TreeSessionStore("assemble")
        store.appendTurn("$T1_USER 第一轮", "$T1_ASSISTANT 第一轮回答")
        val second = store.appendTurn("$T2_USER 第二轮", "$T2_ASSISTANT 第二轮回答")
        store.appendTurn("$T3_USER 第三轮", "$T3_ASSISTANT 第三轮回答")
        val engine = newEngine(listOf("摘要：$SUMMARY_ONE"))
        val compactor = ContextCompactor(engine)
        compactor.compact(store, budget, usedTokens = 900)

        val view = compactor.view(store)
        val messages = view.assemble(PromptAssembler())
        val contents = messages.map { it.content }
        assertFalse(contents.any { it.contains(T1_USER) })
        assertFalse(contents.any { it.contains(T1_ASSISTANT) })

        val summaryIndex = contents.indexOf("摘要：$SUMMARY_ONE")
        assertTrue(summaryIndex > 0)
        assertEquals(PromptRole.SYSTEM, messages[summaryIndex].role)

        val firstHistoryIndex = contents.indexOf(second.first().content)
        assertTrue(summaryIndex < firstHistoryIndex)
        val historyRoles = messages.drop(firstHistoryIndex).map { it.role }
        assertEquals(
            listOf(
                PromptRole.USER,
                PromptRole.ASSISTANT,
                PromptRole.USER,
                PromptRole.ASSISTANT,
            ),
            historyRoles,
        )
    }

    @Test
    fun C6_手动触发_低于阈值也执行() = runTest {
        val store = storeAboveTargetBelowThreshold()
        val engine = newEngine()
        val compactor = ContextCompactor(engine)
        val before = compactor.estimateContextTokens(store)
        assertFalse(compactor.shouldCompact(before, budget))
        assertTrue(before > TARGET_TOKENS)

        val result = compactor.compactManually(store, budget) as CompactionResult.Compacted
        assertEquals(CompactionTrigger.MANUAL, result.trigger)
        assertEquals(1, engine.invokeCount)
    }

    @Test
    fun C6_占用不高于目标水位时手动压缩不改树() = runTest {
        val store = storeWithThreeTurns()
        val engine = newEngine()
        val compactor = ContextCompactor(engine)
        val nodeCount = store.nodeCount()
        val before = compactor.estimateContextTokens(store)
        assertTrue(before <= TARGET_TOKENS)

        val result = compactor.compactManually(store, budget) as CompactionResult.Skipped
        assertEquals(SkipReason.ALREADY_BELOW_TARGET, result.reason)
        assertEquals(before, result.currentTokens)
        assertEquals(TARGET_TOKENS, result.targetTokens)
        assertEquals(0, engine.invokeCount)
        assertEquals(nodeCount, store.nodeCount())
    }

    @Test
    fun C6_摘要开销不低于被覆盖内容时跳过且不改树() = runTest {
        val store = TreeSessionStore("no-gain")
        store.appendTurn("hi", "ok")
        store.appendTurn("$T2_USER ${fillerTokens(300)}", "$T2_ASSISTANT ${fillerTokens(300)}")
        store.appendTurn("$T3_USER ${fillerTokens(300)}", "$T3_ASSISTANT ${fillerTokens(300)}")
        val engine = newEngine()
        val compactor = ContextCompactor(engine)
        val nodeCount = store.nodeCount()

        val result = compactor.compactManually(store, budget) as CompactionResult.Skipped
        assertEquals(SkipReason.NOT_WORTHWHILE, result.reason)
        // 摘要已生成一次，但估算无收益故不写树。
        assertEquals(1, engine.invokeCount)
        assertEquals(nodeCount, store.nodeCount())
    }

    @Test
    fun C7_回执字段与压后token不增长() = runTest {
        val store = storeAboveTargetBelowThreshold()
        val compactor = ContextCompactor(newEngine())
        val before = compactor.estimateContextTokens(store)

        val result = compactor.compactManually(store, budget) as CompactionResult.Compacted
        assertEquals(before, result.estimatedTokensBefore)
        assertTrue(result.estimatedTokensAfter < result.estimatedTokensBefore)
        assertEquals(
            result.estimatedTokensBefore - result.estimatedTokensAfter,
            result.savedTokens,
        )
        assertTrue(result.retainedNodeCount > 0)
        assertTrue(result.summaryChars > 0)

        // 连续压缩：占用水位已低于目标，第二次直接跳过且不再追加摘要节点。
        val nodeCount = store.nodeCount()
        val second = compactor.compactManually(store, budget) as CompactionResult.Skipped
        assertEquals(SkipReason.ALREADY_BELOW_TARGET, second.reason)
        assertEquals(nodeCount, store.nodeCount())
        assertEquals(1, compactor.view(store).summaries.size)
    }

    @Test
    fun C6_运行中手动压缩延后且不改树() = runTest {
        val store = storeAboveTargetBelowThreshold()
        val compactor = ContextCompactor(newEngine())
        store.markTurnActive(true)
        val nodeCount = store.nodeCount()

        assertEquals(CompactionResult.DeferredUntilSafePoint, compactor.compactManually(store, budget))
        assertEquals(nodeCount, store.nodeCount())

        store.markTurnActive(false)
        assertTrue(compactor.compactManually(store, budget) is CompactionResult.Compacted)
    }

    @Test
    fun C7_压后水位不超过目标() = runTest {
        val store = TreeSessionStore("watermark")
        val big = fillerTokens(3_000)
        store.appendTurn("$T1_USER $big", "$T1_ASSISTANT $big", toolOutput = "$T1_TOOL $big")
        store.appendTurn("$T2_USER 第二轮", "$T2_ASSISTANT 第二轮回答")
        store.appendTurn("$T3_USER 第三轮", "$T3_ASSISTANT 第三轮回答", toolOutput = "$T3_TOOL 最新工具输出")
        val compactor = ContextCompactor(newEngine())
        val before = compactor.estimateContextTokens(store)
        assertTrue(before > 0.8f * budget)

        val result = compactor.compact(store, budget) as CompactionResult.Compacted
        assertTrue(result.postWatermarkRatio <= 0.5f)
        assertTrue(result.watermarkSatisfied)
        assertEquals(compactor.estimateContextTokens(store), result.estimatedTokensAfter)
        assertTrue(result.estimatedTokensAfter < result.estimatedTokensBefore)
    }

    @Test
    fun 无可压缩节点时跳过() = runTest {
        val store = TreeSessionStore("short")
        store.appendTurn("$T1_USER 唯一一轮", "$T1_ASSISTANT 唯一回答")
        val engine = newEngine()
        val result = ContextCompactor(engine).compact(store, budget, usedTokens = 900)

        val skipped = result as CompactionResult.Skipped
        assertEquals(SkipReason.NOTHING_TO_COMPACT, skipped.reason)
        assertEquals(0, engine.invokeCount)
    }

    @Test
    fun 摘要生成失败时不写树() = runTest {
        val store = storeWithThreeTurns()
        val nodeCount = store.nodeCount()
        val result = ContextCompactor(
            RecordingSummaryEngine(failure = IllegalStateException("引擎不可用"))
        ).compact(store, budget, usedTokens = 900)

        assertTrue(result is CompactionResult.Failed)
        assertTrue((result as CompactionResult.Failed).reason.contains("引擎不可用"))
        assertEquals(nodeCount, store.nodeCount())
    }

    @Test
    fun 摘要为空时不写树() = runTest {
        val store = storeWithThreeTurns()
        val nodeCount = store.nodeCount()
        val result = ContextCompactor(newEngine(listOf("   ")))
            .compact(store, budget, usedTokens = 900)

        assertTrue(result is CompactionResult.Failed)
        assertEquals(nodeCount, store.nodeCount())
    }

    @Test
    fun 摘要使用固定短小prompt并携带待压缩历史() = runTest {
        val store = storeWithThreeTurns()
        val engine = newEngine()
        ContextCompactor(engine).compact(store, budget, usedTokens = 900)

        assertEquals(1, engine.invokeCount)
        val messages = engine.calls.single()
        assertEquals(2, messages.size)
        assertEquals(PromptRole.SYSTEM, messages[0].role)
        assertEquals(ContextCompactor.SUMMARY_PROMPT, messages[0].content)
        assertTrue(ContextCompactor.SUMMARY_PROMPT.length <= 200)
        assertTrue(ContextCompactor.SUMMARY_PROMPT.contains("用户目标"))
        assertTrue(ContextCompactor.SUMMARY_PROMPT.contains("关键文件路径"))
        assertTrue(ContextCompactor.SUMMARY_PROMPT.contains("未决事项"))

        assertEquals(PromptRole.USER, messages[1].role)
        assertTrue(messages[1].content.contains("待压缩历史"))
        assertTrue(messages[1].content.contains(T1_USER))
        assertTrue(messages[1].content.contains("[工具/read]"))
    }

    @Test
    fun 待压缩历史按节点截断() = runTest {
        val store = TreeSessionStore("truncate")
        val long = "长".repeat(5_000)
        store.appendTurn("$T1_USER $long", "$T1_ASSISTANT $long", toolOutput = long)
        store.appendTurn("$T2_USER 第二轮", "$T2_ASSISTANT 第二轮回答")
        store.appendTurn("$T3_USER 第三轮", "$T3_ASSISTANT 第三轮回答")
        val engine = newEngine()
        val compactor = ContextCompactor(engine, maxNodeChars = 100, maxTranscriptChars = 1_000)
        compactor.compact(store, budget, usedTokens = 900)

        val prompt = engine.lastUserPrompt
        assertTrue(prompt.contains("…（已截断）"))
        assertTrue(prompt.length < 1_500)
    }

    @Test
    fun 摘要正文受长度上限约束() = runTest {
        val store = storeAboveTargetBelowThreshold()
        val longSummary = "摘要".repeat(400)
        val compactor = ContextCompactor(newEngine(listOf(longSummary)), maxSummaryChars = 120)
        val compacted = compactor.compactManually(store, budget) as CompactionResult.Compacted

        val content = store.node(compacted.summaryNodeId)!!.content
        assertTrue(content.length <= 130)
        assertTrue(content.endsWith("…（已截断）"))
    }

    private companion object {
        const val FIXED_TIME = 1_763_932_800_456L
        const val T1_USER = "T1-USER"
        const val T1_ASSISTANT = "T1-ASSISTANT"
        const val T1_TOOL = "T1-TOOL"
        const val T2_USER = "T2-USER"
        const val T2_ASSISTANT = "T2-ASSISTANT"
        const val T2_TOOL = "T2-TOOL"
        const val T3_USER = "T3-USER"
        const val T3_ASSISTANT = "T3-ASSISTANT"
        const val T3_TOOL = "T3-TOOL"
        const val T4_USER = "T4-USER"
        const val T4_ASSISTANT = "T4-ASSISTANT"
        const val T5_USER = "T5-USER"
        const val T5_ASSISTANT = "T5-ASSISTANT"
        const val T6_USER = "T6-USER"
        const val T6_ASSISTANT = "T6-ASSISTANT"
        const val T7_USER = "T7-USER"
        const val T7_ASSISTANT = "T7-ASSISTANT"
        const val T8_USER = "T8-USER"
        const val T8_ASSISTANT = "T8-ASSISTANT"
        const val SUMMARY_ONE = "S1-MARK"
        const val SUMMARY_TWO = "S2-MARK"
    }
}
