package com.thatgfsj.qaa.feature.normal.confirm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * 中文工具确认对话框（设计文档 3.6）：「允许执行工具？」+ 工具描述
 * （工具名 + 关键参数摘要，文案由 :core-harness 统一生成）+「允许 / 拒绝」。
 *
 * 无自动超时：点外部与返回键均不关闭，必须显式选择；对话框由 :app 装配层
 * 挂在 NormalApp 顶层，切换页面不影响未决的确认。
 */
@Composable
fun NormalToolConfirmDialog(
    pending: PendingToolConfirmation?,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    if (pending == null) return
    AlertDialog(
        onDismissRequest = { /* 必须显式选择：无自动超时（设计文档 3.6） */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text("允许执行工具？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("模型请求执行以下工具，确认后才会真正执行：")
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = pending.description,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    "拒绝后模型会收到「已拒绝执行」的结果并继续对话。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("允许") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("拒绝") } },
    )
}
