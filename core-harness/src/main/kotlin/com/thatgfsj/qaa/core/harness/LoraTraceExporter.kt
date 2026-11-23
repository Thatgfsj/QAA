package com.thatgfsj.qaa.core.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * LoRA 微调轨迹导出（设计文档 5.5，Issue #15）。
 *
 * 导出单元为一条分支线性链（沿 parentId 自叶回溯至根），产物为一行 SFT 样本：
 * messages 风格，系统段与运行时组装一致；tool 消息通过 tool_call_id 回链 assistant.tool_calls。
 *
 * 噪声过滤（默认）：解析失败节点（parseRetries > 0）、未完成的工具调用、
 * FAILURE/DENIED 工具结果整条样本剔除；summary 默认剔除，可配为 system 段。
 * 均为纯函数，不依赖存储实现。
 */
object LoraTraceExporter {

    data class Options(
        /** 系统段；null 时改用分支内的 system 节点（与运行时组装一致时保持默认值）。 */
        val systemPrompt: String? = PromptAssembler.DEFAULT_SYSTEM_PROMPT,
        /** summary 节点默认剔除；true 时按顺序并入 system 段。 */
        val includeSummaryAsSystem: Boolean = false,
        /** 默认 false 时，含 FAILURE/DENIED 工具结果的整条样本被剔除。 */
        val includeRejected: Boolean = false,
        /** true 时在 meta 中旁注引擎指标，供数据质量过滤。 */
        val includeMetrics: Boolean = true,
        /** 有值时按最后一次 assistant 的 tokensPerSec 过滤；无实测值同样剔除。 */
        val minTokensPerSec: Float? = null,
        /** 分支标签；缺省用分支末节点 id 保证可追溯。 */
        val branchLabel: String? = null,
        val extraMeta: Map<String, String> = emptyMap(),
    )

    /** 导出一条分支；不可用于训练（空样本、未完成调用、失败步骤或低于阈值）时返回 null。 */
    fun export(branch: List<SessionNode>, options: Options = Options()): String? {
        if (branch.isEmpty()) return null

        val declaredCalls = LinkedHashSet<String>()
        branch.filter { it.role == SessionRole.ASSISTANT }.forEach { assistant ->
            assistant.toolCalls.forEach { declaredCalls += it.callId }
        }
        val toolNodes = branch.filter { it.role == SessionRole.TOOL }
        // 有 assistant 调用却没有对应 tool 结果：PENDING 或轮次被打断，不产生训练噪声。
        val unfinished = declaredCalls.any { callId -> toolNodes.none { it.callId == callId } }
        if (unfinished) return null
        // 工具结果回链不到调用（跨分支残留），messages 结构不合法。
        val orphanTool = toolNodes.any { it.callId != null && it.callId !in declaredCalls }
        if (orphanTool) return null

        val rejected = toolNodes.any {
            it.toolStatus == ToolOutcomeStatus.FAILURE || it.toolStatus == ToolOutcomeStatus.DENIED
        }
        if (rejected && !options.includeRejected) return null

        val lastAssistant = branch.lastOrNull {
            it.role == SessionRole.ASSISTANT && it.parseRetries == 0
        }
        options.minTokensPerSec?.let { threshold ->
            val measured = lastAssistant?.metrics?.tokensPerSec ?: return null
            if (measured < threshold) return null
        }

        val messages = mutableListOf<JsonObject>()
        val systemTexts = mutableListOf<String>()
        options.systemPrompt?.takeIf { it.isNotBlank() }?.let { systemTexts += it }
        if (options.systemPrompt == null) {
            branch.filter { it.role == SessionRole.SYSTEM }.forEach { systemTexts += it.content }
        }
        if (options.includeSummaryAsSystem) {
            branch.filter { it.role == SessionRole.SUMMARY }.forEach { systemTexts += it.content }
        }
        systemTexts.forEach { text ->
            messages += buildJsonObject {
                put("role", "system")
                put("content", text)
            }
        }

        for (node in branch) {
            when (node.role) {
                SessionRole.USER -> messages += message(node.role, node.content)

                SessionRole.ASSISTANT -> {
                    if (node.parseRetries > 0) continue
                    messages += buildJsonObject {
                        put("role", "assistant")
                        put("content", node.content)
                        if (node.toolCalls.isNotEmpty()) {
                            put("tool_calls", JsonArray(node.toolCalls.map { toolCallJson(it) }))
                        }
                    }
                }

                SessionRole.TOOL -> {
                    // 无 callId 的 tool 节点是系统级回注（如解析纠错），不属于工具消息。
                    val callId = node.callId ?: continue
                    val failed = node.toolStatus == ToolOutcomeStatus.FAILURE ||
                        node.toolStatus == ToolOutcomeStatus.DENIED
                    messages += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", callId)
                        put("content", node.content)
                        if (failed) {
                            put("rejected", true)
                            node.errorCode?.let { put("errorCode", it) }
                        }
                    }
                }

                SessionRole.SYSTEM, SessionRole.SUMMARY -> Unit
            }
        }

        val userCount = messages.count { roleOf(it) == "user" }
        val assistantCount = messages.count { roleOf(it) == "assistant" }
        if (userCount == 0 || assistantCount == 0) return null

        val meta = buildJsonObject {
            put("branch", options.branchLabel ?: branch.last().id)
            if (options.includeMetrics) {
                lastAssistant?.metrics?.backend?.let { put("backend", it) }
                lastAssistant?.metrics?.promptTokens?.let { put("promptTokens", it) }
                lastAssistant?.metrics?.completionTokens?.let { put("completionTokens", it) }
                lastAssistant?.metrics?.tokensPerSec?.let { put("tokensPerSec", it) }
            }
            if (options.includeRejected && rejected) put("rejected", true)
            options.extraMeta.forEach { (key, value) -> put(key, value) }
        }

        return buildJsonObject {
            put("messages", JsonArray(messages))
            put("meta", meta)
        }.toString()
    }

    /** 批量导出多条分支，保持输入顺序，自动跳过不可用分支。 */
    fun exportLines(branches: Iterable<List<SessionNode>>, options: Options = Options()): List<String> =
        branches.mapNotNull { export(it, options) }

    private fun message(role: SessionRole, content: String): JsonObject = buildJsonObject {
        put("role", role.wire)
        put("content", content)
    }

    private fun toolCallJson(call: SessionToolCall): JsonObject = buildJsonObject {
        put("id", call.callId)
        put("function", buildJsonObject {
            put("name", call.tool)
            put("arguments", call.argsJson)
        })
    }

    private fun roleOf(message: JsonObject): String? {
        val primitive = message["role"] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }
}
