package com.thatgfsj.qaa.feature.normal.chat

import com.thatgfsj.qaa.feature.normal.model.ChatRole
import java.util.Locale

/** 工具卡片状态（#127）：执行链中每一步的可见状态。 */
enum class ToolSegmentStatus {
    WAITING_CONFIRM,
    RUNNING,
    SUCCESS,
    FAILED,
    DENIED,
    ;

    /** 中文状态标签。 */
    val label: String
        get() = when (this) {
            WAITING_CONFIRM -> "等待确认"
            RUNNING -> "执行中"
            SUCCESS -> "成功"
            FAILED -> "失败"
            DENIED -> "已拒绝"
        }

    /** 是否终态（成功 / 失败 / 拒绝）；等待确认与执行中还会继续变化。 */
    val isFinished: Boolean
        get() = this == SUCCESS || this == FAILED || this == DENIED
}

/** 内联确认操作条状态（#127）：决策后操作条收起、状态固化。 */
enum class ConfirmSegmentState { PENDING, ALLOWED, DENIED }

/**
 * 会话时间线段（#127）：Normal 会话页的唯一时间线模型。
 *
 * 推入语义为「同 id 覆盖、新 id 追加」的 upsert：同一段可反复推入后续状态
 * （工具卡片 等待确认 → 执行中 → 成功），UI 只按 id 渲染，不做状态回推。
 * 文本段 id 由 [ChatViewModel] 从 0 递增；工具 / 确认 / 错误段由装配层从
 * [CHAT_SEGMENT_AGENT_ID_BASE] 起分配，两侧互不冲突。
 */
sealed interface ChatSegment {

    val id: Long

    /** 文本气泡（用户输入 / 助手可见文本，生成中逐 token 增长）。 */
    data class Text(
        override val id: Long,
        val role: ChatRole,
        val content: String,
    ) : ChatSegment

    /** 工具调用卡片：工具名 + 原始参数 + 状态 + 耗时 + 结果摘要。 */
    data class ToolCall(
        override val id: Long,
        val name: String,
        val arguments: String,
        val status: ToolSegmentStatus,
        val durationMs: Long? = null,
        val result: String? = null,
    ) : ChatSegment

    /** 确认段：挂起时展示「允许 / 拒绝」，决策后仅留状态。 */
    data class Confirm(
        override val id: Long,
        val toolSegmentId: Long,
        val description: String,
        val state: ConfirmSegmentState = ConfirmSegmentState.PENDING,
    ) : ChatSegment

    /** 轮次级错误（#127 需求 3）：[retryable] 为 true 时 UI 提供「重试」。 */
    data class Error(
        override val id: Long,
        val message: String,
        val retryable: Boolean = true,
    ) : ChatSegment
}

/** 装配层段 id 起点：远高于 ViewModel 文本段 id，保证 upsert 不串段。 */
const val CHAT_SEGMENT_AGENT_ID_BASE: Long = 1L shl 40

/** 渲染单元（#127 需求 1）：连续工具步聚合为一条执行链。 */
sealed interface ChatTimelineEntry {

    data class Text(val segment: ChatSegment.Text) : ChatTimelineEntry

    /** 执行链：[steps] 为连续工具段；[confirm] 为链内确认条（PENDING 时渲染操作条）。 */
    data class ToolChain(
        val steps: List<ChatSegment.ToolCall>,
        val confirm: ChatSegment.Confirm?,
    ) : ChatTimelineEntry

    data class Error(val segment: ChatSegment.Error) : ChatTimelineEntry
}

/**
 * 段列表 → 渲染单元（纯函数）：连续工具段合并成一条执行链，文本段与错误段
 * 各自成块并切断执行链（工具→工具→文本→工具 得到 链2步 / 文本 / 链1步）。
 */
fun chatTimeline(segments: List<ChatSegment>): List<ChatTimelineEntry> {
    val entries = mutableListOf<ChatTimelineEntry>()
    val steps = mutableListOf<ChatSegment.ToolCall>()
    var confirm: ChatSegment.Confirm? = null

    fun flushChain() {
        if (steps.isNotEmpty() || confirm != null) {
            entries += ChatTimelineEntry.ToolChain(steps.toList(), confirm)
        }
        steps.clear()
        confirm = null
    }

    segments.forEach { segment ->
        when (segment) {
            is ChatSegment.Text -> {
                flushChain()
                entries += ChatTimelineEntry.Text(segment)
            }
            is ChatSegment.Error -> {
                flushChain()
                entries += ChatTimelineEntry.Error(segment)
            }
            is ChatSegment.ToolCall -> steps += segment
            is ChatSegment.Confirm -> confirm = segment
        }
    }
    flushChain()
    return entries
}

/** 执行链标题：多步显示步数，单步直接标「工具调用」，仅确认条时标「工具确认」。 */
fun ChatTimelineEntry.ToolChain.titleText(): String = when {
    steps.size > 1 -> "执行链 · ${steps.size} 步"
    steps.size == 1 -> "工具调用"
    else -> "工具确认"
}

/** 工具耗时文案：< 1s 用毫秒，否则保留一位小数秒；未计时返回 null。 */
fun ChatSegment.ToolCall.durationText(): String? {
    val millis = durationMs ?: return null
    return if (millis >= 1000) {
        "%.1f s".format(Locale.ROOT, millis / 1000.0)
    } else {
        "${millis} ms"
    }
}

/** 列表 key：同一段状态更新后 key 不变，避免 LazyColumn 丢失展开态。 */
fun ChatTimelineEntry.listKey(): String = when (this) {
    is ChatTimelineEntry.Text -> "text-${segment.id}"
    is ChatTimelineEntry.Error -> "error-${segment.id}"
    is ChatTimelineEntry.ToolChain -> "chain-${steps.firstOrNull()?.id ?: confirm?.id ?: 0L}"
}
