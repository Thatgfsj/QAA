package com.thatgfsj.qaa.feature.terminal

/**
 * Terminal 三段式视图的状态模型（Issue #9 骨架）。
 *
 * 纯 Kotlin 数据层：不依赖 Android / Compose，保证可 JVM 单测；
 * 唯一持有者为 [TerminalViewModel]，对外以 StateFlow 暴露。
 */

/**
 * 流式对话角色：「你 &lt; 」「AI &lt; 」对应对话契约；
 * [SYSTEM] 为 Shell 命令输出（help 表格 / 中文提示），无前缀、单独成行。
 */
enum class TerminalRole(val displayPrefix: String) {
    USER("你 < "),
    AI("AI < "),
    SYSTEM(""),
}

/** 会话模式：顶部常驻栏 [CHAT] / [AGENT] 标签来源（切换由命令层 Issue #10 驱动）。 */
enum class TerminalMode(val displayTag: String) {
    CHAT("CHAT"),
    AGENT("AGENT"),
}

/**
 * 实际命中后端（降级链结果）。
 *
 * :feature-terminal-ui 依赖图仅指向 :core-harness（docs/ARCHITECTURE.md 第 2 节），
 * 故此处定义 UI 本地枚举；Issue #5 落地后由装配层把
 * com.thatgfsj.qaa.core.inference.ComputeBackend 映射为本枚举
 * （AUTO 是请求值，实际命中只会是 NPU / HYBRID / CPU）。
 */
enum class TerminalBackend(val displayTag: String) {
    NPU("NPU"),
    HYBRID("HYBRID"),
    CPU("CPU"),
}

/** 中部滚动区一行对话。id 单调递增，供 LazyColumn 复用键与流式合并判断。 */
data class TerminalEntry(
    val id: Long,
    val role: TerminalRole,
    val text: String,
)

/** 顶部常驻栏模型信息：模型名(量化)。默认值为冒烟模型（docs/ARCHITECTURE.md 第 1 节）。 */
data class TerminalModelInfo(
    val name: String = "Supra2.5-Pro-Instruct",
    val quantization: String = "Q4_0",
) {
    val displayText: String get() = "$name($quantization)"
}

/**
 * [TOOL] 工具确认区状态机（确认流本体属 Issue #14）。
 *
 * requestId 单调递增：Pending -> Resolved 一次流转，新请求直接替换旧状态，
 * 同时保证确认区在 LazyColumn 中的复用键唯一。
 */
sealed interface ToolConfirmState {
    /** 无待确认项。 */
    data object Idle : ToolConfirmState

    /** 待确认项。 */
    data class Pending(val requestId: Long, val description: String) : ToolConfirmState

    /** 已确认项：记录用户选择，供 Issue #14 挂接真实执行 / 中止回调。 */
    data class Resolved(val requestId: Long, val description: String, val allowed: Boolean) : ToolConfirmState
}

/** [TOOL] 待确认文案：[TOOL] read(".") -> Allow / Deny?。 */
fun ToolConfirmState.Pending.confirmText(): String = "[TOOL] $description -> Allow / Deny?"

/** [TOOL] 已确认文案：Allow / Deny 结果直接回显在同一行。 */
fun ToolConfirmState.Resolved.resultText(): String =
    "[TOOL] $description -> ${if (allowed) "Allow" else "Deny"}"

/** 终端 UI 快照（默认即「空会话」：无对话、无确认、跟随开启、CHAT + 冒烟模型 + NPU 展示值）。 */
data class TerminalUiState(
    val entries: List<TerminalEntry> = emptyList(),
    val modelInfo: TerminalModelInfo = TerminalModelInfo(),
    val mode: TerminalMode = TerminalMode.CHAT,
    val backend: TerminalBackend = TerminalBackend.NPU,
    val toolConfirm: ToolConfirmState = ToolConfirmState.Idle,
    val metrics: TerminalMetricsSnapshot = TerminalMetricsSnapshot(),
    /** 滚动跟随开关：用户上滑离开底部即暂停，回到底部自动恢复。 */
    val followOutput: Boolean = true,
)

/** 顶部常驻栏单行三段内容：[MODEL] 模型名(量化) | [CHAT/AGENT] | [实际后端]。 */
fun TerminalUiState.topBarSegments(): List<String> = listOf(
    "[MODEL] ${modelInfo.displayText}",
    "[${mode.displayTag}]",
    "[${backend.displayTag}]",
)
