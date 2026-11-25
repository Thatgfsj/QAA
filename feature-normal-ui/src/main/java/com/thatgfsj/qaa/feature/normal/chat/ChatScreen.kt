package com.thatgfsj.qaa.feature.normal.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thatgfsj.qaa.feature.normal.model.ChatRole

/**
 * 流式会话页（Issue #8 → #127 · 段模型与工具卡片）：消息气泡 + 工具执行链卡片 + 指标条。
 *
 * 渲染只依赖 [ChatViewModel.segments]；连续工具段由 [chatTimeline] 聚合成执行链，
 * 卡片参数 / 结果按需展开且展开态按段 id 记忆（状态更新不丢展开态）。确认交互仍走
 * NormalApp 顶层兜底对话框，内联确认 / 停止 / 重试属 #127 B 段。
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val segments by viewModel.segments.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val timeline = chatTimeline(segments)

    // 时间线增长或末条内容变化（流式追加）时滚动到底部。
    LaunchedEffect(timeline.size, segments.lastOrNull()) {
        if (timeline.isNotEmpty()) listState.animateScrollToItem(timeline.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(timeline, key = { it.listKey() }) { entry ->
                when (entry) {
                    is ChatTimelineEntry.Text -> MessageBubble(entry.segment)
                    is ChatTimelineEntry.ToolChain -> ToolChainCard(entry)
                    is ChatTimelineEntry.Error -> ErrorRow(entry.segment)
                }
            }
        }
        Text(
            text = metrics.metricsBarText(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息…") },
                enabled = !isGenerating,
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.send(input)
                    input = ""
                },
                enabled = input.isNotBlank() && !isGenerating,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                Spacer(Modifier.width(4.dp))
                Text("发送")
            }
        }
    }
}

/** 文本气泡：用户右侧高亮、助手左侧中性底色；助手占位空串显示省略号。 */
@Composable
private fun MessageBubble(segment: ChatSegment.Text) {
    val isUser = segment.role == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = if (isUser) segment.content else segment.content.ifEmpty { "…" },
                modifier = Modifier.padding(10.dp),
                color = if (isUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** 执行链卡片：多步合并展示，每步独立状态 / 耗时 / 参数 / 结果。 */
@Composable
private fun ToolChainCard(chain: ChatTimelineEntry.ToolChain) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = chain.titleText(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            chain.steps.forEach { step -> ToolStepRow(step) }
        }
    }
}

/** 单步工具行：名称 + 状态 + 耗时 +（可展开）参数与结果摘要。 */
@Composable
private fun ToolStepRow(step: ChatSegment.ToolCall) {
    var showArguments by rememberSaveable(step.id) { mutableStateOf(false) }
    var showResult by rememberSaveable("${step.id}-result") { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = step.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            step.durationText()?.let { duration ->
                Text(
                    text = duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
            }
            StatusChip(step.status)
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            if (step.arguments.isNotBlank()) {
                TextButton(onClick = { showArguments = !showArguments }) {
                    Text(if (showArguments) "收起参数" else "参数", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (!step.result.isNullOrBlank()) {
                TextButton(onClick = { showResult = !showResult }) {
                    Text(if (showResult) "收起结果" else "结果", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (showArguments) {
            MonoBlock(step.arguments)
        }
        if (showResult) {
            step.result?.let { MonoBlock(it) }
        }
    }
}

/** 状态小标签：等待确认 / 执行中 / 成功 / 失败 / 已拒绝。 */
@Composable
private fun StatusChip(status: ToolSegmentStatus) {
    val color = when (status) {
        ToolSegmentStatus.WAITING_CONFIRM -> MaterialTheme.colorScheme.tertiary
        ToolSegmentStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ToolSegmentStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        ToolSegmentStatus.FAILED -> MaterialTheme.colorScheme.error
        ToolSegmentStatus.DENIED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = status.label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

/** 等宽代码块：参数与工具结果摘要。 */
@Composable
private fun MonoBlock(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** 错误段：失败原因提示。 */
@Composable
private fun ErrorRow(segment: ChatSegment.Error) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = segment.message,
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
