package com.thatgfsj.qaa.feature.normal.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 设置页（Issue #8）。
 *
 * 硬约束："切换至极客终端模式"仅暴露 [onSwitchToTerminal] 回调占位；
 * ui_mode 持久化写入与切换即立即重启 App 属 Issue #7，本期严禁实现。
 */
@Composable
fun SettingsScreen(onSwitchToTerminal: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GroupTitle("前端模式")
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("极客终端模式", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "三段式 Shell 终端视图，适合键盘操作与快速调参。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSwitchToTerminal, modifier = Modifier.fillMaxWidth()) {
                    Text("切换至极客终端模式")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "提示：切换后需立即重启 App 生效（生效链路属后续议题）。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        GroupTitle("通用")
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("更多设置项随后续议题开放。", style = MaterialTheme.typography.bodySmall)
            }
        }

        GroupTitle("关于")
        Text(
            "QAA · Normal 前端骨架（本期为占位实现）",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun GroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
