package com.thatgfsj.qaa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

/**
 * 入口占位 Activity（工程骨架，Issue #3）。
 *
 * 双前端启动路由（ui_mode 持久化 → NORMAL / TERMINAL 独立入口、切换即立即重启）
 * 属后续 Issue #10；:app 已预接 :feature-normal-ui / :feature-terminal-ui 依赖，
 * 本骨架仅保证链路可编译。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Text(text = "QAA 骨架就绪", fontSize = 24.sp)
                }
            }
        }
    }
}
