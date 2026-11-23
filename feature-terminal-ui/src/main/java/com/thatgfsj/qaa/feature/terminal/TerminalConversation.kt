package com.thatgfsj.qaa.feature.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 中部滚动区跟随容差：视口底部以内视为「在底部」，避免高频抖动。 */
private val FollowBottomThreshold = 48.dp

/**
 * 中部滚动区（Issue #9）：「你 &lt; 」/「AI &lt; 」前缀流式对话 + [TOOL] 确认区。
 *
 * - 等宽字体、黑底浅字；空会话时展示居中占位文案；
 * - followOutput=true 时自动滚动跟随流式输出；用户上滑离开底部自动暂停跟随，
 *   滑回底部自动恢复（经 [onFollowChanged] 上报，逻辑可 JVM 单测）；
 * - [TOOL] 确认区出现在对话末尾：Pending 展示 Allow / Deny 按钮（回调预留，
 *   确认流本体属 Issue #14），Resolved 回显用户选择。
 */
@Composable
fun TerminalConversation(
    state: TerminalUiState,
    onToolAllow: () -> Unit,
    onToolDeny: () -> Unit,
    onFollowChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val thresholdPx = with(LocalDensity.current) { FollowBottomThreshold.toPx() }

    // 列表是否贴近底部：用户离开 -> false（暂停跟随）；回到底部 -> true（恢复跟随）。
    val nearBottom by remember {
        derivedStateOf { listState.isNearBottom(thresholdPx) }
    }
    LaunchedEffect(nearBottom) { onFollowChanged(nearBottom) }

    // 流式输出跟随：末行 id / 文本长度或确认区变化时滚动到底部条目。
    val lastEntry = state.entries.lastOrNull()
    LaunchedEffect(lastEntry?.id, lastEntry?.text?.length, state.toolConfirm) {
        if (state.followOutput) {
            val targetIndex = listState.layoutInfo.totalItemsCount - 1
            if (targetIndex >= 0) listState.animateScrollToItem(targetIndex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalColors.Background),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(state.entries, key = { it.id }) { entry ->
                TerminalEntryRow(entry)
            }
            when (val tool = state.toolConfirm) {
                ToolConfirmState.Idle -> Unit
                is ToolConfirmState.Pending -> item(key = "tool-${tool.requestId}") {
                    PendingToolRow(tool, onToolAllow, onToolDeny)
                }
                is ToolConfirmState.Resolved -> item(key = "tool-${tool.requestId}") {
                    ResolvedToolRow(tool)
                }
            }
        }
        if (state.entries.isEmpty() && state.toolConfirm is ToolConfirmState.Idle) {
            Text(
                text = "会话为空，等待输入…",
                color = TerminalColors.TextPrimary.copy(alpha = 0.45f),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** 列表是否贴近底部（含容差）：离开即视为暂停跟随，回来即恢复。 */
private fun LazyListState.isNearBottom(thresholdPx: Float): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    if (last.index < info.totalItemsCount - 1) return false
    return last.offset + last.size <= info.viewportEndOffset + thresholdPx
}

/** 单行对话：前缀着色（你 / AI），正文浅字等宽。 */
@Composable
private fun TerminalEntryRow(entry: TerminalEntry) {
    val prefixColor = when (entry.role) {
        TerminalRole.USER -> TerminalColors.UserPrefix
        TerminalRole.AI -> TerminalColors.AiPrefix
    }
    Text(
        text = buildAnnotatedString {
            withStyle(style = SpanStyle(color = prefixColor)) {
                append(entry.role.displayPrefix)
            }
            append(entry.text)
        },
        color = TerminalColors.TextPrimary,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
    )
}

/** [TOOL] 待确认区：提示行 + Allow / Deny 两个终端风按钮（回调预留，Issue #14）。 */
@Composable
private fun PendingToolRow(
    tool: ToolConfirmState.Pending,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = tool.confirmText(),
            color = TerminalColors.ToolText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TerminalToolButton(text = "Allow", contentColor = TerminalColors.AiPrefix, onClick = onAllow)
            TerminalToolButton(text = "Deny", contentColor = TerminalColors.DenyText, onClick = onDeny)
        }
    }
}

@Composable
private fun TerminalToolButton(text: String, contentColor: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, contentColor),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(text = text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

/** [TOOL] 已确认行：Allow / Deny 结果回显。 */
@Composable
private fun ResolvedToolRow(tool: ToolConfirmState.Resolved) {
    Text(
        text = tool.resultText(),
        color = if (tool.allowed) TerminalColors.AiPrefix else TerminalColors.DenyText,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    )
}
