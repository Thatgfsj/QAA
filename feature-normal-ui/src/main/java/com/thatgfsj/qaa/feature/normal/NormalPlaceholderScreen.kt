package com.thatgfsj.qaa.feature.normal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Normal 前端占位屏（工程骨架，Issue #8）。
 *
 * 会话页 / 模型管理页 / 设置页 / 后端状态栏均属后续议题；
 * 本模块禁止绕过 harness 直连引擎（职责表）。
 */
@Composable
fun NormalPlaceholderScreen() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Text(text = "Normal 前端占位")
        }
    }
}
