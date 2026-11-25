package com.thatgfsj.qaa.feature.normal.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thatgfsj.qaa.feature.normal.model.ChatRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 会话页 ViewModel（Issue #8 骨架 → #127 · 段模型）。
 *
 * [segments] 是唯一事实来源；装配层以「同 id 覆盖、新 id 追加」推入工具类段，
 * 本类负责文本段流式拼装与轮次状态（空闲 / 生成中），并把确认挂起镜像给顶层
 * 兜底对话框（[toolConfirmPending]）。停止 / 重试 / 内联确认按钮属 #127 B 段。
 */
class ChatViewModel(
    private val tokenIntervalMs: Long = DEFAULT_TOKEN_INTERVAL_MS,
) : ViewModel() {

    private val _segments = MutableStateFlow<List<ChatSegment>>(emptyList())
    /** 会话时间线（文本 / 工具卡片 / 确认条 / 错误段）。 */
    val segments: StateFlow<List<ChatSegment>> = _segments.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    /** 待确认工具描述（NormalApp 顶层兜底对话框镜像用）。 */
    private val _toolConfirmPending = MutableStateFlow<String?>(null)
    val toolConfirmPending: StateFlow<String?> = _toolConfirmPending.asStateFlow()

    private val _metrics = MutableStateFlow(ChatMetrics())
    /** 指标条状态（只订阅装配层指标流，未载入模型时为 -- 占位）。 */
    val metrics: StateFlow<ChatMetrics> = _metrics.asStateFlow()

    /** 真实 Agent 端口（:app 注入）；null 时保持模块内 fake 流式回复。 */
    private var agent: NormalAgentPort? = null

    /** 指标 / 段流收集任务：端口重绑时先取消，避免双来源争写。 */
    private var metricsJob: Job? = null
    private var segmentsJob: Job? = null

    private var nextTextId = 0L

    /** 装配层注入真实运行链（null 复位为骨架 fake）。 */
    fun bindAgent(port: NormalAgentPort?) {
        agent = port
        metricsJob?.cancel()
        metricsJob = null
        segmentsJob?.cancel()
        segmentsJob = null
        if (port == null) {
            _metrics.value = ChatMetrics()
            return
        }
        port.chatMetrics?.let { source ->
            metricsJob = viewModelScope.launch { source.collect { _metrics.value = it } }
        }
        port.chatSegments?.let { source ->
            segmentsJob = viewModelScope.launch {
                source.collect { segment -> applySegment(segment) }
            }
        }
    }

    /**
     * 发送一条用户消息：注入真实端口时走 Agent 轮次（工具卡片与确认条由装配层推入），
     * 否则用脚本化延迟逐 token 推送 fake 回复。生成中或空白输入将被忽略。
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return
        appendText(ChatRole.USER, trimmed)
        runTurn(trimmed)
    }

    /** 同步待确认工具描述（null 表示无）；确认门的挂起状态由装配层镜像到这里。 */
    fun setToolConfirmPending(description: String?) {
        _toolConfirmPending.value = description?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 一轮执行：真实端口优先，未接线时走 fake 脚本流。 */
    private fun runTurn(text: String) {
        _isGenerating.value = true
        val replyId = nextTextId++
        upsertSegment(ChatSegment.Text(replyId, ChatRole.ASSISTANT, ""))
        val port = agent
        viewModelScope.launch {
            val streamJob: Job? = if (port == null) null else port.assistantTokens?.let { tokens ->
                launch { tokens.collect { token -> appendStreaming(replyId, token) } }
            }
            try {
                if (port == null) {
                    scriptedReplyTokens(text).forEach { token ->
                        delay(tokenIntervalMs)
                        appendStreaming(replyId, token)
                    }
                } else {
                    val reply = port.ask(text)
                    if (reply.isNotEmpty()) {
                        replaceText(replyId, reply)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 端口内部已吞掉轮次异常，这里兜底引擎 / 装配层越界的意外异常。
                appendError("本轮执行失败：${error.message ?: error::class.simpleName}")
            } finally {
                streamJob?.cancel()
                finishTurn()
            }
        }
    }

    private fun finishTurn() {
        _isGenerating.value = false
        _segments.update { list ->
            list.filterNot { it is ChatSegment.Text && it.role == ChatRole.ASSISTANT && it.content.isEmpty() }
        }
    }

    /** 装配层段推入：同 id 覆盖，新 id 按语义位置插入；确认条镜像到兜底对话框状态。 */
    private fun applySegment(segment: ChatSegment) {
        upsertSegment(segment, placeBeforeAnswer = segment !is ChatSegment.Text)
        if (segment is ChatSegment.Confirm) {
            _toolConfirmPending.value = segment.description.takeIf { segment.state == ConfirmSegmentState.PENDING }
        }
    }

    /**
     * 段接入：同 id 覆盖；新段按语义位置插入。工具 / 确认 / 错误段（[placeBeforeAnswer]）
     * 插到本轮助手答复气泡前——生成步先流式出文字、工具卡片随后才到，终稿答复要排在
     * 执行链之后；锚点对事件到达顺序不敏感。
     */
    private fun upsertSegment(segment: ChatSegment, placeBeforeAnswer: Boolean = false) {
        _segments.update { list ->
            val index = list.indexOfFirst { it.id == segment.id }
            if (index >= 0) {
                list.toMutableList().apply { this[index] = segment }
            } else {
                list.toMutableList().apply { add(insertIndexFor(list, placeBeforeAnswer), segment) }
            }
        }
    }

    /** 新段插入位置：需要排在答复前时取本轮助手气泡下标，否则追加末尾。 */
    private fun insertIndexFor(list: List<ChatSegment>, placeBeforeAnswer: Boolean): Int {
        if (!placeBeforeAnswer) return list.size
        val lastUserIndex = list.indexOfLast { it is ChatSegment.Text && it.role == ChatRole.USER }
        return list.indices.lastOrNull { index ->
            index > lastUserIndex &&
                list[index] is ChatSegment.Text &&
                (list[index] as ChatSegment.Text).role == ChatRole.ASSISTANT
        } ?: list.size
    }

    /** 追加流式片段；生成已结束或片段空时忽略（迟到片段防护）。 */
    private fun appendStreaming(segmentId: Long, token: String) {
        if (token.isEmpty() || !_isGenerating.value) return
        _segments.update { list ->
            list.map { segment ->
                if (segment.id == segmentId && segment is ChatSegment.Text) {
                    segment.copy(content = segment.content + token)
                } else {
                    segment
                }
            }
        }
    }

    /** 终稿覆盖：流式预览以端口返回文本为准（位置由占位段决定，不移动）。 */
    private fun replaceText(segmentId: Long, content: String) {
        _segments.update { list ->
            list.map { segment ->
                if (segment.id == segmentId && segment is ChatSegment.Text) segment.copy(content = content) else segment
            }
        }
    }

    private fun appendText(role: ChatRole, content: String) {
        upsertSegment(ChatSegment.Text(nextTextId++, role, content))
    }

    private fun appendError(message: String) {
        upsertSegment(ChatSegment.Error(nextTextId++, message, retryable = true), placeBeforeAnswer = true)
    }

    /** fake 脚本化回复：按中文语感切 token，仅演示流式逐字渲染。 */
    internal fun scriptedReplyTokens(userText: String): List<String> = listOf(
        "已收到", "：", "「", userText, "」。",
        "这是", "骨架期", "的", "fake 流式回复，",
        "真实", "推理链路", "将在", "引擎接线", "完成后", "接入。",
    )

    companion object {
        const val DEFAULT_TOKEN_INTERVAL_MS = 120L
    }
}
