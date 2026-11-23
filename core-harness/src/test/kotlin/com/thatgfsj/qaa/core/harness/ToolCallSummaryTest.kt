package com.thatgfsj.qaa.core.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 确认挂起展示文案单测（设计文档 3.6 / 附录 B.3 字段清单）。 */
class ToolCallSummaryTest {

    @Test
    fun write展示路径与内容字节数() {
        val call = ToolCall("write", """{"path":"a.txt","content":"你好"}""")
        assertEquals("write 路径=\"a.txt\" 内容=6 字节", summarizeToolCall(call))
    }

    @Test
    fun edit展示路径与变更前后片段() {
        val call = ToolCall(
            "edit",
            """{"path":"b.kt","oldText":"val a = 1","newText":"val a = 2"}""",
        )
        assertEquals(
            "edit 路径=\"b.kt\" 替换 \"val a = 1\" → \"val a = 2\"",
            summarizeToolCall(call),
        )
    }

    @Test
    fun drawImage展示提示词() {
        val call = ToolCall("draw_image", """{"prompt":"一只在雨里打伞的猫"}""")
        assertEquals("draw_image 提示词=\"一只在雨里打伞的猫\"", summarizeToolCall(call))
    }

    @Test
    fun read展示目标路径() {
        val call = ToolCall("read", """{"path":"src/a.kt"}""")
        assertEquals("read 路径=\"src/a.kt\"", summarizeToolCall(call))
    }

    @Test
    fun 参数非法时退化为工具名与原始参数() {
        val call = ToolCall("write", "not-a-json")
        val summary = summarizeToolCall(call)
        assertTrue(summary.startsWith("write "))
        assertTrue(summary.contains("not-a-json"))
    }

    @Test
    fun 超长文案截断且折叠为单行() {
        val longText = "很长的内容\n".repeat(40)
        val call = ToolCall("draw_image", """{"prompt":"${longText.replace("\n", "\\n")}"}""")
        val summary = summarizeToolCall(call)
        assertTrue(summary.length <= 160)
        assertTrue(summary.contains("…"))
        assertTrue(!summary.contains("\n"))
    }
}
