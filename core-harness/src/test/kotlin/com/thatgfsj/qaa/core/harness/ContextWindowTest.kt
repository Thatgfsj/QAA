package com.thatgfsj.qaa.core.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上下文窗口预算单测（#106 / #112）：默认值、范围合法性、自由输入解析、预算公式边界。 */
class ContextWindowTest {

    @Test
    fun 默认值为32k且建议档位保留四档() {
        assertEquals(32768, ContextWindow.DEFAULT)
        assertEquals(listOf(4096, 8192, 16384, 32768), ContextWindow.OPTIONS)
    }

    @Test
    fun 合法范围覆盖任意整数() {
        listOf(1024, 3072, 4096, 5000, 8192, 20480, 65536, 1048576).forEach { value ->
            assertTrue("合法值 $value", ContextWindow.isAllowed(value))
        }
        listOf(-1, 0, 1023, 1048577, Int.MAX_VALUE).forEach { value ->
            assertFalse("非法值 $value", ContextWindow.isAllowed(value))
        }
    }

    @Test
    fun 后缀解析支持k与m() {
        assertEquals(20480, ContextWindow.parseInput("20k"))
        assertEquals(20480, ContextWindow.parseInput("20K"))
        assertEquals(65536, ContextWindow.parseInput("64k"))
        assertEquals(1048576, ContextWindow.parseInput("1m"))
        assertEquals(1048576, ContextWindow.parseInput("1M"))
        assertEquals(8192, ContextWindow.parseInput("8192"))
        assertEquals(1024, ContextWindow.parseInput(" 1 k "))
        assertEquals(3072, ContextWindow.parseInput("3\t072"))
    }

    @Test
    fun 后缀解析拒绝非法输入() {
        listOf("", " ", "0", "-1", "abc", "1g", "1m1", "k", "20 kb", "1024 ", "1023", "2m")
            .filter { it != "1024 " }
            .forEach { raw -> assertNull("应拒绝：$raw", ContextWindow.parseInput(raw)) }
        assertEquals(1024, ContextWindow.parseInput("1024 "))
    }

    @Test
    fun 输出预留与安全边界按公式计算() {
        assertEquals(512, ContextWindow.outputReserve(4096))
        assertEquals(512, ContextWindow.outputReserve(32768))
        // safetyMargin = max(128, ceil(0.02 * nCtx))
        assertEquals(128, ContextWindow.safetyMargin(4096))
        assertEquals(164, ContextWindow.safetyMargin(8192))
        assertEquals(328, ContextWindow.safetyMargin(16384))
        assertEquals(656, ContextWindow.safetyMargin(32768))
    }

    @Test
    fun 四档提示预算按公式派生() {
        assertEquals(3456, ContextWindow.promptBudget(4096))
        assertEquals(7516, ContextWindow.promptBudget(8192))
        assertEquals(15544, ContextWindow.promptBudget(16384))
        assertEquals(31600, ContextWindow.promptBudget(32768))
        assertEquals(ContextWindow.promptBudget(8192), ContextWindow.budgetFor(8192))
    }

    @Test
    fun 压缩水位常量与旧配置一致() {
        assertEquals(0.8f, ContextWindow.COMPACT_TRIGGER_RATIO, 1e-6f)
        assertEquals(0.5f, ContextWindow.COMPACT_TARGET_RATIO, 1e-6f)
        // 触发点 = 0.8 * 预算、目标点 = 0.5 * 预算（整数下取整由调用方按浮点比较）
        assertTrue(0.8f * ContextWindow.promptBudget(4096) < ContextWindow.promptBudget(4096))
    }

    @Test
    fun 预算随建议档位单调递增且恒为正() {
        val budgets = ContextWindow.OPTIONS.map { ContextWindow.promptBudget(it) }
        assertEquals(budgets.sorted(), budgets)
        assertTrue(budgets.all { it > 0 })
        assertEquals(budgets.size, budgets.distinct().size)
    }
}
