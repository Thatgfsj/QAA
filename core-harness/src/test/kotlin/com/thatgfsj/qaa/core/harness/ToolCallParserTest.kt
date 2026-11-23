package com.thatgfsj.qaa.core.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolCallParser 五级提取管线单测（设计文档 4.2、附录 A.3 P1–P9）：
 * 围栏剥离、首花括号定位、配平扫描、截断修补、数组展开与字段校验。
 */
class ToolCallParserTest {

    private val parser = ToolCallParser()

    private val readJson = """{"tool":"read","args":{"path":"a.txt"}}"""
    private val writeJson = """{"tool":"write","args":{"path":"b.txt","content":"hi"}}"""

    private fun calls(text: String): List<ToolCall> {
        val outcome = parser.parse(text)
        assertTrue("期望 Calls，实际 $outcome", outcome is ParseOutcome.Calls)
        return (outcome as ParseOutcome.Calls).calls
    }

    private fun reason(text: String): String {
        val outcome = parser.parse(text)
        assertTrue("期望 Invalid，实际 $outcome", outcome is ParseOutcome.Invalid)
        return (outcome as ParseOutcome.Invalid).reason
    }

    @Test
    fun P1_json围栏与裸围栏均提取() {
        val tagged = "调用如下：\n```json\n" + readJson + "\n```\n"
        val plain = "```\n" + writeJson + "\n```"
        assertEquals("read", calls(tagged).single().tool)
        assertEquals("write", calls(plain).single().tool)
    }

    @Test
    fun P1_多围栏取第一个含tool的() {
        val text = "```kotlin\nval x = 1\n```\n" +
            "```json\n" + writeJson + "\n```\n" +
            "```json\n" + readJson + "\n```"
        assertEquals("write", calls(text).single().tool)
    }

    @Test
    fun P1_围栏内数组多调用() {
        val text = "```json\n[" + readJson + "," + writeJson + "]\n```"
        assertEquals(listOf("read", "write"), calls(text).map { it.tool })
    }

    @Test
    fun P2_首花括号前噪声文本() {
        val text = "我先读取文件确认内容：\n" + readJson + "\n然后再回答。"
        assertEquals("read", calls(text).single().tool)
    }

    @Test
    fun P2_噪声花括号之后仍能定位调用() {
        // 前置片段 {a, b} 不是 JSON：继续向后扫描，仍应找到真正的调用。
        val text = "集合 {a, b} 不是 JSON，真正的调用是 " + readJson
        assertEquals("read", calls(text).single().tool)
    }

    @Test
    fun P3_字符串含花括号与转义引号_配平不误截() {
        val text = """{"tool":"write","args":{"path":"a.txt","content":"a{b\"c}d"}}""" + "，以上。"
        val call = calls(text).single()
        assertEquals("write", call.tool)
        assertEquals("""{"path":"a.txt","content":"a{b\"c}d"}""", call.argsJson)
    }

    @Test
    fun P4_截断修补_三层内成功() {
        // 截断在字符串内：补 1 个引号 + 2 个花括号，共 3 个闭合记号。
        val truncated = "{\"tool\":\"read\",\"args\":{\"path\":\"a.txt"
        val call = calls(truncated).single()
        assertEquals("read", call.tool)
        assertEquals("""{"path":"a.txt"}""", call.argsJson)
    }

    @Test
    fun P4_截断超深_判定失败() {
        // 4 层未闭合花括号 + 1 个未闭合字符串 = 需补 5 个记号，超过 3 个上限。
        val deep = "{\"tool\":\"write\",\"args\":{\"opts\":{\"a\":{\"b\":\"x"
        val deepReason = reason(deep)
        assertTrue(deepReason, deepReason.contains("截断过深"))
    }

    @Test
    fun P5_顶层数组展开为多调用() {
        val text = "[" + readJson + "," + writeJson + "]"
        val result = calls(text)
        assertEquals(listOf("read", "write"), result.map { it.tool })
        assertEquals("""{"path":"a.txt"}""", result[0].argsJson)
        assertEquals("""{"path":"b.txt","content":"hi"}""", result[1].argsJson)
    }

    @Test
    fun P5_正文编号列表不误判() {
        assertEquals(ParseOutcome.NoToolCall, parser.parse("步骤 [1] 已完成，接着做 [2]。"))
    }

    @Test
    fun P5_数组含未知工具_整体失败() {
        val text = "[" + readJson + "," + """{"tool":"hack","args":{}}""" + "]"
        val reason = reason(text)
        assertTrue(reason, reason.contains("第 2 个元素"))
        assertTrue(reason, reason.contains("未知工具：hack"))
    }

    @Test
    fun P6_未知工具_Invalid含原因() {
        val reason = reason("""{"tool":"hack","args":{}}""")
        assertTrue(reason, reason.contains("未知工具：hack"))
        assertEquals(
            listOf("read", "write", "edit", "draw_image"),
            ToolCallParser.TOOL_WHITELIST.toList(),
        )
    }

    @Test
    fun P7_纯文本无花括号_NoToolCall() {
        assertEquals(ParseOutcome.NoToolCall, parser.parse("今天天气不错，直接回答即可。"))
        assertEquals(ParseOutcome.NoToolCall, parser.parse("好的。"))
    }

    @Test
    fun P7_空对象与非法args_判定失败() {
        assertTrue(reason("{}").contains("缺少 tool"))
        assertTrue(reason("""{"tool":"read","args":[]}""").contains("args"))
        assertTrue(reason("""{"tool":null}""").contains("缺少 tool"))
    }

    @Test
    fun P7_括号错配_判定失败() {
        val reason = reason("""{"tool":"read","args":{"path":"a.txt"]}""")
        assertTrue(reason, reason.contains("括号不匹配"))
    }

    @Test
    fun P7_异常输入不抛异常() {
        listOf("", "   ", "{", "[", "```", "```json", "}", "]", "null", "{tool:1}").forEach {
            parser.parse(it)
        }
        // 残片不抛异常，按可提取性归为终答或纠错。
        assertEquals(ParseOutcome.NoToolCall, parser.parse("]"))
        assertTrue(parser.parse("{") is ParseOutcome.Invalid)
    }

    @Test
    fun P8_别名name与arguments兜底() {
        val call = calls("""{"name":"read","arguments":{"path":"a.txt"}}""").single()
        assertEquals("read", call.tool)
        assertEquals("""{"path":"a.txt"}""", call.argsJson)
    }

    @Test
    fun P8_canonical优先于别名() {
        val call = calls(
            """{"tool":"write","args":{"path":"c.txt","content":"x"},"name":"read"}"""
        ).single()
        assertEquals("write", call.tool)
    }

    @Test
    fun P9_缺少args视作空对象() {
        assertEquals("{}", calls("""{"tool":"read"}""").single().argsJson)
    }
}
