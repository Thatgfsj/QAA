package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ContextCompactor 分叉安全性（设计文档 6.4、附录 A.6 C4/C5）：
 * 摘要范围只在包含该 summary 节点的活动分支上生效，分叉后的新旧分支互不影响。
 */
class ContextCompactorForkTest {

    private suspend fun storeWithCompactedHistory(): CompactedFixture {
        val store = TreeSessionStore("fork")
        val first = store.appendTurn("$T1_USER 第一轮", "$T1_ASSISTANT 第一轮回答")
        val secondUser = store.appendTurn("$T2_USER 第二轮", "$T2_ASSISTANT 第二轮回答").first()
        store.appendTurn("$T3_USER 第三轮", "$T3_ASSISTANT 第三轮回答")
        val engine = RecordingSummaryEngine(listOf("摘要：$SUMMARY_ONE"))
        val compactor = ContextCompactor(engine)
        val compacted = compactor.compact(store, 1_000, usedTokens = 900) as CompactionResult.Compacted
        return CompactedFixture(store, compactor, first, secondUser, compacted)
    }

    @Test
    fun 分叉后新分支不继承旧摘要的覆盖范围() = runTest {
        val fixture = storeWithCompactedHistory()
        val store = fixture.store
        val compactor = fixture.compactor
        val oldLeaf = store.activeLeafId!!
        assertEquals(fixture.compacted.summaryNodeId, oldLeaf)

        assertTrue(store.fork(fixture.secondUser.id) is ForkResult.Success)
        val newView = compactor.view(store)
        assertTrue(newView.summaries.isEmpty())
        assertTrue(newView.history.any { it.content == fixture.firstTurn.first().content })
        assertTrue(newView.history.any { it.content == fixture.firstTurn.last().content })

        val oldView = compactor.view(store, oldLeaf)
        assertEquals(listOf("摘要：$SUMMARY_ONE"), oldView.summaries)
        assertFalse(oldView.history.any { it.content == fixture.firstTurn.first().content })
    }

    @Test
    fun 分叉点位于覆盖链段内时旧摘要不生效() = runTest {
        val fixture = storeWithCompactedHistory()
        val store = fixture.store
        val oldLeaf = store.activeLeafId!!

        assertTrue(store.fork(fixture.firstTurn.last().id) is ForkResult.Success)
        val view = fixture.compactor.view(store)
        assertTrue(view.summaries.isEmpty())
        assertEquals(
            listOf(fixture.firstTurn.first().content, fixture.firstTurn.last().content),
            view.history.map { it.content },
        )

        // 旧分支完整保留摘要节点。
        assertEquals(listOf("摘要：$SUMMARY_ONE"), fixture.compactor.view(store, oldLeaf).summaries)
    }

    @Test
    fun 新分支写入不影响旧分支压缩视图() = runTest {
        val fixture = storeWithCompactedHistory()
        val store = fixture.store
        val oldLeaf = store.activeLeafId!!

        assertTrue(store.fork(fixture.secondUser.id) is ForkResult.Success)
        store.appendTurn("$T4_USER 新分支", "$T4_ASSISTANT 新分支回答")

        assertEquals(1, fixture.compactor.view(store, oldLeaf).summaries.size)
        assertTrue(fixture.compactor.view(store).summaries.isEmpty())
        assertEquals(oldLeaf, store.branchTo(oldLeaf).last().id)
        assertEquals("$T4_ASSISTANT 新分支回答", store.branchTo().last().content)
    }

    private data class CompactedFixture(
        val store: TreeSessionStore,
        val compactor: ContextCompactor,
        val firstTurn: List<SessionNode>,
        val secondUser: SessionNode,
        val compacted: CompactionResult.Compacted,
    )

    private companion object {
        const val T1_USER = "T1-USER"
        const val T1_ASSISTANT = "T1-ASSISTANT"
        const val T2_USER = "T2-USER"
        const val T2_ASSISTANT = "T2-ASSISTANT"
        const val T3_USER = "T3-USER"
        const val T3_ASSISTANT = "T3-ASSISTANT"
        const val T4_USER = "T4-USER"
        const val T4_ASSISTANT = "T4-ASSISTANT"
        const val SUMMARY_ONE = "S1-MARK"
    }
}
