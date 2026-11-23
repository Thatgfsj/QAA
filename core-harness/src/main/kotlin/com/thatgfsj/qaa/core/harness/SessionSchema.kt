package com.thatgfsj.qaa.core.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 会话 JSONL 落盘 schema（设计文档 5.2/5.3，Issue #15）。
 *
 * 一行一条记录：[RECORD_TYPE_NODE] 为树节点，[RECORD_TYPE_META] 为活动叶指针（后写覆盖前写）。
 * 读端忽略未知字段，仅破坏性变更才递增 [SESSION_SCHEMA_VERSION]。
 */
const val SESSION_SCHEMA_VERSION: Int = 1

/** 记录类型取值（设计文档 5.3 recordType）。 */
const val RECORD_TYPE_NODE: String = "node"
const val RECORD_TYPE_META: String = "meta"

/** 节点角色（设计文档 5.3 role）。 */
enum class SessionRole(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
    SUMMARY("summary"),
    ;

    companion object {
        /** 未知角色返回 null（前向兼容：读端跳过而非崩溃）。 */
        fun fromWire(value: String?): SessionRole? = entries.firstOrNull { it.wire == value }
    }
}

/** assistant.toolCalls 元素（设计文档 5.2）。 */
data class SessionToolCall(
    val callId: String,
    val tool: String,
    val argsJson: String,
)

/** summary.compaction 引用（设计文档 5.3/6.4）。 */
data class SummaryCompaction(
    val coveredFrom: String,
    val coveredTo: String,
    val nodeCount: Int,
    val estimatedTokens: Long,
    val createdAt: Long,
)

/**
 * 一个树节点，字段与设计文档 5.2/5.3 一一对应。
 *
 * [durationMs] 仅 tool 节点使用；assistant 的生成耗时位于 [GenerationMetrics.durationMs]。
 * [stepIndex] 与 [parseRetries] 按 schema 写入 metrics 对象，读端再展开回本模型。
 */
data class SessionNode(
    val id: String,
    val parentId: String?,
    val role: SessionRole,
    val content: String,
    val toolCalls: List<SessionToolCall> = emptyList(),
    val callId: String? = null,
    val tool: String? = null,
    val toolStatus: ToolOutcomeStatus? = null,
    val errorCode: String? = null,
    val tags: List<String> = emptyList(),
    val timestamp: Long = 0L,
    val metrics: GenerationMetrics? = null,
    val stepIndex: Int? = null,
    val parseRetries: Int = 0,
    val durationMs: Long? = null,
    val compaction: SummaryCompaction? = null,
)

/** 加载结果报告（设计文档 5.1：损坏行跳过并计数上报）。 */
data class SessionLoadReport(
    val corruptLines: Int = 0,
    val skippedRecords: Int = 0,
    val duplicateNodeIds: Int = 0,
    val warnings: List<String> = emptyList(),
    val schemaVersions: Set<Int> = emptySet(),
) {
    val isClean: Boolean
        get() = corruptLines == 0 && skippedRecords == 0 && duplicateNodeIds == 0
}

/** JSONL 记录编解码（不依赖序列化插件，按 schema 手写字段映射以控制容错）。 */
internal object SessionJson {

    fun encodeNode(node: SessionNode): JsonObject = buildJsonObject {
        put("recordType", RECORD_TYPE_NODE)
        put("schemaVersion", SESSION_SCHEMA_VERSION)
        put("id", node.id)
        put("parentId", node.parentId.toJsonOrNull())
        put("role", node.role.wire)
        put("content", node.content)
        put("toolCalls", JsonArray(node.toolCalls.map { call ->
            buildJsonObject {
                put("callId", call.callId)
                put("tool", call.tool)
                put("argsJson", call.argsJson)
            }
        }))
        if (node.callId != null) put("callId", node.callId)
        if (node.tool != null) put("tool", node.tool)
        if (node.toolStatus != null) put("toolStatus", node.toolStatus.name)
        if (node.errorCode != null) put("errorCode", node.errorCode)
        put("tags", JsonArray(node.tags.map { JsonPrimitive(it) }))
        put("timestamp", node.timestamp)
        put("metrics", encodeMetrics(node))
        put("compaction", encodeCompaction(node.compaction))
    }

    fun encodeMeta(activeLeafId: String?, updatedAt: Long): JsonObject = buildJsonObject {
        put("recordType", RECORD_TYPE_META)
        put("schemaVersion", SESSION_SCHEMA_VERSION)
        put("activeLeafId", activeLeafId.toJsonOrNull())
        put("updatedAt", updatedAt)
    }

    /** 解码单行 node 记录；字段缺失/类型不符时尽量降级，id 或 role 不可用时返回 null。 */
    fun decodeNode(record: JsonObject): SessionNode? {
        val id = record.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
        val role = SessionRole.fromWire(record.stringOrNull("role")) ?: return null
        val metricsObject = record["metrics"] as? JsonObject
        val generation = if (role == SessionRole.ASSISTANT) metricsObject?.toGenerationMetrics() else null
        val stepIndex = metricsObject?.intOrNull("stepIndex") ?: record.intOrNull("stepIndex")
        val parseRetries = metricsObject?.intOrNull("parseRetries") ?: record.intOrNull("parseRetries") ?: 0
        val durationMs = if (role == SessionRole.TOOL) {
            record.longOrNull("durationMs") ?: metricsObject?.longOrNull("durationMs")
        } else {
            null
        }
        return SessionNode(
            id = id,
            parentId = record.stringOrNull("parentId"),
            role = role,
            content = record.stringOrNull("content").orEmpty(),
            toolCalls = record.toolCallsOrEmpty(),
            callId = record.stringOrNull("callId"),
            tool = record.stringOrNull("tool"),
            toolStatus = record.stringOrNull("toolStatus")?.let { wire ->
                ToolOutcomeStatus.entries.firstOrNull { it.name == wire }
            },
            errorCode = record.stringOrNull("errorCode"),
            tags = record.tagsOrEmpty(),
            timestamp = record.longOrNull("timestamp") ?: 0L,
            metrics = generation,
            stepIndex = stepIndex,
            parseRetries = parseRetries.coerceAtLeast(0),
            durationMs = durationMs,
            compaction = record.compactionOrNull(),
        )
    }

    private fun encodeMetrics(node: SessionNode): JsonElement {
        val metrics = buildJsonObject {
            node.metrics?.let { generation ->
                generation.backend?.let { put("backend", it) }
                generation.ttftMs?.let { put("ttftMs", it) }
                generation.tokensPerSec?.let { put("tokensPerSec", it) }
                generation.promptTokens?.let { put("promptTokens", it) }
                generation.completionTokens?.let { put("completionTokens", it) }
            }
            node.stepIndex?.let { put("stepIndex", it) }
            if (node.role == SessionRole.ASSISTANT) put("parseRetries", node.parseRetries)
            val durationMs = if (node.role == SessionRole.TOOL) node.durationMs else node.metrics?.durationMs
            durationMs?.let { put("durationMs", it) }
        }
        return if (metrics.isEmpty()) JsonNull else metrics
    }

    private fun encodeCompaction(compaction: SummaryCompaction?): JsonElement =
        compaction?.let {
            buildJsonObject {
                put("coveredFrom", it.coveredFrom)
                put("coveredTo", it.coveredTo)
                put("nodeCount", it.nodeCount)
                put("estimatedTokens", it.estimatedTokens)
                put("createdAt", it.createdAt)
            }
        } ?: JsonNull

    private fun JsonObject.toGenerationMetrics(): GenerationMetrics? {
        val backend = stringOrNull("backend")
        val ttftMs = longOrNull("ttftMs")
        val tokensPerSec = floatOrNull("tokensPerSec")
        val promptTokens = intOrNull("promptTokens")
        val completionTokens = intOrNull("completionTokens")
        val durationMs = longOrNull("durationMs")
        val empty = backend == null && ttftMs == null && tokensPerSec == null &&
            promptTokens == null && completionTokens == null && durationMs == null
        if (empty) return null
        return GenerationMetrics(
            backend = backend,
            ttftMs = ttftMs,
            tokensPerSec = tokensPerSec,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            durationMs = durationMs,
        )
    }

    private fun JsonObject.toolCallsOrEmpty(): List<SessionToolCall> {
        val array = this["toolCalls"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val call = element as? JsonObject ?: return@mapNotNull null
            val callId = call.stringOrNull("callId") ?: return@mapNotNull null
            val tool = call.stringOrNull("tool") ?: return@mapNotNull null
            val argsJson = call.stringOrNull("argsJson")
                ?: call["argsJson"]?.takeIf { it !is JsonNull }?.toString()
                ?: "{}"
            SessionToolCall(callId, tool, argsJson)
        }
    }

    private fun JsonObject.tagsOrEmpty(): List<String> =
        (this["tags"] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

    private fun JsonObject.compactionOrNull(): SummaryCompaction? {
        val record = this["compaction"] as? JsonObject ?: return null
        val coveredFrom = record.stringOrNull("coveredFrom") ?: return null
        val coveredTo = record.stringOrNull("coveredTo") ?: return null
        return SummaryCompaction(
            coveredFrom = coveredFrom,
            coveredTo = coveredTo,
            nodeCount = record.intOrNull("nodeCount") ?: 0,
            estimatedTokens = record.longOrNull("estimatedTokens") ?: 0L,
            createdAt = record.longOrNull("createdAt") ?: 0L,
        )
    }

    private fun String?.toJsonOrNull(): JsonElement =
        this?.let { JsonPrimitive(it) } ?: JsonNull
}

internal fun JsonObject.stringOrNull(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}

internal fun JsonObject.longOrNull(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toLongOrNull() ?: primitive.content.toDoubleOrNull()?.toLong()
}

internal fun JsonObject.intOrNull(key: String): Int? = longOrNull(key)?.toInt()

internal fun JsonObject.floatOrNull(key: String): Float? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toFloatOrNull()
}
