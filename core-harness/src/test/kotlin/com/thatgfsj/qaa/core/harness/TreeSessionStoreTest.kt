package com.thatgfsj.qaa.core.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TreeSessionStore JVM 单测（设计附录 A.5：S1–S7，另含树打印、分支线性化与空会话）。
 */
class TreeSessionStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newFile(): File = File(tempFolder.newFolder(), "s1.jsonl")

    private fun nodeLine(id: String, parentId: String?, role: String, content: String): String {
        val parent = if (parentId == null) "null" else "\"" + parentId + "\""
        return "{\"recordType\":\"node\",\"schemaVersion\":1,\"id\":\"" + id +
            "\",\"parentId\":" + parent + ",\"role\":\"" + role + "\",\"content\":\"" + content +
            "\",\"toolCalls\":[],\"tags\":[],\"timestamp\":1,\"metrics\":null,\"compaction\":null}"
    }

    @Test
    fun S1_追加回读一致_节点字段无损() {
        val file = newFile()
        val original = TreeSessionStore("s1", file, clock = { FIXED_TIME })
        val user = original.append(SessionRole.USER, "把 notes.txt 里的标题改成大写", tags = listOf("steer"))
        val assistant = original.append(
            role = SessionRole.ASSISTANT,
            content = "我先读取该文件。",
            toolCalls = listOf(ToolCall("read", "{\"path\":\"notes.txt\"}")),
            stepIndex = 0,
            metrics = GenerationMetrics("NPU", 210L, 18.4f, 1350, 96, 4200L),
        )
        val tool = original.append(
            role = SessionRole.TOOL,
            content = "（文件内容）",
            callId = assistant.toolCalls.single().callId,
            tool = "read",
            toolStatus = ToolOutcomeStatus.SUCCESS,
            stepIndex = 0,
            durationMs = 12L,
        )
        val summary = original.append(
            role = SessionRole.SUMMARY,
            content = "用户目标：整理笔记。",
            compaction = SummaryCompaction(user.id, tool.id, 3, 5200L, FIXED_TIME + 100),
        )
        original.close()

        val reloaded = TreeSessionStore("s1", file)
        try {
            assertEquals(4, reloaded.nodeCount())
            assertEquals(user, reloaded.node(user.id))
            assertEquals(assistant, reloaded.node(assistant.id))
            assertEquals(tool, reloaded.node(tool.id))
            assertEquals(summary, reloaded.node(summary.id))
            assertEquals(0, reloaded.lastLoadReport.corruptLines)
        } finally {
            reloaded.close()
        }
    }

    @Test
    fun S2_meta后写覆盖_活动叶以最后一条为准() {
        val file = newFile()
        val store = TreeSessionStore("s1", file)
        store.append(SessionRole.USER, "第一问")
        val branchPoint = store.append(SessionRole.ASSISTANT, "第一答")
        store.append(SessionRole.USER, "第二问")

        val result = store.fork(branchPoint.id)
        assertTrue(result is ForkResult.Success)
        assertEquals(branchPoint.id, store.activeLeafId)
        store.close()

        val reloaded = TreeSessionStore("s1", file)
        try {
            assertEquals(branchPoint.id, reloaded.activeLeafId)
            val metaLeaves = file.readLines()
                .mapNotNull { line -> Json.parseToJsonElement(line) as? JsonObject }
                .filter { it["recordType"]?.jsonPrimitive?.content == RECORD_TYPE_META }
                .map { it["activeLeafId"]?.let { leaf -> if (leaf is JsonNull) null else leaf.jsonPrimitive.content } }
            assertTrue(metaLeaves.size >= 4)
            assertEquals(branchPoint.id, metaLeaves.last())
        } finally {
            reloaded.close()
        }
    }

    @Test
    fun S3_分叉_缺参与带参_原分支保留() {
        val store = TreeSessionStore("s1")
        val firstUser = store.append(SessionRole.USER, "第一问")
        val firstAnswer = store.append(SessionRole.ASSISTANT, "回答一")

        // 缺参：以当前活动叶为分叉点开新分支。
        assertTrue(store.fork() is ForkResult.Success)
        assertEquals(firstAnswer.id, store.activeLeafId)
        val followUp = store.append(SessionRole.USER, "追问")
        assertEquals(firstAnswer.id, followUp.parentId)
        assertEquals(listOf(firstUser.id, firstAnswer.id, followUp.id), store.branchTo().map { it.id })

        // 带参：fork 回同一分叉点，原分支保留且可再次回溯。
        assertTrue(store.fork(firstAnswer.id) is ForkResult.Success)
        val alternative = store.append(SessionRole.ASSISTANT, "另一条回答")
        assertEquals(firstAnswer.id, alternative.parentId)
        assertEquals(listOf(firstUser.id, firstAnswer.id, alternative.id), store.branchTo().map { it.id })
        assertEquals(listOf(firstUser.id, firstAnswer.id, followUp.id), store.branchTo(followUp.id).map { it.id })
        assertEquals(4, store.nodeCount())

        val tree = store.renderTree()
        assertTrue(tree.contains(followUp.id))
        assertTrue(tree.contains(alternative.id))
        assertTrue(tree.contains("⑂分叉点"))
    }

    @Test
    fun S4_分叉非法id_报错且树不变() {
        val store = TreeSessionStore("s1")
        store.append(SessionRole.USER, "一")
        val leaf = store.activeLeafId
        val before = store.allNodes()

        assertEquals(ForkResult.NodeNotFound("n-999999"), store.fork("n-999999"))
        assertEquals(leaf, store.activeLeafId)
        assertEquals(before, store.allNodes())
    }

    @Test
    fun S5_轮次进行中拒绝fork() {
        val file = newFile()
        val store = TreeSessionStore("s1", file)
        try {
            val user = store.append(SessionRole.USER, "一")
            val assistant = store.append(SessionRole.ASSISTANT, "二")
            val linesBefore = file.readLines().size

            store.markTurnActive(true)
            assertEquals(ForkResult.TurnInProgress, store.fork(user.id))
            assertEquals(assistant.id, store.activeLeafId)
            assertEquals(linesBefore, file.readLines().size)

            store.markTurnActive(false)
            assertTrue(store.fork(user.id) is ForkResult.Success)
            assertEquals(user.id, store.activeLeafId)
        } finally {
            store.close()
        }
    }

    @Test
    fun S6_尾行半写容忍() {
        val file = newFile()
        val broken = "{\"recordType\":\"node\",\"schemaVersion\":1,\"id\":\"n-000003\",\"parentId\":\"n-000002\",\"rol"
        file.writeText(
            listOf(
                "{\"recordType\":\"meta\",\"schemaVersion\":1,\"activeLeafId\":null,\"updatedAt\":1}",
                nodeLine("n-000001", null, "user", "问题"),
                "{\"recordType\":\"meta\",\"schemaVersion\":1,\"activeLeafId\":\"n-000001\",\"updatedAt\":2}",
                nodeLine("n-000002", "n-000001", "assistant", "回答"),
                "{\"recordType\":\"meta\",\"schemaVersion\":1,\"activeLeafId\":\"n-000002\",\"updatedAt\":3}",
                broken,
            ).joinToString("\n") + "\n",
        )

        val store = TreeSessionStore("s1", file)
        try {
            assertEquals(2, store.nodeCount())
            assertEquals("n-000002", store.activeLeafId)
            assertEquals(1, store.lastLoadReport.corruptLines)
            assertTrue(store.lastLoadReport.warnings.isNotEmpty())
            assertFalse(store.lastLoadReport.isClean)
        } finally {
            store.close()
        }
    }

    @Test
    fun S7_前向兼容_未知字段与更高版本不崩溃() {
        val file = newFile()
        file.writeText(
            "{\"recordType\":\"node\",\"schemaVersion\":2,\"id\":\"n-000001\",\"parentId\":null," +
                "\"role\":\"user\",\"content\":\"新版本节点\",\"future\":{\"x\":1},\"timestamp\":1}\n" +
                "{\"recordType\":\"meta\",\"schemaVersion\":2,\"activeLeafId\":\"n-000001\",\"updatedAt\":2}\n",
        )

        val store = TreeSessionStore("s1", file)
        try {
            assertEquals(1, store.nodeCount())
            assertEquals("新版本节点", store.node("n-000001")?.content)
            assertEquals(0, store.lastLoadReport.corruptLines)
            assertTrue(store.lastLoadReport.schemaVersions.contains(2))
            assertEquals("n-000001", store.activeLeafId)
        } finally {
            store.close()
        }
    }

    @Test
    fun 加载_未知记录与重复id跳过计数() {
        val file = newFile()
        file.writeText(
            listOf(
                "{\"recordType\":\"meta\",\"schemaVersion\":1,\"activeLeafId\":null,\"updatedAt\":1}",
                nodeLine("n-000001", null, "user", "一"),
                nodeLine("n-000001", null, "user", "重复"),
                "{\"recordType\":\"weird\",\"schemaVersion\":1}",
                "{\"recordType\":\"meta\",\"schemaVersion\":1,\"activeLeafId\":\"n-000001\",\"updatedAt\":2}",
            ).joinToString("\n") + "\n",
        )

        val store = TreeSessionStore("s1", file)
        try {
            assertEquals(1, store.nodeCount())
            assertEquals("一", store.node("n-000001")?.content)
            assertEquals(1, store.lastLoadReport.duplicateNodeIds)
            assertEquals(1, store.lastLoadReport.skippedRecords)
            assertEquals(0, store.lastLoadReport.corruptLines)
        } finally {
            store.close()
        }
    }

    @Test
    fun 树打印_包含节点id与分支标记() {
        val store = TreeSessionStore("s1")
        val user = store.append(SessionRole.USER, "把 notes.txt 里的标题改成大写")
        val answer = store.append(
            role = SessionRole.ASSISTANT,
            content = "我先读取该文件。",
            toolCalls = listOf(ToolCall("read", "{}")),
            stepIndex = 0,
        )
        store.append(
            role = SessionRole.TOOL,
            content = "文件内容",
            callId = answer.toolCalls.single().callId,
            tool = "read",
            toolStatus = ToolOutcomeStatus.SUCCESS,
            stepIndex = 0,
            durationMs = 5L,
        )
        val finished = store.append(SessionRole.ASSISTANT, "已改好。")
        store.fork(user.id)
        val alternative = store.append(SessionRole.ASSISTANT, "换一种写法。")

        val tree = store.renderTree()
        assertTrue(tree.contains(user.id))
        assertTrue(tree.contains(answer.id))
        assertTrue(tree.contains("SUCCESS"))
        assertTrue(tree.contains("← 活动叶子"))
        assertTrue(tree.contains(finished.id))
        assertTrue(tree.contains(alternative.id))

        val activeLine = tree.lines().first { it.contains(alternative.id) }
        assertTrue(activeLine.contains("● "))
        val abandonedLine = tree.lines().first { it.contains(finished.id) }
        assertTrue(abandonedLine.contains("○ "))
        assertTrue(tree.contains("⑂分叉点"))
    }

    @Test
    fun 分支序列_根到叶且快照回链工具参数() {
        val store = TreeSessionStore("s1")
        val user = store.append(SessionRole.USER, "读文件")
        val answer = store.append(
            role = SessionRole.ASSISTANT,
            content = "好",
            toolCalls = listOf(ToolCall("read", "{\"path\":\"a.txt\"}")),
            stepIndex = 0,
        )
        val tool = store.append(
            role = SessionRole.TOOL,
            content = "内容",
            callId = answer.toolCalls.single().callId,
            tool = "read",
            toolStatus = ToolOutcomeStatus.SUCCESS,
            stepIndex = 0,
            durationMs = 7L,
        )

        assertEquals(listOf(user.id, answer.id, tool.id), store.branchTo().map { it.id })
        val snapshot = store.snapshot()
        assertEquals(listOf(TurnRole.USER, TurnRole.ASSISTANT, TurnRole.TOOL), snapshot.map { it.role })
        assertEquals(listOf(ToolCall("read", "{\"path\":\"a.txt\"}")), snapshot[2].toolCalls)
        assertEquals(7L, snapshot[2].durationMs!!)
        assertEquals(ToolOutcomeStatus.SUCCESS, snapshot[2].toolStatus)
        assertEquals(0, snapshot[2].stepIndex)
        assertEquals(0, snapshot[1].stepIndex)
    }

    @Test
    fun 空会话_首行写meta且树为空提示() {
        val file = newFile()
        val store = TreeSessionStore("s1", file)
        try {
            assertTrue(file.exists())
            assertEquals(1, file.readLines().count { it.isNotBlank() })
            assertNull(store.activeLeafId)
            assertEquals(TreeSessionStore.EMPTY_SESSION_TEXT, store.renderTree())
            val record = Json.parseToJsonElement(file.readText().trim()) as JsonObject
            assertEquals(RECORD_TYPE_META, record["recordType"]!!.jsonPrimitive.content)
            assertEquals(SESSION_SCHEMA_VERSION, record["schemaVersion"]!!.jsonPrimitive.int)
            assertTrue(record["activeLeafId"] is JsonNull)
        } finally {
            store.close()
        }
    }

    @Test
    fun 树打印_摘要节点标注覆盖范围与受保护() {
        val store = TreeSessionStore("s1")
        store.append(SessionRole.USER, "第一轮问题")
        store.append(
            role = SessionRole.SUMMARY,
            content = "此前对话的压缩摘要",
            compaction = SummaryCompaction(
                coveredFrom = "n-000001",
                coveredTo = "n-000002",
                nodeCount = 2,
                estimatedTokens = 128,
                createdAt = FIXED_TIME,
            ),
        )

        val line = store.renderTree().lines().first { it.contains("此前对话的压缩摘要") }
        // #99：摘要节点必须自解释——覆盖范围、节点数、受保护状态一眼可见。
        assertTrue(line.contains("[摘要"))
        assertTrue(line.contains("覆盖 n-000001..n-000002"))
        assertTrue(line.contains("2 节点"))
        assertTrue(line.contains("受保护"))
    }

    @Test
    fun 树打印_无根节点也不静默返回空串() {
        val file = newFile()
        // 父链成环：两个节点互为父节点，任何一方都不是根（#99 真机「/tree 无输出」兜底路径）。
        file.writeText(
            "{\"recordType\":\"node\",\"schemaVersion\":1,\"id\":\"n-000001\",\"parentId\":\"n-000002\"," +
                "\"role\":\"user\",\"content\":\"环一\",\"timestamp\":1}\n" +
                "{\"recordType\":\"node\",\"schemaVersion\":1,\"id\":\"n-000002\",\"parentId\":\"n-000001\"," +
                "\"role\":\"assistant\",\"content\":\"环二\",\"timestamp\":2}\n",
        )
        val store = TreeSessionStore("s1", file)
        try {
            val tree = store.renderTree()
            assertTrue(tree.isNotBlank())
            assertTrue(tree.contains(TreeSessionStore.UNROOTED_NOTICE))
            assertTrue(tree.contains("环一"))
            assertTrue(tree.contains("环二"))
        } finally {
            store.close()
        }
    }

    private companion object {
        const val FIXED_TIME = 1_763_932_800_123L
    }
}
