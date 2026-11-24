package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.SessionNode
import com.thatgfsj.qaa.core.harness.SessionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** /export 路径与回显单测（Issue #90）：默认落点、后缀补齐、分支剔除统计。 */
class TraceExportTest {

    @Test
    fun 默认路径_落在沙盒exports会话文件() {
        assertEquals("exports/session-main.jsonl", traceExportPath("main", null))
        assertEquals("exports/session-main.jsonl", traceExportPath("main", "   "))
    }

    @Test
    fun 自定义路径_补齐jsonl后缀() {
        assertEquals("exports/custom.jsonl", traceExportPath("main", "exports/custom"))
        assertEquals("exports/custom.jsonl", traceExportPath("main", " exports/custom.jsonl "))
        assertEquals("exports/custom.JSONL", traceExportPath("main", "exports/custom.JSONL"))
    }

    @Test
    fun 分支统计_摘要解析重试与工具结果() {
        val branch = listOf(
            node("n1", SessionRole.USER),
            node("n2", SessionRole.ASSISTANT),
            node("n3", SessionRole.ASSISTANT, parseRetries = 2),
            node("n4", SessionRole.TOOL),
            node("n5", SessionRole.TOOL),
            node("n6", SessionRole.SUMMARY),
        )

        val stats = traceBranchStats(branch)

        assertEquals(6, stats.nodes)
        assertEquals(1, stats.summaries)
        assertEquals(1, stats.parseRetries)
        assertEquals(2, stats.toolResults)
    }

    @Test
    fun 成功回执_含行数与剔除统计() {
        val stats = TraceBranchStats(nodes = 4, summaries = 1, parseRetries = 0, toolResults = 2)

        val text = describeTraceExport("exports/session-main.jsonl", stats, lines = 1)

        assertTrue(text.contains("exports/session-main.jsonl"))
        assertTrue(text.contains("1 行"))
        assertTrue(text.contains("摘要 1 段"))
        assertTrue(text.contains("工具结果 2 条"))
    }

    @Test
    fun 不可用分支_说明未写出文件() {
        val stats = TraceBranchStats(nodes = 2, summaries = 0, parseRetries = 1, toolResults = 0)

        val text = describeTraceExport("exports/session-main.jsonl", stats, lines = 0)

        assertTrue(text.contains("暂不可用于训练"))
        assertTrue(text.contains("未写出文件"))
        assertTrue(text.contains("解析重试 1 节点"))
    }

    // ---- #105 /export 系统提示词快照 ----

    @Test
    fun 导出选项_记录提示词快照与来源标记() {
        val custom = "自定义系统提示词：只回答中文。"

        val options = traceExportOptions("main", custom)

        assertEquals(custom, options.systemPrompt)
        assertEquals(mapOf("systemPromptSource" to "自定义"), options.extraMeta)
    }

    @Test
    fun 导出正文_首条系统消息为当前提示词快照() {
        val custom = "自定义系统提示词：只回答中文。"
        val branch = listOf(
            node("n1", SessionRole.USER),
            node("n2", SessionRole.ASSISTANT),
        )

        val line = com.thatgfsj.qaa.core.harness.LoraTraceExporter
            .export(branch, traceExportOptions("main", custom))

        requireNotNull(line)
        assertTrue(line.contains(custom))
        assertTrue(line.contains("\"systemPromptSource\":\"自定义\""))
    }

    private fun node(id: String, role: SessionRole, parseRetries: Int = 0): SessionNode =
        SessionNode(id = id, parentId = null, role = role, content = "内容", parseRetries = parseRetries)
}
