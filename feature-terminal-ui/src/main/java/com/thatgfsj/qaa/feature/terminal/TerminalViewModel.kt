package com.thatgfsj.qaa.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * - 推理层 Issue #5：[onAiChunk] 流式片段回填；metrics 经 [bindMetrics] 以
 *   StateFlow&lt;TerminalMetricsSnapshot&gt; 形状接入（骨架期接 [FakeMetricsTicker]）；
 * - 工具确认流 Issue #14：[requestToolConfirmation] / [onToolAllow] / [onToolDeny]。
 *
 * 线程契约：所有变更方法在 UI 主线程（或单测线程）调用，快照更新无锁。
 */
class TerminalViewModel internal constructor(
    metricsTicker: FakeMetricsTicker,
) : ViewModel() {

    /** 无参构造：供 ViewModelProvider 默认工厂与 Issue #10 路由装配使用。 */
    constructor() : this(FakeMetricsTicker())

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    private var nextEntryId = 1L
    private var nextToolRequestId = 1L

    init {
        // 骨架期直连模块内 fake 数据源；Issue #5 后由装配层改传真实 metrics 流。
        bindMetrics(metricsTicker.metrics)
        metricsTicker.launchIn(viewModelScope)
    }

    /** 接入推理指标状态流（Issue #5 接线点）。重复绑定会叠加收集，装配层应只调一次。 */
    fun bindMetrics(source: StateFlow<TerminalMetricsSnapshot>) {
        viewModelScope.launch {
            source.collect { snapshot -> updateState { it.copy(metrics = snapshot) } }
        }
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

    /** 进入空会话：清空对话与确认区、恢复滚动跟随（「清屏再渲染」由命令层 Issue #10 驱动）。 */
    fun resetSession() {
        updateState { state ->
            state.copy(
                entries = emptyList(),
                toolConfirm = ToolConfirmState.Idle,
                followOutput = true,
            )
        }
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
    fun requestToolConfirmation(description: String) {
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
}
