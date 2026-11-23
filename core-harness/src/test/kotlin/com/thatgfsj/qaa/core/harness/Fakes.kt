package com.thatgfsj.qaa.core.harness

/**
 * 会话记录内存替身（设计附录 A.0）：
 * appendSystemNotice 以 tags=[correction] 落为 TOOL 节点，与真实纠错回注形态一致；
 * appendTool 透传 outcome 的终态、错误码、耗时与工具步序号（设计文档 5.3）。
 */
class InMemoryTurnRecorder : TurnRecorder {

    val nodes = mutableListOf<TurnNode>()

    override suspend fun appendUser(text: String, tags: List<String>) {
        nodes += TurnNode(TurnRole.USER, text, tags = tags)
    }

    override suspend fun appendAssistant(
        text: String,
        toolCalls: List<ToolCall>,
        parseRetries: Int,
        metrics: GenerationMetrics?,
        stepIndex: Int,
    ) {
        nodes += TurnNode(
            TurnRole.ASSISTANT, text,
            toolCalls = toolCalls,
            parseRetries = parseRetries,
            metrics = metrics,
            stepIndex = stepIndex,
        )
    }

    override suspend fun appendTool(call: ToolCall, outcome: ToolOutcome, stepIndex: Int) {
        nodes += TurnNode(
            TurnRole.TOOL, outcome.output,
            toolCalls = listOf(call),
            stepIndex = stepIndex,
            toolStatus = outcome.status,
            errorCode = outcome.errorCode,
            durationMs = outcome.durationMs,
        )
    }

    override suspend fun appendSystemNotice(text: String) {
        nodes += TurnNode(TurnRole.TOOL, text, tags = listOf("correction"))
    }

    override suspend fun snapshot(): List<TurnNode> = nodes.toList()
}

/** 脚本化确认门替身（设计附录 A.0）：按序弹出决策，耗尽后重复末元素。 */
class FakeConfirmationGate(
    decisions: List<ConfirmationDecision>,
) : ConfirmationGate {

    val seen = mutableListOf<ToolCall>()
    private val queue = ArrayDeque(decisions)
    private val fallback = decisions.last()

    override suspend fun awaitConfirmation(call: ToolCall): ConfirmationDecision {
        seen += call
        return if (queue.isEmpty()) fallback else queue.removeFirst()
    }
}

/** 工具执行记录（步序号用于断言 AgentLoop 的步序透传，Issue #12）。 */
data class ExecutedCall(val call: ToolCall, val stepIndex: Int)

/** 脚本化工具执行替身（设计附录 A.0）：requiresConfirmation 按 3.3 契约，执行恒 SUCCESS。 */
class FakeToolGateway : ToolExecutorGateway {

    val executed = mutableListOf<ExecutedCall>()

    override suspend fun requiresConfirmation(toolName: String): Boolean = when (toolName) {
        "read" -> false
        "write", "edit", "draw_image" -> true
        else -> true
    }

    override suspend fun execute(call: ToolCall, stepIndex: Int): ToolOutcome {
        executed += ExecutedCall(call, stepIndex)
        return ToolOutcome(
            status = ToolOutcomeStatus.SUCCESS,
            output = "（模拟执行成功）${call.tool} 已处理",
        )
    }
}
