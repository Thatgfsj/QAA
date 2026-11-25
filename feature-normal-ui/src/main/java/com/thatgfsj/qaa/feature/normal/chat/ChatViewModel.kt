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
 * 会话页 ViewModel（Issue #8 骨架 → #127 窗口 Agent 化）。
 *
 * 时间线模型：[segments] 是唯一事实来源（文本气泡 / 工具卡片 / 确认条 / 错误段），
 * 装配层以「同 id 覆盖、新 id 追加」的 upsert 语义推入工具类段，本类负责文本段的
 * 流式拼装与轮次状态机（生成中 / 可停止 / 可重试）。
 *
 * 状态机：
 * ```
 * 空闲 --send--> 生成中(端口) --ask 返回--> 空闲
 *                    |--stop()--> 已停止(可重试)
 *                    |--错误段--> 失败(可重试)
 * 已停止/失败 --retry()--> 生成中(沿用上一条用户消息，不再追加气泡)
 * ```
 *
 * 停止语义：取消本轮协程（等价 abort），运行链 finally 会收口流式行与轮次互斥；
 * 确认挂起中停止同样走取消路径，确认门 finally 清空挂起状态。
 */
class ChatViewModel(
    private val tokenIntervalMs: Long = DEFAULT_TOKEN_INTERVAL_MS,
) : ViewModel() {

    private val _segments = MutableStateFlow<List<ChatSegment>>(emptyList())
    /** 会话时间线（文本 / 工具卡片 / 确认条 / 错误段）。 */
    val segments: StateFlow<List<ChatSegment>> = _segments.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _canRetry = MutableStateFlow(false)
    /** 上一轮失败或被停止，可「重试」重跑同一条用户消息。 */
    val canRetry: StateFlow<Boolean> = _canRetry.asStateFlow()

    /** 待确认工具描述（NormalApp 顶层兜底对话框镜像用；内联确认由 [ChatSegment.Confirm] 表达）。 */
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

    /** 当前轮次协程：停止按钮直接取消它（生成期 abort 的既有路径）。 */
    private var turnJob: Job? = null

    /** 上一条用户消息：失败 / 停止后重试复用，不再追加用户气泡。 */
    private var lastUserText: String? = null

    /** 本轮是否被用户停止（决定收尾时是否补错误段与重试态）。 */
    private var stopRequested = false


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

    /** 重试上一轮：失败 / 停止后把同一条用户消息重新跑一轮（不重复用户气泡）。 */
    fun retry() {
        val text = lastUserText ?: return
        if (_isGenerating.value) return
        // 清掉上一轮的错误段，避免重试成功后仍显示失败提示。
        _segments.update { list -> list.filterNot { it is ChatSegment.Error } }
        runTurn(text)
    }

    /** 停止当前轮次：取消本轮协程，收口后进入「已停止、可重试」。 */
    fun stop() {
        if (!_isGenerating.value) return
        stopRequested = true
        turnJob?.cancel()
    }

    /** 内联确认「允许」（直达装配层确认门）。 */
    fun allowTool() {
        agent?.allowPendingTool()
    }

    /** 内联确认「拒绝」（直达装配层确认门）。 */
    fun denyTool() {
        agent?.denyPendingTool()
    }

    /** 同步待确认工具描述（null 表示无）；确认门的挂起状态由装配层镜像到这里。 */
    fun setToolConfirmPending(description: String?) {
        _toolConfirmPending.value = description?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 一轮执行：真实端口优先，未接线时走 fake 脚本流。 */
    private fun runTurn(text: String) {
        lastUserText = text
        stopRequested = false
        _isGenerating.value = true
        _canRetry.value = false
        val replyId = nextTextId++
        upsertSegment(ChatSegment.Text(replyId, ChatRole.ASSISTANT, ""))
        val port = agent
        turnJob = viewModelScope.launch {
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
                    // 端口返回值为权威终稿：覆盖流式预览（可含工具调用片段或格式差异）。
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

    /** 轮次收尾：收口生成态；被停止时补一条可重试的提示段。 */
    private fun finishTurn() {
        _isGenerating.value = false
        turnJob = null
        if (stopRequested) {
            stopRequested = false
            appendError(STOPPED_MESSAGE)
        }
        _segments.update { list ->
            // 空占位 AI 气泡（未产生任何文本）在停止 / 失败后保留提示，去掉空壳。
            list.filterNot { it is ChatSegment.Text && it.role == ChatRole.ASSISTANT && it.content.isEmpty() }
        }
    }

    /** 装配层段推入：同 id 覆盖，新 id 追加；确认条与错误段顺带更新派生状态。 */
    private fun applySegment(segment: ChatSegment) {
        upsertSegment(segment, placeBeforeAnswer = segment !is ChatSegment.Text)
        when (segment) {
            is ChatSegment.Confirm ->
                _toolConfirmPending.value = segment.description.takeIf { segment.state == ConfirmSegmentState.PENDING }
            is ChatSegment.Error -> if (segment.retryable) _canRetry.value = true
            else -> Unit
        }
    }

    /**
     * 段接入：同 id 覆盖；新段按语义位置插入。
     *
     * 工具 / 确认 / 错误段（[placeBeforeAnswer]）插到「本轮助手答复气泡」之前：生成步先流式
     * 出文字、工具卡片随后才到，终稿答复却要排在执行链之后。锚点取「最后一条用户消息之后
     * 的第一条助手文本段」，对事件到达顺序不敏感（流式 token 与工具事件可能交错抵达）。
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

    /** 追加流式片段到指定文本段；生成已结束或片段空时忽略（迟到片段防护）。 */
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
        _canRetry.value = true
        upsertSegment(ChatSegment.Error(nextTextId++, message, retryable = true))
    }

    /** fake 脚本化回复：按中文语感切 token，仅演示流式逐字渲染。 */
    internal fun scriptedReplyTokens(userText: String): List<String> = listOf(
        "已收到", "：", "「", userText, "」。",
        "这是", "骨架期", "的", "fake 流式回复，",
        "真实", "推理链路", "将在", "引擎接线", "完成后", "接入。",
    )

    companion object {
        const val DEFAULT_TOKEN_INTERVAL_MS = 120L

        /** 停止后的提示文案（错误段复用 [ChatSegment.Error] 以带出重试按钮）。 */
        const val STOPPED_MESSAGE = "已停止本轮生成，可点「重试」重新发送。"
    }
}
