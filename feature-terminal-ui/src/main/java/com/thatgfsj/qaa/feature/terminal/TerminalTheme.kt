package com.thatgfsj.qaa.feature.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 终端色板（黑底浅字契约）：常量集中在 [TerminalColors]，供三段式各子组件直取。
 * 字体统一由各组件使用 FontFamily.Monospace（等宽字体契约）。
 */
object TerminalColors {
    /** 纯黑底。 */
    val Background = Color(0xFF000000)

    /** 顶部 / 底部分隔线。 */
    val BarDivider = Color(0xFF2E2E2E)

    /** 正文浅字。 */
    val TextPrimary = Color(0xFFD8D8D8)

    /** 标签字（[MODEL] / [CHAT] / [HYBRID]）。 */
    val TagText = Color(0xFFFFC66D)

    /** 「你 &lt;」前缀。 */
    val UserPrefix = Color(0xFF6FD3FF)

    /** 「AI &lt;」前缀与 NPU / Allow 成功色。 */
    val AiPrefix = Color(0xFF7CE38B)

    /** [TOOL] 确认区。 */
    val ToolText = Color(0xFFE6C07B)

    /** Deny / 危险（CPU 命中）。 */
    val DenyText = Color(0xFFFF6E6E)

    /** 分段竖线与次级提示。 */
    val BarDividerText = Color(0xFF6A6A6A)

    /** 快捷键按钮描边与次级提示。 */
    val HintText = Color(0xFF8A8A8A)
}

/** 终端主题：固定暗色，不随系统切换（极客终端契约）。 */
@Composable
fun TerminalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = TerminalColors.Background,
            surface = TerminalColors.Background,
            primary = TerminalColors.UserPrefix,
            secondary = TerminalColors.AiPrefix,
        ),
        content = content,
    )
}
