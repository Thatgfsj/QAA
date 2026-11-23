package com.thatgfsj.qaa

import org.junit.Assert.assertEquals
import org.junit.Test

/** 路由决策与 UiMode 存取映射纯函数 JVM 单测（Issue #7）。 */
class UiModeTest {

    @Test
    fun fromRaw_合法值往返() {
        for (mode in UiMode.entries) {
            assertEquals(mode, UiMode.fromRaw(mode.raw))
        }
    }

    @Test
    fun fromRaw_未知值回退NORMAL() {
        assertEquals(UiMode.NORMAL, UiMode.fromRaw(raw = -1))
        assertEquals(UiMode.NORMAL, UiMode.fromRaw(raw = 99))
    }

    @Test
    fun 路由决策_NORMAL路由Normal前端() {
        assertEquals(StartRoute.Normal, resolveStartRoute(UiMode.NORMAL))
    }

    @Test
    fun 路由决策_TERMINAL路由Terminal前端() {
        assertEquals(StartRoute.Terminal, resolveStartRoute(UiMode.TERMINAL))
    }
}
