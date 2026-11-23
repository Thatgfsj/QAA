package com.thatgfsj.qaa.core.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoRA 轨迹导出单测（设计附录 A.5 S8：分支线性化、噪声剔除、tool_calls 映射、rejected 开关）。
 */
class LoraTraceExporterTest {

    @Test
    fun 导出_线性化与tool_calls映射() {
        val call = SessionToolCall("c-000002-0", "read", "{\"path\":\"notes.txt\"}")
        val branch = listOf(
            node("n-000001", null, SessionRole.USER, "把标题改成大写"),
            node(
                "n-000002", "n-000001", SessionRole.ASSISTANT, "我先读取该文件。",
                toolCalls = listOf(call), stepIndex = 0,
                metrics = GenerationMetrics("NPU", 210L, 18.4f, 1350, 96, 4200L),
            ),
            node(
                "n-000003", "n-000002", SessionRole.TOOL, "（文件内容）",
                callId = call.callId, tool = "read", toolStatus = ToolOutcomeStatus.SUCCESS,
                stepIndex = 0, durationMs = 12L,
            ),
            node(
                "n-000004", "n-000003", SessionRole.ASSISTANT, "已将标题改为大写。",
                stepIndex = 1, metrics = GenerationMetrics("NPU", 180L, 20.1f, 1500, 32, 1600L),
            ),
        )

        val line = LoraTraceExporter.export(branch)
        assertNotNull(line)
        val record = Json.parseToJsonElement(line!!).jsonObject
        val messages = record["messages"]!!.jsonArray
        assertEquals(
            listOf("system", "user", "assistant", "tool", "assistant"),
            messages.map { it.jsonObject["role"]!!.jsonPrimitive.content },
        )
        assertEquals(
            PromptAssembler.DEFAULT_SYSTEM_PROMPT,
            messages[0].jsonObject["content"]!!.jsonPrimitive.content,
        )
        val toolCalls = messages[2].jsonObject["tool_calls"]!!.jsonArray
        assertEquals(1, toolCalls.size)
        val function = toolCalls[0].jsonObject["function"]!!.jsonObject
        assertEquals("c-000002-0", toolCalls[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("read", function["name"]!!.jsonPrimitive.content)
        assertEquals("{\"path\":\"notes.txt\"}", function["arguments"]!!.jsonPrimitive.content)

        val toolMessage = messages[3].jsonObject
        assertEquals("c-000002-0", toolMessage["tool_call_id"]!!.jsonPrimitive.content)
        val meta = record["meta"]!!.jsonObject
        assertEquals("n-000004", meta["branch"]!!.jsonPrimitive.content)
        assertEquals("NPU", meta["backend"]!!.jsonPrimitive.content)
        assertEquals(1500, meta["promptTokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun 导出_解析失败与系统回注节点剔除() {
        val branch = listOf(
            node("n-000001", null, SessionRole.USER, "你好"),
            node("n-000002", "n-000001", SessionRole.ASSISTANT, "这不是 JSON", parseRetries = 1),
            node("n-000003", "n-000002", SessionRole.TOOL, "纠错：请输出 JSON"),
            node("n-000004", "n-000003", SessionRole.ASSISTANT, "好的"),
        )

        val line = LoraTraceExporter.export(branch)
        assertNotNull(line)
        val messages = Json.parseToJsonElement(line!!).jsonObject["messages"]!!.jsonArray
        assertEquals(
            listOf("system", "user", "assistant"),
            messages.map { it.jsonObject["role"]!!.jsonPrimitive.content },
        )
        assertEquals("好的", messages[2].jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(line.contains("这不是 JSON"))
        assertFalse(line.contains("纠错"))
    }

    @Test
    fun 导出_失败工具默认剔除_开关保留() {
        val call = SessionToolCall("c-000002-0", "write", "{}")
        val branch = listOf(
            node("n-000001", null, SessionRole.USER, "写文件"),
            node("n-000002", "n-000001", SessionRole.ASSISTANT, "我来写。", toolCalls = listOf(call)),
            node(
                "n-000003", "n-000002", SessionRole.TOOL, "写入失败",
                callId = call.callId, tool = "write", toolStatus = ToolOutcomeStatus.FAILURE,
                errorCode = "E_IO", stepIndex = 0, durationMs = 3L,
            ),
            node("n-000004", "n-000003", SessionRole.ASSISTANT, "写入失败了，请检查权限。"),
        )

        assertNull(LoraTraceExporter.export(branch))

        val line = LoraTraceExporter.export(
            branch,
            LoraTraceExporter.Options(includeRejected = true),
        )
        assertNotNull(line)
        val record = Json.parseToJsonElement(line!!).jsonObject
        val messages = record["messages"]!!.jsonArray
        assertEquals(5, messages.size)
        val toolMessage = messages[3].jsonObject
        assertTrue(toolMessage["rejected"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("E_IO", toolMessage["errorCode"]!!.jsonPrimitive.content)
        assertTrue(record["meta"]!!.jsonObject["rejected"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun 导出_未完成的工具调用剔除() {
        val first = SessionToolCall("c-000002-0", "read", "{}")
        val second = SessionToolCall("c-000002-1", "write", "{}")
        val branch = listOf(
            node("n-000001", null, SessionRole.USER, "读写"),
            node("n-000002", "n-000001", SessionRole.ASSISTANT, "开始。", toolCalls = listOf(first, second)),
            node(
                "n-000003", "n-000002", SessionRole.TOOL, "内容",
                callId = first.callId, tool = "read", toolStatus = ToolOutcomeStatus.SUCCESS,
            ),
        )

        assertNull(LoraTraceExporter.export(branch))
    }

    @Test
    fun 导出_摘要默认剔除_可配system段() {
        val branch = listOf(
            node("n-000001", null, SessionRole.USER, "整理笔记"),
            node("n-000002", "n-000001", SessionRole.ASSISTANT, "已整理。"),
            node("n-000003", "n-000002", SessionRole.SUMMARY, "用户目标：整理笔记。"),
        )

        val plain = Json.parseToJsonElement(LoraTraceExporter.export(branch)!!)
            .jsonObject["messages"]!!.jsonArray
        assertEquals(3, plain.size)
        assertFalse(plain.map { it.jsonObject["content"]!!.jsonPrimitive.content }.contains("用户目标：整理笔记。"))

        val withSummary = Json.parseToJsonElement(
            LoraTraceExporter.export(branch, LoraTraceExporter.Options(includeSummaryAsSystem = true))!!,
        ).jsonObject["messages"]!!.jsonArray
        assertEquals(4, withSummary.size)
        assertEquals("system", withSummary[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("用户目标：整理笔记。", withSummary[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun 导出_无系统提示时取分支system节点() {
        val branch = listOf(
            node("n-000001", null, SessionRole.SYSTEM, "自定义系统段"),
            node("n-000002", "n-000001", SessionRole.USER, "你好"),
            node("n-000003", "n-000002", SessionRole.ASSISTANT, "你好，有什么可以帮你？"),
        )

        val messages = Json.parseToJsonElement(
            LoraTraceExporter.export(branch, LoraTraceExporter.Options(systemPrompt = null))!!,
        ).jsonObject["messages"]!!.jsonArray
        assertEquals("自定义系统段", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun 导出_tokensPerSec下限过滤() {
        val branch = listOf(
            node("n-000001", null, SessionRole.USER, "你好"),
            node(
                "n-000002", "n-000001", SessionRole.ASSISTANT, "你好。",
                metrics = GenerationMetrics("NPU", 100L, 8f, 10, 5, 600L),
            ),
        )

        assertNull(LoraTraceExporter.export(branch, LoraTraceExporter.Options(minTokensPerSec = 10f)))
        assertNotNull(LoraTraceExporter.export(branch, LoraTraceExporter.Options(minTokensPerSec = 5f)))
        // 无实测指标时按不可用剔除，避免噪声进入训练集。
        val withoutMetrics = listOf(
            node("n-000001", null, SessionRole.USER, "你好"),
            node("n-000002", "n-000001", SessionRole.ASSISTANT, "你好。"),
        )
        assertNull(LoraTraceExporter.export(withoutMetrics, LoraTraceExporter.Options(minTokensPerSec = 5f)))
    }

    @Test
    fun 导出_自定义分支标签与附加meta() {
        val branch = listOf(
            node("n-000001", null, SessionRole.USER, "你好"),
            node("n-000002", "n-000001", SessionRole.ASSISTANT, "你好。"),
        )

        val meta = Json.parseToJsonElement(
            LoraTraceExporter.export(
                branch,
                LoraTraceExporter.Options(branchLabel = "fork-a", extraMeta = mapOf("dataset" to "v1")),
            )!!,
        ).jsonObject["meta"]!!.jsonObject
        assertEquals("fork-a", meta["branch"]!!.jsonPrimitive.content)
        assertEquals("v1", meta["dataset"]!!.jsonPrimitive.content)
    }

    private fun node(
        id: String,
        parentId: String?,
        role: SessionRole,
        content: String,
        toolCalls: List<SessionToolCall> = emptyList(),
        callId: String? = null,
        tool: String? = null,
        toolStatus: ToolOutcomeStatus? = null,
        errorCode: String? = null,
        metrics: GenerationMetrics? = null,
        stepIndex: Int? = null,
        parseRetries: Int = 0,
        durationMs: Long? = null,
    ): SessionNode = SessionNode(
        id = id,
        parentId = parentId,
        role = role,
        content = content,
        toolCalls = toolCalls,
        callId = callId,
        tool = tool,
        toolStatus = toolStatus,
        errorCode = errorCode,
        timestamp = 1L,
        metrics = metrics,
        stepIndex = stepIndex,
        parseRetries = parseRetries,
        durationMs = durationMs,
    )
}
