package com.thatgfsj.qaa.core.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolCallParser L0 XML 方言提取单测（Issue #107）：
 * 1. 真机 tool_call + arg_key/arg_value 形态；
 * 2. Qwen 系 tool_call + function/parameter 形态；
 * 3. tool_call 内 JSON 载荷复用既有 L1–L5 提取与字段校验；
 * 4. 宽容：缺闭合标签/截断可救回、多块取首个通过白名单的、数组多调用按序展开；
 * 5. 未知工具仍返回 Invalid 走纠错重试；工具痕迹不得静默当终答，文档提及 tool_call 不误判。
 */
class ToolCallParserXmlDialectTest {

    private val parser = ToolCallParser()

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

    private fun expectArgs(call: ToolCall, vararg pairs: Pair<String, String>) {
        val expected = JsonObject(pairs.associate { (key, value) -> key to JsonPrimitive(value) })
        assertEquals(expected, Json.parseToJsonElement(call.argsJson))
    }

    @Test
    fun X1_真机argKey_argValue形态() {
        // 2026-11-25 真机 Spark-X2.6-4B 原始输出形态。
        val text = """<tool_call>write<arg_key>content</arg_key><arg_value>QAA-real-device-test</arg_value><arg_key>path</arg_key><arg_value>test.txt</arg_value></tool_call>"""
        val call = calls(text).single()
        assertEquals("write", call.tool)
        // 直接比较序列化串，锁定参数顺序（content 在 path 前；Map 相等证明不了顺序，评审 P2）。
        assertEquals("""{"content":"QAA-real-device-test","path":"test.txt"}""", call.argsJson)
    }

    @Test
    fun X2_Qwen函数参数形态() {
        val text = """<tool_call><function=write><parameter=path>a.txt</parameter><parameter=content>hello</parameter></function></tool_call>"""
        val call = calls(text).single()
        assertEquals("write", call.tool)
        expectArgs(call, "path" to "a.txt", "content" to "hello")
    }

    @Test
    fun X3_toolCall内JSON对象() {
        val text = """<tool_call>{"tool":"read","args":{"path":"a.txt"}}</tool_call>"""
        val call = calls(text).single()
        assertEquals("read", call.tool)
        expectArgs(call, "path" to "a.txt")
    }

    @Test
    fun X3b_toolCall内数组多调用按序展开() {
        val text = """<tool_call>[{"tool":"read","args":{"path":"a.txt"}},{"tool":"write","args":{"path":"b.txt","content":"hi"}}]</tool_call>"""
        val result = calls(text)
        assertEquals(listOf("read", "write"), result.map { it.tool })
        expectArgs(result[0], "path" to "a.txt")
        expectArgs(result[1], "path" to "b.txt", "content" to "hi")
    }

    @Test
    fun X4_前后夹闲聊仍提取() {
        val text = "好的，我先读取文件确认内容。" +
            """<tool_call>read<arg_key>path</arg_key><arg_value>a.txt</arg_value></tool_call>""" +
            "读取后再回答你。"
        val call = calls(text).single()
        assertEquals("read", call.tool)
        expectArgs(call, "path" to "a.txt")
    }

    @Test
    fun X5_缺闭合标签仍可提取() {
        // 缺 </tool_call>：JSON 载荷走截断修补，arg_key 载荷读到文本末尾。
        val json = """<tool_call>{"tool":"read","args":{"path":"a.txt"}"""
        assertEquals("read", calls(json).single().tool)
        val arg = calls("""<tool_call>write<arg_key>path</arg_key><arg_value>a.txt""").single()
        assertEquals("write", arg.tool)
        expectArgs(arg, "path" to "a.txt")
        // 缺 </function> 的 Qwen 形态。
        val fn = calls("""<tool_call><function=read><parameter=path>a.txt</parameter>""").single()
        assertEquals("read", fn.tool)
        expectArgs(fn, "path" to "a.txt")
    }

    @Test
    fun X6_截断到无法修复_判定失败() {
        // 子标签被截断（arg_key 连开标签都没闭合），无从恢复参数 → 纠错重试。
        assertTrue(reason("<tool_call>write<arg_key").contains("工具调用痕迹"))
        // 只有 arg_value 没有 arg_key，无法配对参数。
        assertTrue(reason("""<tool_call>write<arg_value>x</arg_value></tool_call>""").contains("缺少参数"))
        // 工具名为空。
        assertTrue(reason("""<tool_call><arg_key>path</arg_key><arg_value>a.txt</arg_value></tool_call>""").contains("缺少工具名"))
    }

    @Test
    fun X7_未知工具_Invalid含原因() {
        assertTrue(reason("""<tool_call>hack<arg_key>a</arg_key><arg_value>b</arg_value></tool_call>""").contains("未知工具：hack"))
        assertTrue(reason("""<tool_call><function=hack><parameter=a>b</parameter></function></tool_call>""").contains("未知工具：hack"))
        assertTrue(reason("""<tool_call>{"tool":"hack","args":{}}</tool_call>""").contains("未知工具：hack"))
        // 块内坏 JSON 同样 Invalid，不得静默回显。
        assertTrue(reason("""<tool_call>{not-json}</tool_call>""").isNotEmpty())
    }

    @Test
    fun X8_多块取第一个通过白名单校验的() {
        val text = """<tool_call>hack<arg_key>a</arg_key><arg_value>b</arg_value></tool_call>""" +
            "中间闲聊。" +
            """<tool_call>read<arg_key>path</arg_key><arg_value>a.txt</arg_value></tool_call>"""
        val call = calls(text).single()
        assertEquals("read", call.tool)
    }

    @Test
    fun X9_普通文本提及tool_call不误判() {
        // 文档/说明中的裸词提及：无标签结构 → 仍是终答。
        assertEquals(ParseOutcome.NoToolCall, parser.parse("系统提示词规定用 tool_call 标签包裹工具调用。"))
        // 行内代码引用标签、其后为中文说明：载荷不以工具名/JSON 起头 → 不误判。
        assertEquals(ParseOutcome.NoToolCall, parser.parse("格式形如 <tool_call> 的标签，需要成对出现。"))
        assertEquals(ParseOutcome.NoToolCall, parser.parse("示例 <tool_call> 标签用于包裹一次工具调用。"))
    }

    @Test
    fun X10_工具痕迹但未提取出调用_转Invalid() {
        // 无花括号的 JSON 片段（花括号之外的工具键痕迹）。
        assertTrue(reason("\"tool\": \"read\", \"args\": ").contains("未形成合法 JSON"))
        // 开标签被截断在末尾。
        assertTrue(reason("我先调用 <tool_call").contains("<tool_call>"))
        // 开标签存在但载荷为空（模型输出截断在标签处）。
        assertTrue(reason("我来处理：<tool_call>").contains("<tool_call>"))
    }

    @Test
    fun X11_无痕迹纯文本仍NoToolCall() {
        assertEquals(ParseOutcome.NoToolCall, parser.parse("今天天气不错，直接回答即可。"))
        assertEquals(ParseOutcome.NoToolCall, parser.parse("好的。"))
        assertEquals(ParseOutcome.NoToolCall, parser.parse("步骤 [1] 已完成，接着做 [2]。"))
    }
    @Test
    fun F1_评审P0_畸形参数标签不抛异常() {
        // 评审 P0：<parameter=x</function> 被开标签正则吃进名称，曾抛 StringIndexOutOfBoundsException。
        val text = "<tool_call><function=write><parameter=a>b</parameter><parameter=x</function></tool_call>"
        val outcome = parser.parse(text)
        assertTrue("期望 Invalid，实际 \$outcome", outcome is ParseOutcome.Invalid)
        assertTrue(outcome.toString(), outcome.toString().contains("XML 参数标签畸形"))
    }

    @Test
    fun F7_评审P1_1_XML_Invalid不抢占JSON管线() {
        // ① XML 块字段非法（未知工具）只暂存，后续 JSON 围栏是合法调用 → 以 JSON 为准。
        val wrapped = """<tool_call>hack<arg_key>a</arg_key><arg_value>b</arg_value></tool_call>""" +
            " 不过正确做法是：\n```json\n" +
            """{"tool":"read","args":{"path":"a.txt"}}""" +
            "\n```"
        assertEquals("read", calls(wrapped).single().tool)
        // ② 评审原始复现：说明句里的 arg 标签 + JSON 围栏，行为应为 Calls(read)。
        val prose = "说明句里出现 <arg_key>k</arg_key><arg_value>v</arg_value>，后面接 JSON：\n" +
            "```json\n" +
            """{"tool":"read","args":{"path":"a.txt"}}""" +
            "\n```"
        assertEquals("read", calls(prose).single().tool)
    }

    @Test
    fun F2_评审P1_2_示例句中的XML块不执行() {
        val text = "示例：" +
            """<tool_call>read<arg_key>path</arg_key><arg_value>etc/hosts</arg_value></tool_call>""" +
            " 这样写即可。"
        assertEquals(ParseOutcome.NoToolCall, parser.parse(text))
    }

    @Test
    fun F3_评审P1_2_围栏里的XML块不执行() {
        val text = "```xml\n" +
            """<tool_call>read<arg_key>path</arg_key><arg_value>etc/hosts</arg_value></tool_call>""" +
            "\n```"
        assertEquals(ParseOutcome.NoToolCall, parser.parse(text))
    }

    @Test
    fun F4_评审P1_3_散文提及arg标签不误判() {
        assertEquals(
            ParseOutcome.NoToolCall,
            parser.parse("参数用 <arg_key> 与 <arg_value> 包裹，成对出现。"),
        )
    }

    @Test
    fun F5_评审P1_3_散文提及function形态不误判() {
        assertEquals(
            ParseOutcome.NoToolCall,
            parser.parse("函数写作 <function=name><parameter=k>v</parameter></function> 的形式。"),
        )
    }

    @Test
    fun F6_嵌套tool_call被拒绝() {
        val text = """<tool_call>read<arg_key>path</arg_key><arg_value><tool_call>write<arg_key>path</arg_key><arg_value>b.txt</arg_value></tool_call></arg_value></tool_call>"""
        assertTrue(reason(text).contains("嵌套"))
    }

    @Test
    fun F12_HTML实体形态当前不识别_锁定为NoToolCall() {
        // HTML 实体反转义归 issue #108；此处锁定当前行为，避免日后静默变成误执行。
        val text = "&lt;tool_call&gt;read&lt;arg_key&gt;path&lt;/arg_key&gt;" +
            "&lt;arg_value&gt;a.txt&lt;/arg_value&gt;&lt;/tool_call&gt;"
        assertEquals(ParseOutcome.NoToolCall, parser.parse(text))
    }

    @Test
    fun F15_多块全部未知工具_返回首个Invalid() {
        val text = """<tool_call>hack1<arg_key>a</arg_key><arg_value>b</arg_value></tool_call>""" +
            """<tool_call>hack2<arg_key>c</arg_key><arg_value>d</arg_value></tool_call>"""
        assertTrue(reason(text).contains("未知工具：hack1"))
    }

    @Test
    fun P2真机arg_value保留首尾空白() {
        val text = """<tool_call>write<arg_key>path</arg_key><arg_value>a.txt</arg_value><arg_key>content</arg_key><arg_value>  hi  </arg_value></tool_call>"""
        val call = calls(text).single()
        assertEquals("""{"path":"a.txt","content":"  hi  "}""", call.argsJson)
    }
}
