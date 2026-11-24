package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 稳定前缀口径单测（#106）：估算与压缩回执都加 prefixTokens。 */
class ContextCompactorPrefixTest {

    @Test
    fun 估算值随稳定前缀线性增加() {
        val store = TreeSessionStore("prefix")
        store.appendTurn(userText = "用户目标：整理 notes.txt。", assistantText = "已整理完毕。")
        val compactor = ContextCompactor(RecordingSummaryEngine())
        val base = compactor.estimateContextTokens(store)
        assertEquals(base + 120, compactor.estimateContextTokens(store, prefixTokens = 120))
        // 防御非法调用：负前缀按 0 处理
        assertEquals(base, compactor.estimateContextTokens(store, prefixTokens = -5))
    }

    @Test
    fun 压缩回执before与after使用同一前缀口径() = runTest {
        val store = TreeSessionStore("prefix-compact")
        repeat(3) { index ->
            store.appendTurn(
                userText = "第 $index 轮用户输入 " + fillerTokens(80),
                assistantText = "第 $index 轮助手输出 " + fillerTokens(80),
            )
        }
        val compactor = ContextCompactor(RecordingSummaryEngine())
        val prefix = 200
        val plainBefore = compactor.estimateContextTokens(store)
        val result = compactor.compact(
            store = store,
            contextBudget = 1_000,
            usedTokens = 900,
            prefixTokens = prefix,
        ) as CompactionResult.Compacted

        assertEquals(plainBefore + prefix, result.estimatedTokensBefore)
        assertEquals(
            compactor.estimateContextTokens(store, prefixTokens = prefix),
            result.estimatedTokensAfter,
        )
    }
}
