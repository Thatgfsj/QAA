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

    // ------------------------------------------- Issue #108 边界收敛（实体/闭合/空白/重复键/混合）

    @Test
    fun E1_HTML实体整块形态可提取() {
        // Issue #108 第 1 条：整块被写成 &lt;tool_call&gt; 时先反转义再按 L0 提取（原 F12 锁定已作废）。
        val text = "&lt;tool_call&gt;read&lt;arg_key&gt;path&lt;/arg_key&gt;" +
            "&lt;arg_value&gt;a.txt&lt;/arg_value&gt;&lt;/tool_call&gt;"
        val call = calls(text).single()
        assertEquals("read", call.tool)
        expectArgs(call, "path" to "a.txt")
    }

    @Test
    fun E2_arg_value内五类实体还原() {
        // Issue #108 第 1 条：&amp; &lt; &gt; &quot; &#39; 全部还原，其余文本原样。
        val text = """<tool_call>write<arg_key>path</arg_key><arg_value>a.txt</arg_value>""" +
            """<arg_key>content</arg_key><arg_value>a &amp; b &lt;tag&gt; &quot;q&quot; &#39;s&#39;</arg_value></tool_call>"""
        val call = calls(text).single()
        expectArgs(call, "path" to "a.txt", "content" to """a & b <tag> "q" 's'""")
    }

    @Test
    fun E3_实体整块未知工具_仍转Invalid() {
        // 反转义得到非法调用时不得静默当终答（Issue #108 第 2/6 条）。
        val text = "&lt;tool_call&gt;hack&lt;arg_key&gt;a&lt;/arg_key&gt;" +
            "&lt;arg_value&gt;b&lt;/arg_value&gt;&lt;/tool_call&gt;"
        assertTrue(reason(text).contains("未知工具：hack"))
    }

    @Test
    fun E4_围栏内的实体块不执行() {
        val text = "```xml\n" +
            "&lt;tool_call&gt;read&lt;arg_key&gt;path&lt;/arg_key&gt;" +
            "&lt;arg_value&gt;etc/hosts&lt;/arg_value&gt;&lt;/tool_call&gt;\n```"
        assertEquals(ParseOutcome.NoToolCall, parser.parse(text))
    }

    @Test
    fun E5_散文里的实体示例不执行() {
        // 说明语境在反转义后的文本上重新判定，仍未执行（Issue #108 第 5 条）。
        val text = "示例：&lt;tool_call&gt;read&lt;arg_key&gt;path&lt;/arg_key&gt;" +
            "&lt;arg_value&gt;etc/hosts&lt;/arg_value&gt;&lt;/tool_call&gt;"
        assertEquals(ParseOutcome.NoToolCall, parser.parse(text))
    }

    @Test
    fun E6_实体转义开标签截断_不得静默NoToolCall() {
        assertTrue(reason("我先调用 &lt;tool_call").contains("实体转义"))
    }

    @Test
    fun E7_Qwen参数值实体还原() {
        val text = """<tool_call><function=write><parameter=path>a.txt</parameter>""" +
            """<parameter=content>1 &lt; 2 &amp;&amp; 3 &gt; 2</parameter></function></tool_call>"""
        expectArgs(calls(text).single(), "path" to "a.txt", "content" to "1 < 2 && 3 > 2")
    }

    @Test
    fun N2_函数标签缺失或畸形_不得静默NoToolCall() {
        // Issue #108 第 2 条：R6 类静默吞调用，缺 <function= 或标签未闭合时必须转 Invalid。
        assertTrue(reason("<tool_call><function=writ").contains("XML 函数标签缺少工具名"))
        assertTrue(
            reason("<tool_call><parameter=a>b</parameter></tool_call>").contains("XML 函数标签缺少工具名"),
        )
    }

    @Test
    fun N3_闭合标签内空白仍可解析() {
        // Issue #108 第 2 条（非法闭合）：</arg_key > / </arg_value > / </tool_call > 均容忍。
        val text = "<tool_call>write<arg_key>path</arg_key ><arg_value>a.txt</arg_value ></tool_call >"
        val call = calls(text).single()
        assertEquals("write", call.tool)
        expectArgs(call, "path" to "a.txt")
    }

    @Test
    fun N4_多余或不匹配的闭合标签不破坏调用() {
        val text = """<tool_call>write<arg_key>path</arg_key><arg_value>a.txt</arg_value></function></tool_call>"""
        expectArgs(calls(text).single(), "path" to "a.txt")
    }

    @Test
    fun N5_值内闭合字面量_按首个闭合截断_锁定策略() {
        // Issue #108 第 4 条建议“取最后一个闭合”，但会吞并后续参数，此处锁定首闭合策略并记录差异。
        val text = """<tool_call>write<arg_key>path</arg_key><arg_value>a</arg_value>b</arg_value></tool_call>"""
        expectArgs(calls(text).single(), "path" to "a")
    }

    @Test
    fun W1_Qwen多行参数保留缩进与行尾空白() {
        // Issue #108 第 3 条：只剥模板两端的排版换行，多行值不得 trim。
        val text = "<tool_call><function=write><parameter=path>a.txt</parameter>" +
            "<parameter=content>\n    indented line\n  second  \n</parameter></function></tool_call>"
        val call = calls(text).single()
        expectArgs(call, "path" to "a.txt", "content" to "    indented line\n  second  ")
    }

    @Test
    fun W2_单行参数维持既有trim() {
        val text = "<tool_call><function=read><parameter=path> a.txt </parameter></function></tool_call>"
        expectArgs(calls(text).single(), "path" to "a.txt")
    }

    @Test
    fun W3_arg_value多行文本原样保留() {
        val text = """<tool_call>write<arg_key>path</arg_key><arg_value>a.txt</arg_value>""" +
            "<arg_key>content</arg_key><arg_value>line1\nline2\n</arg_value></tool_call>"
        expectArgs(calls(text).single(), "path" to "a.txt", "content" to "line1\nline2\n")
    }

    @Test
    fun D1_重复参数名后者覆盖前者() {
        // Issue #108 第 4 条：同名 key 重复时后者覆盖，两种方言一致。
        val arg = "<tool_call>write<arg_key>path</arg_key><arg_value>a.txt</arg_value>" +
            "<arg_key>path</arg_key><arg_value>b.txt</arg_value></tool_call>"
        expectArgs(calls(arg).single(), "path" to "b.txt")
        val qwen = "<tool_call><function=read><parameter=path>a.txt</parameter>" +
            "<parameter=path>b.txt</parameter></function></tool_call>"
        expectArgs(calls(qwen).single(), "path" to "b.txt")
    }

    @Test
    fun M1_XML非法与裸JSON混合_以JSON为准() {
        // Issue #108 第 5 条：XML 的 Invalid 不得抢占可执行的 JSON 调用（沿用评审 P1-1 语义）。
        val text = """<tool_call>hack<arg_key>a</arg_key><arg_value>b</arg_value></tool_call>""" +
            """ 正确做法是：{"tool":"read","args":{"path":"a.txt"}}"""
        assertEquals("read", calls(text).single().tool)
    }

    @Test
    fun M2_XML与JSON同时存在_维持L0优先() {
        val text = """<tool_call>write<arg_key>path</arg_key><arg_value>a.txt</arg_value></tool_call>""" +
            """ 另外 {"tool":"read","args":{"path":"b.txt"}}"""
        val call = calls(text).single()
        assertEquals("write", call.tool)
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
