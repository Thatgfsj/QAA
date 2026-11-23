package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 摘要节点落盘与重载：compaction 引用随 JSONL 往返后压缩视图保持一致。 */
class ContextCompactorPersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun 摘要落盘后重载压缩视图一致() = runTest {
        val file = File(tempFolder.newFolder(), "compaction.jsonl")
        val store = TreeSessionStore("persist", file)
        val first = store.appendTurn("$T1_USER 第一轮", "$T1_ASSISTANT 第一轮回答")
        store.appendTurn("$T2_USER 第二轮", "$T2_ASSISTANT 第二轮回答")
        store.appendTurn("$T3_USER 第三轮", "$T3_ASSISTANT 第三轮回答")
        val compactor = ContextCompactor(RecordingSummaryEngine(listOf("摘要：$SUMMARY_ONE")))
        val compacted = compactor.compact(store, 1_000, usedTokens = 900) as CompactionResult.Compacted
        val expectedView = compactor.view(store)
        val summaryId = compacted.summaryNodeId
        store.close()

        val reopened = TreeSessionStore("persist", file)
        try {
            assertTrue(reopened.lastLoadReport.isClean)
            val view = compactor.view(reopened)
            assertEquals(expectedView.summaries, view.summaries)
            assertEquals(expectedView.history.map { it.content }, view.history.map { it.content })
            assertEquals("摘要：$SUMMARY_ONE", reopened.node(summaryId)?.content)
            assertEquals(
                compacted.ranges.single().coveredFrom,
                reopened.node(summaryId)?.compaction?.coveredFrom,
            )
            assertEquals(
                compacted.ranges.single().coveredTo,
                reopened.node(summaryId)?.compaction?.coveredTo,
            )
            assertEquals(first.first().id, reopened.node(summaryId)?.compaction?.coveredFrom)
        } finally {
            reopened.close()
        }
    }

    private companion object {
        const val T1_USER = "T1-USER"
        const val T1_ASSISTANT = "T1-ASSISTANT"
        const val T2_USER = "T2-USER"
        const val T2_ASSISTANT = "T2-ASSISTANT"
        const val T3_USER = "T3-USER"
        const val T3_ASSISTANT = "T3-ASSISTANT"
        const val SUMMARY_ONE = "S1-MARK"
    }
}
