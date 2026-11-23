package com.thatgfsj.qaa.feature.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Terminal 前端占位屏（工程骨架，Issue #9）。
 *
 * Shell 命令解析器 / 全中文 help / 三段式视图 / 实时统计栏均属后续议题；
 * 本模块禁止绕过 harness 直连引擎（职责表）。
 */
@Composable
fun TerminalPlaceholderScreen() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Text(text = "Terminal 前端占位")
        }
    }
}
