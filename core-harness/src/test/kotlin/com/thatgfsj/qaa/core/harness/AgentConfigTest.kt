package com.thatgfsj.qaa.core.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 验证 :core-harness 可脱离 Android 环境直接 JVM 单测（Issue #3 验收项）。
 */
class AgentConfigTest {
    @Test
    fun defaultsFollowArchitectureContract() {
        val config = AgentConfig()
        assertFalse(config.yoloMode)
        assertEquals(12, config.maxToolSteps)
        assertEquals(0.8f, config.compactThresholdRatio)
    }
}
