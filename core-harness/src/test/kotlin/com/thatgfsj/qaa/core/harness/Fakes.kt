package com.thatgfsj.qaa.core.harness

/**
 * 会话记录内存替身（设计附录 A.0）：
 * appendSystemNotice 以 tags=[correction] 落为 TOOL 节点，与真实纠错回注形态一致。
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
    ) {
        nodes += TurnNode(
            TurnRole.ASSISTANT, text,
            toolCalls = toolCalls, parseRetries = parseRetries,
        )
    }

    override suspend fun appendTool(call: ToolCall, outcome: ToolOutcome) {
        nodes += TurnNode(TurnRole.TOOL, outcome.output, toolCalls = listOf(call))
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

/** 脚本化工具执行替身（设计附录 A.0）：requiresConfirmation 按 3.3 契约，执行恒 SUCCESS。 */
class FakeToolGateway : ToolExecutorGateway {

    val executed = mutableListOf<ToolCall>()

    override suspend fun requiresConfirmation(toolName: String): Boolean = when (toolName) {
        "read" -> false
        "write", "edit", "draw_image" -> true
        else -> true
    }

    override suspend fun execute(call: ToolCall): ToolOutcome {
        executed += call
        return ToolOutcome(
            status = ToolOutcomeStatus.SUCCESS,
            output = "（模拟执行成功）${call.tool} 已处理",
        )
    }
}
