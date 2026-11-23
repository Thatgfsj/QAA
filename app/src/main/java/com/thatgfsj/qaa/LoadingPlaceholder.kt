package com.thatgfsj.qaa

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** 首帧 ui_mode 读取完成前的过渡占位（路由确定后立即被 Normal / Terminal 前端替换）。 */
@Composable
internal fun LoadingPlaceholder() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = "正在启动…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
