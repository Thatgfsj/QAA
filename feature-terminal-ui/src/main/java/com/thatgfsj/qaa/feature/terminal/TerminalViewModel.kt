package com.thatgfsj.qaa.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Terminal 三段式视图状态持有者（Issue #9 骨架）。
 *
 * 对外接线预留（骨架期全部为空实现 / fake 数据）：
 * - 命令层 Issue #10：[onUserInput] 回显用户输入、[resetSession] 支撑「清屏再渲染」、
 *   [setMode] / [setModelInfo] / [setBackend] 对应 chat / agent / model / backend 命令回显；
 * - 推理层 Issue #5：[onAiChunk] 流式片段回填；metrics 经 [bindEngineMetrics] 以
 *   StateFlow&lt;TerminalMetricsSnapshot&gt; 形状接入真实引擎指标（[FakeMetricsTicker] 仅骨架单测用）；
 * - 工具确认流 Issue #14：实现 [TerminalToolConfirmHost]，配合 [TerminalUiGate] 完成
 *   挂起 / Allow / Deny / 取消清理（真实工具调用由 AgentLoop 触发）。
 *
 * 线程契约：所有变更方法在 UI 主线程（或单测线程）调用，快照更新无锁。
 */
class TerminalViewModel internal constructor(
    metricsTicker: FakeMetricsTicker?,
) : ViewModel(), TerminalToolConfirmHost {

    /**
     * 无参构造：供 ViewModelProvider 默认工厂与命令层装配使用。
     * 不启动模块内伪数据心跳——统计栏由装配层经 [bindEngineMetrics] 注入真实指标源。
     */
    constructor() : this(null)

    private val _uiState = MutableStateFlow(TerminalUiState())
    override val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    private var nextEntryId = 1L
    private var nextToolRequestId = 1L

    /** 当前流式 AI 行：streamId 由装配层发放，entryId 为对话行键，成对出现与清空。 */
    private var activeStreamId: Long? = null
    private var activeStreamEntryId: Long? = null
    private var nextStreamId = 1L

    private var fakeMetricsJob: Job? = null

    /** 最近一次载入占位文案：清屏后据此恢复，避免载入中界面重新静默（Issue #88）。 */
    private var modelLoadText: String? = null

    init {
        // 显式注入伪数据源时才启动心跳（骨架期单测用），生产由装配层注入真实指标。
        if (metricsTicker != null) {
            fakeMetricsJob = metricsTicker.launchIn(viewModelScope)
            bindMetrics(metricsTicker.metrics)
        }
    }

    /** 接入推理指标状态流（接线点）。重复绑定会叠加收集，装配层应只调一次。 */
    fun bindMetrics(source: StateFlow<TerminalMetricsSnapshot>) {
        viewModelScope.launch {
            source.collect { snapshot -> updateState { it.copy(metrics = snapshot) } }
        }
    }

    /**
     * 接入真实引擎指标源（装配层）：先停掉模块内伪数据心跳，再绑定，
     * 避免两个来源争写统计栏导致显示跳变。
     */
    fun bindEngineMetrics(source: StateFlow<TerminalMetricsSnapshot>) {
        fakeMetricsJob?.cancel()
        fakeMetricsJob = null
        bindMetrics(source)
    }

    /**
     * 接入装配层模型载入进度（Issue #88）：载入中显示占位行，结束静默移除。
     *
     * 占位行只由状态流驱动，UI 不估算载入耗时；失败原因由命令回执另行回显，
     * 占位行本身不保留任何「成功」痕迹。
     */
    fun bindModelLoad(source: StateFlow<TerminalModelLoadState>) {
        viewModelScope.launch {
            source.collect { state ->
                // #104：载入进度同步进状态快照，供顶栏显示「载入中」而不是模型就绪假象。
                updateState { it.copy(modelLoad = state) }
                when (state) {
                    TerminalModelLoadState.Idle -> {
                        modelLoadText = null
                        clearTransientEntry(MODEL_LOAD_TAG)
                    }

                    is TerminalModelLoadState.Loading -> {
                        val text = modelLoadPlaceholderText(state.name)
                        modelLoadText = text
                        setTransientEntry(MODEL_LOAD_TAG, text)
                    }
                }
            }
        }
    }

    /**
     * 设置一条临时占位行：同一 [tag] 只保留一行，状态更新时就地改文案；
     * 返回的 id 不复用于普通对话行，清屏时统一清空。
     */
    fun setTransientEntry(tag: String, text: String) {
        val body = text.trimEnd()
        if (tag.isEmpty() || body.isEmpty()) return
        if (_uiState.value.entries.none { it.tag == tag }) {
            val id = nextEntryId
            nextEntryId += 1
            updateState { state ->
                state.copy(
                    entries = state.entries + TerminalEntry(id, TerminalRole.SYSTEM, body, tag),
                    followOutput = true,
                )
            }
            return
        }
        updateState { state ->
            state.copy(entries = state.entries.map { if (it.tag == tag) it.copy(text = body) else it })
        }
    }

    /** 静默移除临时占位行（载入完成 / 失败后都调用）。 */
    fun clearTransientEntry(tag: String) {
        updateState { state -> state.copy(entries = state.entries.filterNot { it.tag == tag }) }
    }

    /** 用户输入一行：立即以「你 &lt; 」前缀回显（命令语义由 Issue #10 解析层赋予）。 */
    fun onUserInput(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val id = nextEntryId
        nextEntryId += 1
        updateState { state ->
            state.copy(entries = state.entries + TerminalEntry(id, TerminalRole.USER, trimmed))
        }
    }

    /**
     * Shell 命令输出回显（命令层接线点）：整段写成一条 SYSTEM 行，不与相邻行合并，
     * 便于 help 表格、错误提示与模型清单保持原样排版。
     */
    fun appendOutput(text: String) {
        val body = text.trimEnd()
        if (body.isEmpty()) return
        val id = nextEntryId
        nextEntryId += 1
        updateState { state ->
            state.copy(
                entries = state.entries + TerminalEntry(id, TerminalRole.SYSTEM, body),
                followOutput = true,
            )
        }
    }

    /**
     * AI 流式片段回填（Issue #5 流式回调接线点）。
     *
     * 流式文本缓冲规则：末行为 AI 时合并续写，否则新起一行
     * （用户刚输入 / 清屏后重开一条 AI 行）。
     */
    fun onAiChunk(chunk: String) {
        if (chunk.isEmpty()) return
        updateState { state ->
            val last = state.entries.lastOrNull()
            if (last != null && last.role == TerminalRole.AI) {
                state.copy(entries = state.entries.dropLast(1) + last.copy(text = last.text + chunk))
            } else {
                val id = nextEntryId
                nextEntryId += 1
                state.copy(entries = state.entries + TerminalEntry(id, TerminalRole.AI, chunk))
            }
        }
    }

    // ---- token 级流式入口（装配层把运行链的 token 事件流接到这里） ----

    /**
     * 开启一条流式 AI 行（占位）：生成步开始、首个 token 到达前由装配层调用。
     *
     * [streamId] 与 [appendToken] / [endAssistantTurn] 配对，用于迟到 / 乱序事件防护；
     * 已有未收口行时先定稿，避免两段生成写进同一行。返回本次流的标识。
     */
    fun beginAssistantTurn(streamId: Long = nextStreamId): Long {
        endAssistantTurn()
        if (streamId >= nextStreamId) nextStreamId = streamId + 1
        val entryId = nextEntryId
        nextEntryId += 1
        activeStreamId = streamId
        activeStreamEntryId = entryId
        updateState { state ->
            state.copy(
                entries = state.entries + TerminalEntry(entryId, TerminalRole.AI, ""),
                followOutput = true,
            )
        }
        return streamId
    }

    /**
     * 增量追加到当前 AI 行：空片段忽略；[streamId] 为空表示追加当前流，
     * 显式传入且与当前流不符（迟到 / 乱序）时整条忽略。
     */
    fun appendToken(text: String, streamId: Long? = null) {
        if (text.isEmpty()) return
        val active = activeStreamId ?: return
        if (streamId != null && streamId != active) return
        val entryId = activeStreamEntryId ?: return
        updateState { state ->
            state.copy(
                entries = state.entries.map {
                    if (it.id == entryId) it.copy(text = it.text + text) else it
                },
                followOutput = true,
            )
        }
    }

    /**
     * 当前 AI 行定稿：生成步结束后由装配层调用。[text] 为该步最终可见文本——
     * 空串表示整步只有工具调用 JSON（丢弃该行），null 表示异常中断（保留已流式内容）。
     * [streamId] 不匹配的收口事件忽略；已定稿的行不再接受后续片段。
     */
    fun endAssistantTurn(streamId: Long? = null, text: String? = null) {
        val active = activeStreamId ?: return
        if (streamId != null && streamId != active) return
        val entryId = activeStreamEntryId
        activeStreamId = null
        activeStreamEntryId = null
        if (entryId == null) return
        updateState { state ->
            val index = state.entries.indexOfFirst { it.id == entryId }
            if (index < 0) {
                state
            } else {
                val current = state.entries[index]
                val finalText = text ?: current.text
                val entries = if (finalText.isBlank()) {
                    state.entries.filterNot { it.id == entryId }
                } else {
                    state.entries.toMutableList().also { it[index] = current.copy(text = finalText) }
                }
                state.copy(entries = entries)
            }
        }
    }

    /** 进入空会话：清空对话与确认区、恢复滚动跟随（「清屏再渲染」由命令层 Issue #10 驱动）。 */
    fun resetSession() {
        activeStreamId = null
        activeStreamEntryId = null
        updateState { state ->
            state.copy(
                entries = emptyList(),
                toolConfirm = ToolConfirmState.Idle,
                // 清屏即结束本会话的自动决策约定（#93）：恢复逐次询问。
                confirmPolicy = ToolConfirmPolicy.ASK,
                followOutput = true,
            )
        }
        // 清屏不打断正在进行的模型载入：重置后按最近状态恢复占位行。
        modelLoadText?.let { setTransientEntry(MODEL_LOAD_TAG, it) }
    }

    /** 会话模式切换（chat / agent 命令回显接线点）。 */
    fun setMode(mode: TerminalMode) {
        updateState { it.copy(mode = mode) }
    }

    /** 实际命中后端回显（Issue #5 降级链结果在装配层映射为 [TerminalBackend]）。 */
    fun setBackend(backend: TerminalBackend) {
        updateState { it.copy(backend = backend) }
    }

    /** 模型信息回显（model 命令 / 装配层注入）。 */
    fun setModelInfo(name: String, quantization: String) {
        updateState { it.copy(modelInfo = TerminalModelInfo(name, quantization)) }
    }

    /** 滚动跟随开关：UI 层检测到用户离开 / 回到列表底部时调用。 */
    fun setFollowOutput(enabled: Boolean) {
        updateState { it.copy(followOutput = enabled) }
    }

    /** 请求工具确认（Issue #14 确认流接入点；同一时刻仅保留最新一条待确认项）。 */
    override fun requestToolConfirmation(description: String) {
        val trimmed = description.trim()
        if (trimmed.isEmpty()) return
        val id = nextToolRequestId
        nextToolRequestId += 1
        updateState { it.copy(toolConfirm = ToolConfirmState.Pending(id, trimmed)) }
    }

    /** 用户放行（Allow 按钮回调；Issue #14 后续按 Resolved 挂接真实执行）。 */
    fun onToolAllow() {
        resolveToolConfirmation(allowed = true)
    }

    /** 用户拒绝（Deny 按钮回调）。 */
    fun onToolDeny() {
        resolveToolConfirmation(allowed = false)
    }

    /**
     * 长按 yes / no（#93）：yes 开启本会话自动放行，并立即结算当前挂起项（若有）；
     * no 关闭自动放行、恢复逐次询问，挂起项保持待人工决策，不伪造结果。
     */
    fun rememberAutoAllow(enabled: Boolean) {
        updateState { state ->
            if (enabled) {
                val pending = state.toolConfirm as? ToolConfirmState.Pending
                state.copy(
                    confirmPolicy = ToolConfirmPolicy.AUTO_ALLOW,
                    toolConfirm = if (pending == null) {
                        state.toolConfirm
                    } else {
                        ToolConfirmState.Resolved(pending.requestId, pending.description, allowed = true)
                    },
                )
            } else if (state.confirmPolicy == ToolConfirmPolicy.AUTO_ALLOW) {
                state.copy(confirmPolicy = ToolConfirmPolicy.ASK)
            } else {
                state
            }
        }
    }

    /** 恢复逐次询问（/exit 离开会话时由命令路由调用）。 */
    fun resetToolConfirmPolicy() {
        updateState { state ->
            if (state.confirmPolicy == ToolConfirmPolicy.ASK) {
                state
            } else {
                state.copy(confirmPolicy = ToolConfirmPolicy.ASK)
            }
        }
    }

    /** 挂起被取消时收起待确认项（清屏 / 轮次取消），避免遗留无人决策的按钮。 */
    override fun dismissToolConfirmation() {
        updateState { state ->
            if (state.toolConfirm is ToolConfirmState.Pending) {
                state.copy(toolConfirm = ToolConfirmState.Idle)
            } else {
                state
            }
        }
    }

    private fun resolveToolConfirmation(allowed: Boolean) {
        updateState { state ->
            val pending = state.toolConfirm as? ToolConfirmState.Pending ?: return@updateState state
            state.copy(
                toolConfirm = ToolConfirmState.Resolved(
                    requestId = pending.requestId,
                    description = pending.description,
                    allowed = allowed,
                ),
            )
        }
    }

    private inline fun updateState(transform: (TerminalUiState) -> TerminalUiState) {
        _uiState.update(transform)
    }

    companion object {
        /** 模型载入占位行标签（全局唯一，避免与普通系统行混淆）。 */
        const val MODEL_LOAD_TAG = "model-load"
    }
}
