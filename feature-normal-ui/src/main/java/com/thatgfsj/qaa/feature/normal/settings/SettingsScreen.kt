package com.thatgfsj.qaa.feature.normal.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thatgfsj.qaa.core.harness.ContextWindow
import com.thatgfsj.qaa.core.harness.PromptAssembler

/**
 * 设置页（Issue #8 / #106）。
 *
 * 硬约束："切换至终端模式"仅暴露 [onSwitchToTerminal] 回调占位；
 * ui_mode 持久化写入与切换即立即重启 App 属 Issue #7，本期严禁实现。
 *
 * 上下文长度（#106）：Normal 侧只保存设置值、不触发引擎重载；立即生效走终端 /ctx。
 * 选项固定为 [ContextWindow.OPTIONS] 四档，默认 [ContextWindow.DEFAULT]。
 *
 * 系统提示词（#105）：多行编辑 + 保存 + 恢复默认，与终端 /system 写同一 DataStore key；
 * 提示词每轮组装，保存后下一轮生效，无需重载模型。草稿与已保存值分离，未点保存不落盘。
 */
@Composable
fun SettingsScreen(
    onSwitchToTerminal: () -> Unit,
    contextTokens: Int = ContextWindow.DEFAULT,
    onContextTokensChange: (Int) -> Unit = {},
    systemPrompt: String = PromptAssembler.DEFAULT_SYSTEM_PROMPT,
    onSystemPromptSave: (String) -> Unit = {},
    onSystemPromptReset: () -> Unit = {},
) {
    // 草稿与已保存值分离：输入不落盘，点「保存」才写入；外部（终端 /system）改写时重建草稿。
    var draft by remember(systemPrompt) { mutableStateOf(systemPrompt) }
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
                Text("终端模式", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "三段式 Shell 终端视图，适合键盘操作与快速调参。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSwitchToTerminal, modifier = Modifier.fillMaxWidth()) {
                    Text("切换至终端模式")
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
                Text("上下文长度（token）", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "模型载入时的上下文窗口，默认 32768。改动只保存设置，需重新载入模型后生效；" +
                        "立即生效请在终端执行 /ctx <档位>。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ContextWindow.OPTIONS.forEach { option ->
                        FilterChip(
                            selected = option == contextTokens,
                            onClick = { onContextTokensChange(option) },
                            label = { Text(option.toString()) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "提示：≥16384 的长上下文在 HTP/NPU 上可能触发 abort；建议先用 4096 / 8192 验证模型可用性。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        GroupTitle("系统提示词")
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("系统提示词", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(SYSTEM_PROMPT_PROTOCOL_WARNING, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    systemPromptStatusText(systemPrompt, draft),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text(PromptAssembler.DEFAULT_SYSTEM_PROMPT) },
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onSystemPromptSave(draft) },
                        enabled = isSystemPromptDirty(draft, systemPrompt),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存")
                    }
                    Button(
                        onClick = {
                            draft = PromptAssembler.DEFAULT_SYSTEM_PROMPT
                            onSystemPromptReset()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("恢复默认")
                    }
                }
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
