package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.ConfirmationDecision
import com.thatgfsj.qaa.core.harness.ConfirmationGate
import com.thatgfsj.qaa.core.harness.ToolCall
import com.thatgfsj.qaa.core.harness.ToolExecutorGateway
import com.thatgfsj.qaa.core.harness.ToolOutcome
import com.thatgfsj.qaa.core.harness.ToolOutcomeStatus
import com.thatgfsj.qaa.core.harness.summarizeToolCall
import com.thatgfsj.qaa.feature.normal.chat.CHAT_SEGMENT_AGENT_ID_BASE
import com.thatgfsj.qaa.feature.normal.chat.ChatSegment
import com.thatgfsj.qaa.feature.normal.chat.ConfirmSegmentState
import com.thatgfsj.qaa.feature.normal.chat.ToolSegmentStatus
import com.thatgfsj.qaa.feature.normal.confirm.NormalGate
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Normal 会话页时间线桥（#127）：把 harness 的「确认挂起 / 工具执行 / 轮次错误」
 * 映射成 feature 模块的 [ChatSegment] 段更新流（同 id 覆盖、新 id 追加）。
 *
 * 确认挂起 → 决策 → 执行 → 结束复用同一张工具卡片，UI 只做 upsert；id 从
 * [CHAT_SEGMENT_AGENT_ID_BASE] 起，与 ViewModel 文本段 id 不冲突。确认门与工具网关
 * 都在轮次协程内调用（turnMutex 串行化），无需加锁；无 replay 的 SharedFlow 在
 * Normal 页未收集时直接丢弃，不会串到下一轮。
 */
class NormalTurnBridge {

    private val _segments = MutableSharedFlow<ChatSegment>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 段更新流（Normal 会话页唯一消费方）。 */
    val segments: SharedFlow<ChatSegment> = _segments.asSharedFlow()

    private var nextId = CHAT_SEGMENT_AGENT_ID_BASE

    /** 等待确认的工具卡片与确认条（决定后清空）。 */
    private var pendingTool: ChatSegment.ToolCall? = null
    private var pendingConfirm: ChatSegment.Confirm? = null

    /** 正在执行的工具卡片（执行结束 / 轮次中止时收口）。 */
    private var runningTool: ChatSegment.ToolCall? = null

    /** 新一轮开始：清掉上轮遗留的挂起状态（轮次异常退出时防御性重置）。 */
    fun beginTurn() {
        pendingTool = null
        pendingConfirm = null
        runningTool = null
    }

    /** 确认门挂起：先出「等待确认」卡片，再出内联操作条（同一工具 id）。 */
    fun toolPending(call: ToolCall) {
        val tool = ChatSegment.ToolCall(
            id = nextId++,
            name = call.tool,
            arguments = call.argsJson,
            status = ToolSegmentStatus.WAITING_CONFIRM,
        )
        pendingTool = tool
        emit(tool)
        val confirm = ChatSegment.Confirm(
            id = nextId++,
            toolSegmentId = tool.id,
            description = summarizeToolCall(call),
            state = ConfirmSegmentState.PENDING,
        )
        pendingConfirm = confirm
        emit(confirm)
    }

    /** 确认门决策：操作条固化；允许 → 卡片转「执行中」，拒绝 → 卡片转「已拒绝」。 */
    fun confirmResolved(allowed: Boolean) {
        val tool = pendingTool
        val confirm = pendingConfirm
        pendingTool = null
        pendingConfirm = null
        if (tool == null || confirm == null) return
        emit(confirm.copy(state = if (allowed) ConfirmSegmentState.ALLOWED else ConfirmSegmentState.DENIED))
        if (allowed) {
            runningTool = tool
        } else {
            emit(tool.copy(status = ToolSegmentStatus.DENIED))
        }
    }

    /** 工具开始执行（无需确认的工具直接开新卡片；需确认的复用等待中的卡片）。 */
    fun toolStarted(call: ToolCall) {
        val tool = runningTool ?: ChatSegment.ToolCall(
            id = nextId++,
            name = call.tool,
            arguments = call.argsJson,
            status = ToolSegmentStatus.RUNNING,
        )
        runningTool = tool
        emit(tool.copy(status = ToolSegmentStatus.RUNNING))
    }

    /** 工具执行结束：写回状态、耗时与结果摘要。 */
    fun toolFinished(outcome: ToolOutcome) {
        val tool = runningTool ?: return
        runningTool = null
        emit(
            tool.copy(
                status = when (outcome.status) {
                    ToolOutcomeStatus.SUCCESS -> ToolSegmentStatus.SUCCESS
                    ToolOutcomeStatus.FAILURE -> ToolSegmentStatus.FAILED
                    ToolOutcomeStatus.DENIED -> ToolSegmentStatus.DENIED
                },
                durationMs = outcome.durationMs,
                result = summarizeToolResult(outcome),
            ),
        )
    }

    /** 轮次错误（引擎异常 / 装配层失败）：推一条可重试的错误段。 */
    fun turnFailed(message: String) {
        emit(ChatSegment.Error(nextId++, message.trim().ifEmpty { "本轮执行失败" }, retryable = true))
    }

    /** 轮次收口：把仍停在等待确认 / 执行中的卡片标记失败，避免异常退出后卡在中间态。 */
    fun endTurn() {
        pendingConfirm?.let { emit(it.copy(state = ConfirmSegmentState.DENIED)) }
        val tool = runningTool ?: pendingTool
        if (tool != null && !tool.status.isFinished) {
            emit(
                tool.copy(
                    status = ToolSegmentStatus.FAILED,
                    result = ABORTED_RESULT,
                ),
            )
        }
        pendingTool = null
        pendingConfirm = null
        runningTool = null
    }

    private fun emit(segment: ChatSegment) {
        _segments.tryEmit(segment)
    }

    private companion object {
        const val BUFFER_CAPACITY = 64

        /** 中止说明：卡片停在中间态时的终态结果文案。 */
        const val ABORTED_RESULT = "本轮已中止，工具未返回结果。"
    }
}

/**
 * 工具网关装饰器（#127）：不改 core-tools / AgentLoop，把每次工具执行的开始与结束
 * 投递给 [NormalTurnBridge]；耗时在此测量（AgentLoop 的 durationMs 调用返回后才填充）。
 * 只旁路观察，异常原样抛回 AgentLoop。
 */
class NormalToolObserver(
    private val delegate: ToolExecutorGateway,
    private val bridge: NormalTurnBridge,
) : ToolExecutorGateway {

    override suspend fun requiresConfirmation(toolName: String): Boolean =
        delegate.requiresConfirmation(toolName)

    override suspend fun execute(call: ToolCall, stepIndex: Int): ToolOutcome {
        bridge.toolStarted(call)
        val startedNanos = System.nanoTime()
        val outcome = try {
            delegate.execute(call, stepIndex)
        } catch (error: Exception) {
            bridge.toolFinished(
                ToolOutcome(
                    status = ToolOutcomeStatus.FAILURE,
                    output = error.message ?: error::class.simpleName.orEmpty(),
                    durationMs = elapsedMs(startedNanos),
                ),
            )
            throw error
        }
        bridge.toolFinished(outcome.copy(durationMs = outcome.durationMs ?: elapsedMs(startedNanos)))
        return outcome
    }

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** 工具结果摘要（纯函数，单测覆盖）：压成单行、截断超长结果，拒绝/失败附错误码。 */
internal fun summarizeToolResult(outcome: ToolOutcome, maxLength: Int = RESULT_MAX_LENGTH): String {
    val body = outcome.output.replace('\n', ' ').replace('\r', ' ').trim()
    val code = outcome.errorCode?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
    return (code + abbreviateForCard(body, maxLength)).ifEmpty { "（无输出）" }
}

/** 卡片摘要截断：超长时保留前 [maxLength] 字符并加省略号。 */
internal fun abbreviateForCard(text: String, maxLength: Int = RESULT_MAX_LENGTH): String =
    if (text.length <= maxLength) text else text.take(maxLength) + "…"

/** 工具结果摘要上限：卡片内可展开查看，默认展示前 400 字符。 */
internal const val RESULT_MAX_LENGTH = 400

/**
 * 确认门委派（#127 起从 AgentRuntime 迁出，便于运行装配文件控行数）：
 * 运行时构造早于 UI，确认门由具体前端在组合期注入
 * （终端 [com.thatgfsj.qaa.feature.terminal.TerminalUiGate] / Normal NormalGate）。
 * 未注入时按拒绝处理，绝不静默放行写类工具。
 *
 * [onPending] / [onResolved] 为 Normal 页时间线回调：挂起时出「等待确认」卡片与内联操作条，
 * 决策后卡片转「执行中 / 已拒绝」；终端前端不消费这两个回调，行为不变。
 */
internal class DelegatingConfirmationGate(
    private val onPending: (ToolCall) -> Unit = {},
    private val onResolved: (Boolean) -> Unit = {},
) : ConfirmationGate {

    @Volatile
    private var delegate: ConfirmationGate? = null

    fun bind(gate: ConfirmationGate) {
        delegate = gate
    }

    /** 内联确认「允许」：仅 Normal 端确认门支持，其余前端返回 false。 */
    fun allowPending(): Boolean {
        val gate = delegate as? NormalGate ?: return false
        gate.allow()
        return true
    }

    /** 内联确认「拒绝」：仅 Normal 端确认门支持，其余前端返回 false。 */
    fun denyPending(): Boolean {
        val gate = delegate as? NormalGate ?: return false
        gate.deny()
        return true
    }

    override suspend fun awaitConfirmation(call: ToolCall): ConfirmationDecision {
        onPending(call)
        val decision = delegate?.awaitConfirmation(call) ?: ConfirmationDecision.DENY
        onResolved(decision == ConfirmationDecision.ALLOW)
        AppLog.confirmDecision(
            description = summarizeToolCall(call),
            allowed = decision == ConfirmationDecision.ALLOW,
        )
        return decision
    }
}
