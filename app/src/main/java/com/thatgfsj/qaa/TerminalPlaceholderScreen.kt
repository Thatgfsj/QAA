package com.thatgfsj.qaa

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

/**
 * Terminal 前端占位视图（Issue #7 路由临时终点）。
 * 三段式 Shell 视图与命令解析（如 start window）由 Issue #9/#10 交付后替换，此处不实现。
 */
@Composable
fun TerminalPlaceholderScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "极客终端模式 · 界面建设中",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 20.sp,
                )
                Text(
                    text = "三段式终端视图接入后启用。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 首帧 ui_mode 读取完成前的过渡占位。 */
@Composable
internal fun LoadingPlaceholder() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = "正在启动…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
