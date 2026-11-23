package com.thatgfsj.qaa.core.harness

/**
 * [TurnRecorder] 的 JSONL 实现（设计文档 5.2/5.3，Issue #15）。
 *
 * 每次 append* 落一个树节点：parentId 指向当前活动叶，返回后活动叶前移。
 * assistant 节点的 toolCalls 由 [TreeSessionStore] 分配 callId，appendTool 按
 * 工具名与参数回链到对应调用；未回链的调用（如重启后继续）callId 为 null。
 * 轮次边界由装配层通过 [beginTurn]/[endTurn] 告知，供 /fork 拒绝并发改树。
 */
class JsonlTurnRecorder(
    val store: TreeSessionStore,
) : TurnRecorder {

    private val pendingCalls = ArrayDeque<PendingCall>()

    fun beginTurn() {
        store.markTurnActive(true)
    }

    fun endTurn() {
        store.markTurnActive(false)
    }

    override suspend fun appendUser(text: String, tags: List<String>) {
        store.append(role = SessionRole.USER, content = text, tags = tags)
    }

    override suspend fun appendAssistant(
        text: String,
        toolCalls: List<ToolCall>,
        parseRetries: Int,
        metrics: GenerationMetrics?,
        stepIndex: Int,
    ) {
        val node = store.append(
            role = SessionRole.ASSISTANT,
            content = text,
            toolCalls = toolCalls,
            parseRetries = parseRetries,
            metrics = metrics,
            stepIndex = stepIndex,
        )
        // 新的 assistant 落地即重置待回链队列，避免跨轮次错配。
        pendingCalls.clear()
        node.toolCalls.forEach { call ->
            pendingCalls.addLast(PendingCall(ToolCall(call.tool, call.argsJson), call.callId))
        }
    }

    override suspend fun appendTool(call: ToolCall, outcome: ToolOutcome, stepIndex: Int) {
        store.append(
            role = SessionRole.TOOL,
            content = outcome.output,
            callId = takeCallId(call),
            tool = call.tool,
            toolStatus = outcome.status,
            errorCode = outcome.errorCode,
            stepIndex = stepIndex,
            durationMs = outcome.durationMs,
        )
    }

    override suspend fun appendSystemNotice(text: String) {
        store.append(role = SessionRole.TOOL, content = text, tags = listOf(TAG_CORRECTION))
    }

    override suspend fun snapshot(): List<TurnNode> = store.snapshot()

    /** 取出与 [call] 匹配的待回链 callId（同轮内按工具名与参数字符串匹配）。 */
    private fun takeCallId(call: ToolCall): String? {
        val index = pendingCalls.indexOfFirst {
            it.call.tool == call.tool && it.call.argsJson == call.argsJson
        }
        if (index < 0) return null
        return pendingCalls.removeAt(index).callId
    }

    private data class PendingCall(val call: ToolCall, val callId: String)

    companion object {
        /** 解析纠错等系统级回注的节点标记（设计附录 A.0）。 */
        const val TAG_CORRECTION: String = "correction"
    }
}
