package com.thatgfsj.qaa.core.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上下文窗口预算单测（#106）：默认档、档位合法性、四档预算与公式边界。 */
class ContextWindowTest {

    @Test
    fun 默认档为32k且档位固定四档() {
        assertEquals(32768, ContextWindow.DEFAULT)
        assertEquals(listOf(4096, 8192, 16384, 32768), ContextWindow.OPTIONS)
    }

    @Test
    fun 档位合法性只认四档() {
        ContextWindow.OPTIONS.forEach { tier -> assertTrue("合法档 $tier", ContextWindow.isValid(tier)) }
        listOf(-1, 0, 2048, 3072, 4095, 4097, 30000, 65536).forEach { tier ->
            assertFalse("非法档 $tier", ContextWindow.isValid(tier))
        }
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
    fun 预算随档位单调递增且恒为正() {
        val budgets = ContextWindow.OPTIONS.map { ContextWindow.promptBudget(it) }
        assertEquals(budgets.sorted(), budgets)
        assertTrue(budgets.all { it > 0 })
        assertEquals(budgets.size, budgets.distinct().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun 非正档位计算预算直接拒绝() {
        ContextWindow.promptBudget(0)
    }
}
