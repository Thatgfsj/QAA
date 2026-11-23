package com.thatgfsj.qaa

/**
 * 双前端模式（docs/ARCHITECTURE.md 第 6 节）：
 * ui_mode 经 DataStore 持久化，启动时由 MainActivity 读取并路由（Issue #7）。
 */
enum class UiMode(val raw: Int) {
    NORMAL(0),
    TERMINAL(1);

    companion object {
        /** 未知存储值回退 NORMAL，保证首次安装与脏数据可启动。 */
        fun fromRaw(raw: Int): UiMode = entries.firstOrNull { it.raw == raw } ?: NORMAL
    }
}

/** 启动路由决策（纯函数，可 JVM 单测）。 */
sealed interface StartRoute {
    data object Normal : StartRoute
    data object Terminal : StartRoute
}

fun resolveStartRoute(mode: UiMode): StartRoute = when (mode) {
    UiMode.NORMAL -> StartRoute.Normal
    UiMode.TERMINAL -> StartRoute.Terminal
}
