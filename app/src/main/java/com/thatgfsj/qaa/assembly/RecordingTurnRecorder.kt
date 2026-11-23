package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.CompactedView
import com.thatgfsj.qaa.core.harness.GenerationMetrics
import com.thatgfsj.qaa.core.harness.JsonlTurnRecorder
import com.thatgfsj.qaa.core.harness.ParseOutcome
import com.thatgfsj.qaa.core.harness.ToolCall
import com.thatgfsj.qaa.core.harness.ToolCallParser
import com.thatgfsj.qaa.core.harness.TurnNode
import com.thatgfsj.qaa.core.harness.TurnRecorder
import com.thatgfsj.qaa.core.harness.TurnRole
import com.thatgfsj.qaa.core.harness.ToolOutcomeStatus
import com.thatgfsj.qaa.core.harness.summarizeToolCall
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 轮次增量事件（终端逐条回显、Normal 轮末取本轮文本）。 */
enum class AgentEventKind { ASSISTANT, TOOL, NOTICE }

data class AgentEvent(val kind: AgentEventKind, val text: String)

/**
 * 会话记录适配器（HarnessPorts.TurnRecorder 端口装配）。
 *
 * 三件事：
 * 1. 落盘仍走 [JsonlTurnRecorder]（树状 JSONL、callId 回链、轮次活动标记）；
 * 2. 提示视图改由 [compactedView] 生成 —— 摘要段前置为 TOOL 角色（系统回注），
 *    被摘要覆盖的历史段剔除，与 PromptAssembler 的段序约定一致；
 * 3. 把助手可见文本与工具终态以 [AgentEvent] 推给 UI，同时累积本轮可展示文本。
 *
 * 工具调用 JSON 不进对话区（由 [ToolCallParser] 判定），只展示纯文字作答与工具结果行。
 */
class RecordingTurnRecorder(
    @Volatile
    private var delegate: JsonlTurnRecorder,
    private val compactedView: () -> CompactedView,
    private val parser: ToolCallParser = ToolCallParser(),
    private val tokenRelay: AssistantTokenRelay? = null,
) : TurnRecorder {

    private val _events = MutableSharedFlow<AgentEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 轮次事件流（供 UI 收集）。 */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val turnText = StringBuilder()

    fun beginTurn() {
        turnText.setLength(0)
        tokenRelay?.beginTurn()
        delegate.beginTurn()
    }

    fun endTurn() = delegate.endTurn()

    /** /new 切换会话后重绑落盘委派；事件流与 token 转发器保持不变。 */
    fun rebind(next: JsonlTurnRecorder) {
        delegate = next
    }

    /** 本轮已产生的可见助手文本（工具调用 JSON 已过滤）。 */
    fun turnAssistantText(): String = turnText.toString().trim()

    /** 运行态提示（初始化失败、压缩结果、命令回执）以 NOTICE 事件推给 UI。 */
    fun notice(text: String) {
        if (text.isNotBlank()) _events.tryEmit(AgentEvent(AgentEventKind.NOTICE, text))
    }

    override suspend fun appendUser(text: String, tags: List<String>) = delegate.appendUser(text, tags)

    override suspend fun appendAssistant(
        text: String,
        toolCalls: List<ToolCall>,
        parseRetries: Int,
        metrics: GenerationMetrics?,
        stepIndex: Int,
    ) {
        delegate.appendAssistant(text, toolCalls, parseRetries, metrics, stepIndex)
        val visible = visibleAssistantText(text, parser)
        // 生成步收口：已逐 token 上屏的步由 UI 行定稿，不再整段重放；工具调用 JSON 的步
        // 以空文本丢弃该临时行，保持「调用 JSON 不进对话区」的既有语义。
        val streamed = tokenRelay?.finishStep(visible) ?: false
        if (visible.isNotEmpty()) {
            turnText.append(visible).append('\n')
            if (!streamed) _events.tryEmit(AgentEvent(AgentEventKind.ASSISTANT, visible))
        }
    }

    override suspend fun appendTool(call: ToolCall, outcome: com.thatgfsj.qaa.core.harness.ToolOutcome, stepIndex: Int) {
        delegate.appendTool(call, outcome, stepIndex)
        _events.tryEmit(
            AgentEvent(
                AgentEventKind.TOOL,
                "[TOOL] " + summarizeToolCall(call) + " -> " + outcome.status.name,
            ),
        )
    }

    override suspend fun appendSystemNotice(text: String) = delegate.appendSystemNotice(text)

    /** 提示视图：压缩摘要前置 + 未被覆盖的历史。 */
    override suspend fun snapshot(): List<TurnNode> = compactedView().toPromptHistory()
}

/** 工具调用请求本身不进对话区；其余文本（含解析失败的自纠说明）原样展示。 */
internal fun visibleAssistantText(text: String, parser: ToolCallParser): String =
    when (parser.parse(text)) {
        is ParseOutcome.Calls -> ""
        else -> text.trim()
    }

/** 压缩视图 → 提示历史：摘要段（TOOL 角色、标签 summary）在前，保留历史按树序跟随。 */
internal fun CompactedView.toPromptHistory(): List<TurnNode> {
    val summaries = summaries
        .filter { it.isNotBlank() }
        .map { TurnNode(role = TurnRole.TOOL, content = it, tags = listOf(TAG_SUMMARY)) }
    return summaries + history
}

/** 摘要段节点标签（与落盘 schema 的 summary 角色对应）。 */
const val TAG_SUMMARY: String = "summary"

/** 工具终态的中文短名（/tree 与终端回显共用）。 */
internal fun ToolOutcomeStatus.displayName(): String = when (this) {
    ToolOutcomeStatus.SUCCESS -> "成功"
    ToolOutcomeStatus.FAILURE -> "失败"
    ToolOutcomeStatus.DENIED -> "已拒绝"
}
