package com.thatgfsj.qaa.core.harness

/** 追加一个最小轮次：user → assistant（可选一次工具调用与回注），返回本轮节点。 */
internal fun TreeSessionStore.appendTurn(
    userText: String,
    assistantText: String,
    toolOutput: String? = null,
    toolName: String = "read",
): List<SessionNode> {
    val nodes = mutableListOf<SessionNode>()
    nodes += append(SessionRole.USER, userText)
    if (toolOutput == null) {
        nodes += append(SessionRole.ASSISTANT, assistantText)
    } else {
        val assistant = append(
            role = SessionRole.ASSISTANT,
            content = assistantText,
            toolCalls = listOf(ToolCall(toolName, "{\"path\":\"notes.txt\"}")),
        )
        nodes += assistant
        nodes += append(
            role = SessionRole.TOOL,
            content = toolOutput,
            callId = assistant.toolCalls.single().callId,
            tool = toolName,
            toolStatus = ToolOutcomeStatus.SUCCESS,
            stepIndex = 0,
        )
    }
    return nodes
}

/** 生成指定 token 量级的 ASCII 填充文本（"data " 约 5 字符，按 4 字符/token 粗算）。 */
internal fun fillerTokens(tokens: Int): String = buildString {
    repeat(tokens) { append("data ") }
}

/** 记录调用的 fake 摘要引擎：按序返回预设摘要，可注入失败。 */
internal class RecordingSummaryEngine(
    replies: List<String> = listOf(DEFAULT_SUMMARY),
    private val failure: Exception? = null,
) : LlmEngineGateway {

    init {
        require(replies.isNotEmpty()) { "至少需要一条脚本化摘要" }
    }

    val calls = mutableListOf<List<PromptMessage>>()
    private val queue = ArrayDeque(replies)
    private val fallback = replies.last()

    override suspend fun streamGenerate(
        messages: List<PromptMessage>,
        onToken: (String) -> Unit,
    ): GenerationOutput {
        calls += messages
        failure?.let { throw it }
        val text = if (queue.isEmpty()) fallback else queue.removeFirst()
        onToken(text)
        return GenerationOutput(text = text, metrics = GenerationMetrics(backend = "FAKE"))
    }

    val invokeCount: Int get() = calls.size

    val lastSystemPrompt: String
        get() = calls.last().first { it.role == PromptRole.SYSTEM }.content

    val lastUserPrompt: String
        get() = calls.last().first { it.role == PromptRole.USER }.content

    companion object {
        const val DEFAULT_SUMMARY: String =
            "用户目标：完成测试任务。\n" +
                "已完成操作与结果：读取并整理了文件。\n" +
                "关键文件路径：notes.txt\n" +
                "未决事项：无。"
    }
}
