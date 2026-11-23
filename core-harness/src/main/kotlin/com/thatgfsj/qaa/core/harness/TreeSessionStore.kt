package com.thatgfsj.qaa.core.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** 分叉结果（设计文档 5.4）。 */
sealed interface ForkResult {
    /** 分叉成功；[previousLeafId] 为分叉前的活动叶，便于回退提示。 */
    data class Success(val forkPointId: String, val previousLeafId: String?) : ForkResult
    data class NodeNotFound(val nodeId: String) : ForkResult
    /** 空会话（无活动叶）无法分叉。 */
    data object EmptySession : ForkResult
    /** Agent 轮次进行中，拒绝改树指针。 */
    data object TurnInProgress : ForkResult
}

/**
 * 树状 JSONL 会话存储（设计文档 5.1–5.4，Issue #15）。
 *
 * 单会话单文件，所有写入均为追加：每个节点写 node 行后紧跟一条 meta 行，
 * 因此文件尾崩溃时活动叶可回落到最后一条完整记录。加载时损坏行跳过并计入
 * [lastLoadReport]；meta 后写覆盖前写，活动叶以最后一条有效 meta 为准。
 *
 * 同一文件只允许一个写者；[file] 为 null 时退化为纯内存存储（测试与回放用）。
 */
class TreeSessionStore(
    val sessionId: String,
    val file: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val warningSink: (String) -> Unit = {},
) : AutoCloseable {

    private val nodesById = LinkedHashMap<String, SessionNode>()
    private var leafId: String? = null
    private var nextOrdinal = 1
    private var out: BufferedWriter? = null
    private var closed = false
    private var report = SessionLoadReport()

    init {
        require(sessionId.isNotBlank()) { "sessionId 不能为空" }
        if (file != null) {
            file.absoluteFile.parentFile?.mkdirs()
            loadFromDisk()
            if (!file.exists() || file.length() == 0L) {
                // 首行写一条 meta（设计文档 5.1）；空会话活动叶为 null。
                appendRecord(SessionJson.encodeMeta(activeLeafId = null, updatedAt = clock()))
            }
        }
    }

    /** 当前活动叶（最近一次 append 或 fork 的位置）。 */
    val activeLeafId: String?
        @Synchronized get() = leafId

    /** 最近一次磁盘加载的诊断信息。 */
    val lastLoadReport: SessionLoadReport
        @Synchronized get() = report

    /** Agent 轮次进行中的标记：为 true 时拒绝 /fork（设计文档 5.4）。 */
    val isTurnActive: Boolean
        @Synchronized get() = turnActive

    private var turnActive = false

    @Synchronized
    fun nodeCount(): Int = nodesById.size

    @Synchronized
    fun node(id: String): SessionNode? = nodesById[id]

    /** 按写入顺序返回全部节点（跨分支，含被放弃的分支）。 */
    @Synchronized
    fun allNodes(): List<SessionNode> = nodesById.values.toList()

    @Synchronized
    fun childrenOf(id: String): List<SessionNode> =
        nodesById.values.filter { it.parentId == id }

    /**
     * 追加一个节点：parentId 取当前活动叶，落盘后活动叶前移，返回带 id 的节点。
     *
     * assistant 节点的 toolCalls 由本方法分配 callId（c-<节点 id 数字部分>-<序号>，
     * 如 c-000123-0），供 tool 节点回链（设计文档 5.2/5.3）。
     */
    @Synchronized
    fun append(
        role: SessionRole,
        content: String,
        tags: List<String> = emptyList(),
        toolCalls: List<ToolCall> = emptyList(),
        toolStatus: ToolOutcomeStatus? = null,
        errorCode: String? = null,
        stepIndex: Int? = null,
        parseRetries: Int = 0,
        metrics: GenerationMetrics? = null,
        durationMs: Long? = null,
        compaction: SummaryCompaction? = null,
        callId: String? = null,
        tool: String? = null,
    ): SessionNode {
        checkOpen()
        val id = nextNodeId()
        val calls = if (role == SessionRole.ASSISTANT) {
            val idSuffix = id.removePrefix(NODE_ID_PREFIX)
            toolCalls.mapIndexed { index, call ->
                SessionToolCall(callId = "c-$idSuffix-$index", tool = call.tool, argsJson = call.argsJson)
            }
        } else {
            emptyList()
        }
        val node = SessionNode(
            id = id,
            parentId = leafId,
            role = role,
            content = content,
            toolCalls = calls,
            callId = callId,
            tool = tool,
            toolStatus = toolStatus,
            errorCode = errorCode,
            tags = tags,
            timestamp = clock(),
            metrics = metrics,
            stepIndex = stepIndex,
            parseRetries = parseRetries.coerceAtLeast(0),
            durationMs = durationMs,
            compaction = compaction,
        )
        nodesById[id] = node
        leafId = id
        persist(node)
        return node
    }

    /**
     * 以指定节点为分叉点开新分支（设计文档 5.4）：仅追加一条 meta，activeLeafId 回指分叉点，
     * 后续节点的 parentId 链回溯至分叉点；原分支节点完整保留、不复制。
     *
     * [nodeId] 为 null 时取当前活动叶；节点不存在或轮次进行中均不改动树。
     */
    @Synchronized
    fun fork(nodeId: String? = null): ForkResult {
        checkOpen()
        if (turnActive) return ForkResult.TurnInProgress
        val targetId = nodeId ?: leafId ?: return ForkResult.EmptySession
        val target = nodesById[targetId] ?: return ForkResult.NodeNotFound(targetId)
        val previous = leafId
        leafId = target.id
        if (file != null) {
            appendRecord(SessionJson.encodeMeta(activeLeafId = target.id, updatedAt = clock()))
        }
        return ForkResult.Success(target.id, previous)
    }

    /** 轮次进行中标记；由装配层在 runTurn 前后置位。 */
    @Synchronized
    fun markTurnActive(active: Boolean) {
        turnActive = active
    }

    /** 从 [leafNodeId]（默认活动叶）沿 parentId 回溯出根→叶的线性序列。 */
    @Synchronized
    fun branchTo(leafNodeId: String? = leafId): List<SessionNode> {
        if (leafNodeId == null) return emptyList()
        val chain = ArrayList<SessionNode>()
        val visited = HashSet<String>()
        var current: String? = leafNodeId
        while (current != null) {
            val node = nodesById[current] ?: break
            if (!visited.add(current)) break
            chain.add(node)
            current = node.parentId
        }
        chain.reverse()
        return chain
    }

    fun activeBranch(): List<SessionNode> = branchTo()

    /** 活动分支的 [TurnNode] 视图（供 PromptAssembler / TurnRecorder 消费）。 */
    @Synchronized
    fun snapshot(): List<TurnNode> {
        val branch = branchTo()
        val callsById = HashMap<String, SessionToolCall>()
        branch.filter { it.role == SessionRole.ASSISTANT }.forEach { assistant ->
            assistant.toolCalls.forEach { callsById[it.callId] = it }
        }
        return branch.mapNotNull { node ->
            val role = when (node.role) {
                SessionRole.USER -> TurnRole.USER
                SessionRole.ASSISTANT -> TurnRole.ASSISTANT
                SessionRole.TOOL -> TurnRole.TOOL
                else -> null
            } ?: return@mapNotNull null
            val calls = when (role) {
                TurnRole.ASSISTANT -> node.toolCalls.map { ToolCall(it.tool, it.argsJson) }
                TurnRole.TOOL -> node.callId?.let { callsById[it] }
                    ?.let { listOf(ToolCall(it.tool, it.argsJson)) }
                    .orEmpty()
                TurnRole.USER -> emptyList()
            }
            TurnNode(
                role = role,
                content = node.content,
                toolCalls = calls,
                tags = node.tags,
                parseRetries = node.parseRetries,
                metrics = node.metrics,
                stepIndex = node.stepIndex,
                toolStatus = node.toolStatus,
                errorCode = node.errorCode,
                durationMs = node.durationMs,
            )
        }
    }

    /**
     * 渲染整棵会话树（设计文档 5.4）：节点 id、role、内容首行摘要、工具状态、
     * 分叉点标记；活动分支以 ● 标记，当前活动叶标注“← 活动叶子”。
     */
    @Synchronized
    fun renderTree(): String {
        if (nodesById.isEmpty()) return EMPTY_SESSION_TEXT
        val activePath = branchTo().mapTo(HashSet()) { it.id }
        val childCounts = nodesById.values.groupingBy { it.parentId }.eachCount()
        val childrenByParent = nodesById.values
            .filter { it.parentId != null }
            .groupBy { it.parentId!! }
        val roots = nodesById.values.filter { it.parentId == null || it.parentId !in nodesById }
        val builder = StringBuilder()
        val stack = ArrayDeque<RenderFrame>()
        for (index in roots.indices.reversed()) {
            stack.addLast(RenderFrame(roots[index], prefix = "", isLast = index == roots.lastIndex, isRoot = true))
        }
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            if (!frame.isRoot) {
                builder.append(frame.prefix).append(if (frame.isLast) BRANCH_LAST else BRANCH_MIDDLE)
            }
            builder.append(if (frame.node.id in activePath) ACTIVE_MARK else IDLE_MARK)
            builder.append(describeNode(frame.node, childCounts))
            builder.append('\n')
            val childPrefix = when {
                frame.isRoot -> ""
                frame.isLast -> frame.prefix + INDENT_BLANK
                else -> frame.prefix + INDENT_BAR
            }
            val children = childrenByParent[frame.node.id].orEmpty()
            for (index in children.indices.reversed()) {
                stack.addLast(RenderFrame(children[index], childPrefix, index == children.lastIndex, isRoot = false))
            }
        }
        return builder.toString().trimEnd()
    }

    /** 重新从磁盘加载（关闭并重开写句柄）；用于外部改写文件后的刷新。 */
    @Synchronized
    fun reload() {
        checkOpen()
        closeWriter()
        loadFromDisk()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        closeWriter()
    }

    private fun checkOpen() {
        check(!closed) { "会话已关闭：$sessionId" }
    }

    private fun describeNode(node: SessionNode, childCounts: Map<String?, Int>): String {
        val builder = StringBuilder(node.id)
        builder.append(" [").append(node.role.wire)
        if (node.role == SessionRole.TOOL && node.tool != null) builder.append('/').append(node.tool)
        builder.append("] ").append(summarize(node.content))
        node.toolStatus?.let { builder.append(' ').append(it.name) }
        if (node.role == SessionRole.ASSISTANT && node.toolCalls.isNotEmpty()) {
            builder.append(" ⇢ ").append(node.toolCalls.joinToString(" ") { it.tool })
        }
        if ((childCounts[node.id] ?: 0) > 1) builder.append(" ⑂分叉点")
        if (node.id == leafId) builder.append(" ← 活动叶子")
        return builder.toString()
    }

    private fun summarize(content: String): String {
        val firstLine = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstLine.isEmpty()) return BLANK_CONTENT
        val compact = WHITESPACE.replace(firstLine, " ")
        return if (compact.length <= SUMMARY_LIMIT) compact else compact.take(SUMMARY_LIMIT) + "…"
    }

    private fun nextNodeId(): String {
        var candidate: String
        do {
            candidate = NODE_ID_PREFIX + nextOrdinal.toString().padStart(NODE_ID_PAD, '0')
            nextOrdinal++
        } while (nodesById.containsKey(candidate))
        return candidate
    }

    private fun persist(node: SessionNode) {
        if (file == null) return
        appendRecord(SessionJson.encodeNode(node))
        appendRecord(SessionJson.encodeMeta(activeLeafId = leafId, updatedAt = clock()))
    }

    private fun appendRecord(record: JsonObject) {
        val target = file ?: return
        val writer = out ?: BufferedWriter(
            OutputStreamWriter(FileOutputStream(target, /* append = */ true), StandardCharsets.UTF_8),
        ).also { out = it }
        writer.write(record.toString())
        writer.newLine()
        writer.flush()
    }

    private fun closeWriter() {
        val writer = out ?: return
        out = null
        try {
            writer.flush()
        } catch (_: IOException) {
            // 关闭阶段的刷盘失败不影响已写入记录。
        }
        try {
            writer.close()
        } catch (_: IOException) {
            // 同上。
        }
    }

    private fun loadFromDisk() {
        nodesById.clear()
        leafId = null
        nextOrdinal = 1
        var corruptLines = 0
        var skippedRecords = 0
        var duplicateIds = 0
        var lastValidId: String? = null
        var metaLeafId: String? = null
        var lastRecordType: String? = null
        var lineNumber = 0
        val warnings = mutableListOf<String>()
        val versions = linkedSetOf<Int>()

        fun warn(message: String) {
            if (warnings.size < MAX_WARNINGS) warnings += message
            warningSink(message)
        }

        val target = file
        if (target != null && target.exists()) {
            try {
                target.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        lineNumber++
                        val raw = line.trim()
                        if (raw.isEmpty()) continue
                        val record = try {
                            Json.parseToJsonElement(raw) as? JsonObject
                        } catch (_: Exception) {
                            null
                        }
                        if (record == null) {
                            corruptLines++
                            warn("第 $lineNumber 行不是合法 JSON 记录，已跳过")
                            continue
                        }
                        record.intOrNull("schemaVersion")?.let { versions += it }
                        when (record.stringOrNull("recordType")) {
                            RECORD_TYPE_NODE -> {
                                val node = SessionJson.decodeNode(record)
                                if (node == null) {
                                    corruptLines++
                                    warn("第 $lineNumber 行节点缺少必要字段，已跳过")
                                    continue
                                }
                                if (nodesById.containsKey(node.id)) {
                                    duplicateIds++
                                    warn("第 $lineNumber 行节点 id 重复（" + node.id + "），已跳过")
                                    continue
                                }
                                nodesById[node.id] = node
                                lastValidId = node.id
                                lastRecordType = RECORD_TYPE_NODE
                                ordinalOf(node.id)?.let { nextOrdinal = maxOf(nextOrdinal, it + 1) }
                            }

                            RECORD_TYPE_META -> {
                                metaLeafId = record.stringOrNull("activeLeafId")
                                lastRecordType = RECORD_TYPE_META
                            }

                            else -> {
                                skippedRecords++
                                warn("第 $lineNumber 行 recordType 未知，已跳过")
                            }
                        }
                    }
                }
            } catch (error: IOException) {
                warn("会话文件读取中断（第 $lineNumber 行）：" + error.message)
            }
        }

        // 尾行半写：最后一个有效记录是 node 说明配对的 meta 未落盘，回退到该节点。
        val crashedAfterNode = lastRecordType == RECORD_TYPE_NODE && lastValidId != null
        val resolvedLeaf = when {
            crashedAfterNode -> {
                warn("活动叶指针缺失（疑似尾行半写），已回退到最后一个有效节点 " + lastValidId)
                lastValidId
            }

            metaLeafId != null && nodesById.containsKey(metaLeafId) -> metaLeafId

            else -> {
                if (metaLeafId != null) warn("活动叶 " + metaLeafId + " 不存在，已回退到最后一个有效节点")
                lastValidId
            }
        }
        leafId = resolvedLeaf
        if (corruptLines > 0) warn("本次加载跳过 " + corruptLines + " 行损坏记录（共 " + lineNumber + " 行）")
        report = SessionLoadReport(
            corruptLines = corruptLines,
            skippedRecords = skippedRecords,
            duplicateNodeIds = duplicateIds,
            warnings = warnings.toList(),
            schemaVersions = versions.toSet(),
        )
    }

    private fun ordinalOf(id: String): Int? =
        if (id.startsWith(NODE_ID_PREFIX)) id.removePrefix(NODE_ID_PREFIX).toIntOrNull() else null

    private data class RenderFrame(
        val node: SessionNode,
        val prefix: String,
        val isLast: Boolean,
        val isRoot: Boolean,
    )

    companion object {
        /** 默认会话目录（设计文档 5.1：sessions/<sessionId>.jsonl）。 */
        const val DEFAULT_SESSIONS_DIR: String = "sessions"
        const val EMPTY_SESSION_TEXT: String = "（空会话）"
        const val BLANK_CONTENT: String = "（空）"

        private const val NODE_ID_PREFIX = "n-"
        private const val NODE_ID_PAD = 6
        private const val MAX_WARNINGS = 20
        private const val SUMMARY_LIMIT = 48
        private const val ACTIVE_MARK = "● "
        private const val IDLE_MARK = "○ "
        private const val BRANCH_MIDDLE = "├─"
        private const val BRANCH_LAST = "└─"
        private const val INDENT_BAR = "│  "
        private const val INDENT_BLANK = "   "
        private val WHITESPACE = Regex("\\s+")

        fun fileFor(directory: File, sessionId: String): File = File(directory, sessionId + ".jsonl")
    }
}
