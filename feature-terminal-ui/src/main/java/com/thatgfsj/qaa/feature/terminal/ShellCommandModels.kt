package com.thatgfsj.qaa.feature.terminal

/**
 * Shell 命令层数据模型（Issue #10）：纯 Kotlin，不依赖 Android / Compose，可 JVM 单测。
 *
 * 解析器本身无状态：每次解析由调用方按当前视图快照构造 [ShellContext]，
 * 解析产物 [ShellParseResult] 中的 [ShellEffect] 再由命令路由分发到 TerminalViewModel。
 */

/** 命令状态机阶段：SHELL 顶层态 / SESSION 会话态（会话态内再分 CHAT / AGENT 两种模式）。 */
enum class ShellStage(val displayName: String) {
    SHELL("Shell 顶层态"),
    SESSION("会话态"),
}

/**
 * backend 命令的请求面取值（docs/ARCHITECTURE.md 4.1：请求 AUTO/NPU/HYBRID/CPU，
 * 实际命中以 [TerminalBackend] 回显）。
 */
enum class BackendRequest(val command: String, val displayName: String) {
    AUTO("auto", "auto（自动降级链）"),
    NPU("npu", "NPU"),
    HYBRID("hybrid", "HYBRID"),
    CPU("cpu", "CPU");

    /** 锁定后顶栏应回显的命中值；AUTO 无固定命中值（保留当前回显），返回 null。 */
    fun toTerminalBackend(): TerminalBackend? = when (this) {
        AUTO -> null
        NPU -> TerminalBackend.NPU
        HYBRID -> TerminalBackend.HYBRID
        CPU -> TerminalBackend.CPU
    }

    companion object {
        /** 从命令参数解析（大小写不敏感）；非法值返回 null。 */
        fun fromCommand(token: String): BackendRequest? =
            entries.firstOrNull { it.command == token.trim().lowercase() }
    }
}

/** 本地模型条目：[index] 从 1 起，面向 model 清单与 chat / agent 选型。 */
data class LocalModelEntry(val index: Int, val name: String, val quantization: String) {
    val displayText: String get() = "$name($quantization)"
}

/** 本地模型目录：Phase 1 仅内置冒烟模型（docs/ARCHITECTURE.md 第 1 节）。 */
object LocalModelCatalog {
    const val SMOKE_MODEL_NAME = "Supra2.5-Pro-Instruct"
    const val SMOKE_MODEL_QUANTIZATION = "Q4_0"

    val entries: List<LocalModelEntry> = listOf(
        LocalModelEntry(index = 1, name = SMOKE_MODEL_NAME, quantization = SMOKE_MODEL_QUANTIZATION),
    )
}

/** 解析上下文：由调用方在每次解析前按当前视图状态构造，解析器不持有任何可变状态。 */
data class ShellContext(
    val stage: ShellStage = ShellStage.SHELL,
    val mode: TerminalMode = TerminalMode.CHAT,
    val modelInfo: TerminalModelInfo = TerminalModelInfo(),
    val requestedBackend: BackendRequest = BackendRequest.AUTO,
    val activeBackend: TerminalBackend = TerminalBackend.NPU,
    val metrics: TerminalMetricsSnapshot = TerminalMetricsSnapshot(),
    val models: List<LocalModelEntry> = LocalModelCatalog.entries,
)

/** 解析产物中的状态副作用：由命令路由分发到 TerminalViewModel 的既有接线点。 */
sealed interface ShellEffect {
    /** 清屏：转 [TerminalViewModel.resetSession]。 */
    data object ClearScreen : ShellEffect

    /** 顶栏模式切换：转 [TerminalViewModel.setMode]。 */
    data class SetMode(val mode: TerminalMode) : ShellEffect

    /** 顶栏模型回显：转 [TerminalViewModel.setModelInfo]。 */
    data class SelectModel(val model: LocalModelEntry) : ShellEffect

    /** 请求后端锁定（AUTO 表示恢复降级链）：转 [TerminalViewModel.setBackend]。 */
    data class SetBackend(val request: BackendRequest) : ShellEffect

    /** 会话态普通文本：作为用户 prompt 转交对话回调。 */
    data class ForwardPrompt(val text: String) : ShellEffect

    /** start window：切回正常窗口并立即重启。 */
    data object StartNormalWindow : ShellEffect
}

/** 单次解析结果：[stage] 为解析后的阶段，[output] 为中文回显行，[effects] 按列表顺序执行。 */
data class ShellParseResult(
    val stage: ShellStage,
    val output: List<String> = emptyList(),
    val effects: List<ShellEffect> = emptyList(),
)
