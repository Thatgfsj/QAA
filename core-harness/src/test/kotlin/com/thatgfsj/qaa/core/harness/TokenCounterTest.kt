package com.thatgfsj.qaa.core.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TokenCounter JVM 单测（设计附录 A.6 C8：实测优先、估算兜底取 max）。 */
class TokenCounterTest {

    private val counter = HeuristicTokenCounter()

    @Test
    fun C8_CJK按一点六字每token估算() {
        assertEquals(100, counter.count("数".repeat(160)))
        assertEquals(63, counter.count("数".repeat(100)))
    }

    @Test
    fun C8_ASCII按四字符每token估算() {
        assertEquals(100, counter.count("a".repeat(400)))
    }

    @Test
    fun C8_混合文本按字符类别累计() {
        assertEquals(13, counter.count("汉字汉字汉字汉字" + "a".repeat(32)))
    }

    @Test
    fun C8_空文本为零且非空文本至少一token() {
        assertEquals(0, counter.count(""))
        assertEquals(1, counter.count(" "))
    }

    @Test
    fun C8_代理对按码点计数() {
        assertEquals(1, counter.count("😀"))
        assertEquals(2, counter.count("😀😀"))
    }

    @Test
    fun C8_消息计数包含逐条固定开销() {
        val messages = listOf(
            PromptMessage(PromptRole.SYSTEM, "数".repeat(160)),
            PromptMessage(PromptRole.USER, "a".repeat(400)),
        )
        assertEquals(100 + 4 + 100 + 4, counter.countMessages(messages))
    }

    @Test
    fun C8_实测优先且与估算取最大值() {
        val messages = listOf(PromptMessage(PromptRole.USER, "数".repeat(160)))
        val estimate = 100 + 4

        val measured = counter.usage(messages, measuredPromptTokens = 5_000)
        assertTrue(measured.isMeasured)
        assertEquals(5_000, measured.effectiveTokens)

        val estimatedOnly = counter.usage(messages)
        assertFalse(estimatedOnly.isMeasured)
        assertEquals(estimate, estimatedOnly.effectiveTokens)

        // 实测低于估算时取估算，避免低估。
        val estimateWins = counter.usage(messages, measuredPromptTokens = 10)
        assertEquals(estimate, estimateWins.effectiveTokens)
    }

    @Test
    fun C8_占用率按有效token计算() {
        val usage = counter.usage(
            listOf(PromptMessage(PromptRole.USER, "a".repeat(396))),
            measuredPromptTokens = 104,
        )
        assertEquals(0.52f, usage.ratio(200), 0.0001f)
    }
}
