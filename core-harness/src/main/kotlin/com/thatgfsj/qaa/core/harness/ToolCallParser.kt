package com.thatgfsj.qaa.core.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 解析产物。 */
sealed interface ParseOutcome {
    /** 纯文本回复，无工具调用。 */
    data object NoToolCall : ParseOutcome
    /** 至少一个合法调用。 */
    data class Calls(val calls: List<ToolCall>) : ParseOutcome
    /** 疑似工具调用但非法（触发纠错重试，设计文档 4.3/4.4）。 */
    data class Invalid(val reason: String) : ParseOutcome
}

/**
 * 工具调用解析器（设计文档 4.2 五级提取管线，任一级成功即停）：
 * 1. 代码围栏剥离：```json 与裸围栏一视同仁；多围栏取第一个含工具键的；
 * 2. 首花括号定位：容忍前置闲聊，从首个 `{` 起截取；顶层数组 `[` 同样纳入；
 * 3. 括号配平扫描：按栈扫描，字符串字面量与转义引号不参与配平；
 * 4. 截断修补：深度未归零时按栈补全缺失的引号/括号，最多 3 个闭合记号；
 * 5. 字段校验：tool ∈ 四工具白名单、args 为对象（缺省视作空对象）。
 *
 * 数组容忍：顶层数组按序展开为多个调用（设计 4.2）；别名容忍：tool/args
 * 缺失时兜底接受 name/arguments。参数级校验仍交各 executor（设计 3.3）。
 */
class ToolCallParser {

    /**
     * 解析一次模型输出。无任何 `{`/`[` 的纯文本判定为终答（[ParseOutcome.NoToolCall]）；
     * 含疑似调用但提取/校验失败时返回 [ParseOutcome.Invalid]，由 AgentLoop 回注纠错提示。
     */
    fun parse(text: String): ParseOutcome {
        if (text.isBlank()) return ParseOutcome.Invalid("空输入，未解析出工具调用")

        val fences = fencedBlocks(text)
        // L1：第一个含工具键的围栏即本次调用的候选；其失败直接走纠错，不再猜测其它围栏。
        val preferredIndex = fences.indexOfFirst { looksLikeToolPayload(it) }
        if (preferredIndex >= 0) {
            when (val outcome = extract(fences[preferredIndex])) {
                is ParseOutcome.Calls -> return outcome
                is ParseOutcome.Invalid -> return outcome
                ParseOutcome.NoToolCall -> Unit
            }
        }
        // 无工具围栏时逐个围栏尝试，取第一个可执行的调用。
        for (index in fences.indices) {
            if (index == preferredIndex) continue
            val outcome = extract(fences[index])
            if (outcome is ParseOutcome.Calls) return outcome
        }
        // L2：围栏之外仍可能有裸 JSON，回到全文从首个花括号（或顶层数组）定位。
        return extract(text)
    }

    // ---------------------------------------------------------------- L1 围栏剥离

    /** 提取代码围栏内容，按出现顺序返回；未闭合的围栏取到文本末尾。 */
    private fun fencedBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        var cursor = 0
        while (cursor < text.length) {
            val open = text.indexOf(FENCE, cursor)
            if (open < 0) break
            val afterOpen = open + FENCE.length
            val lineEnd = text.indexOf('\n', afterOpen).let { if (it < 0) text.length else it }
            val marker = text.substring(afterOpen, lineEnd).trim()
            // 语言标记（json/kotlin 等）独占一行时跳过；否则同一行即为内容开头。
            val contentStart = if (marker.isEmpty() || LANGUAGE_TAG.matches(marker)) {
                if (lineEnd < text.length) lineEnd + 1 else text.length
            } else {
                afterOpen
            }
            val close = text.indexOf(FENCE, contentStart)
            blocks += if (close < 0) {
                text.substring(contentStart)
            } else {
                text.substring(contentStart, close)
            }
            if (close < 0) break
            cursor = close + FENCE.length
        }
        return blocks
    }

    // ------------------------------------------------- L2 定位 + L3 配平 + L4 修补 + L5 校验

    /**
     * 在文本中依次定位候选调用。已解析成合法 JSON 的候选若字段非法即判定失败，
     * 不再向后找调用（避免静默丢弃未知工具等明确错误）；只有连 JSON 都不成立的
     * 噪声片段才继续向后扫描。
     */
    private fun extract(source: String): ParseOutcome {
        var cursor = 0
        var softFailure: String? = null
        while (cursor < source.length) {
            val brace = source.indexOf('{', cursor)
            val bracket = source.indexOf('[', cursor)
            val start = when {
                brace < 0 -> bracket
                bracket < 0 -> brace
                else -> minOf(brace, bracket)
            }
            if (start < 0) break
            val candidate = if (source[start] == '{') {
                objectCandidate(source, start)
            } else {
                arrayCandidate(source, start)
            }
            when (val outcome = candidate.outcome) {
                is ParseOutcome.Calls -> return outcome
                is ParseOutcome.Invalid ->
                    if (candidate.parsed) return outcome
                    else if (softFailure == null) softFailure = outcome.reason
                ParseOutcome.NoToolCall -> Unit
            }
            cursor = candidate.nextIndex.coerceAtLeast(start + 1)
        }
        return softFailure?.let { ParseOutcome.Invalid(it) } ?: ParseOutcome.NoToolCall
    }

    private fun objectCandidate(source: String, start: Int): Candidate {
        val scan = scanBalanced(source, start)
        if (scan.mismatch) {
            return Candidate(ParseOutcome.Invalid(MISMATCH_REASON), parsed = false, nextIndex = scan.endExclusive)
        }
        val fragment = source.substring(start, scan.endExclusive)
        val parsed = parseCandidate(fragment, scan)
        return Candidate(parsed.outcome, parsed.parsed, scan.endExclusive)
    }

    private fun arrayCandidate(source: String, start: Int): Candidate {
        val scan = scanBalanced(source, start)
        val fragment = source.substring(start, scan.endExclusive)
        // 顶层数组仅在含工具键时按调用展开，避免把正文编号列表 [1]/[2] 误判为脏调用。
        if (!looksLikeToolPayload(fragment)) {
            return Candidate(ParseOutcome.NoToolCall, parsed = false, nextIndex = scan.endExclusive)
        }
        if (scan.mismatch) {
            return Candidate(ParseOutcome.Invalid(MISMATCH_REASON), parsed = false, nextIndex = scan.endExclusive)
        }
        val parsed = parseCandidate(fragment, scan)
        return Candidate(parsed.outcome, parsed.parsed, scan.endExclusive)
    }

    /** 解析候选片段：先直接解析；截断时按栈补全后再解析。 */
    private fun parseCandidate(fragment: String, scan: Scan): ParsedCandidate {
        val element = parseElement(fragment)
        if (element != null) return ParsedCandidate(validate(element), parsed = true)
        if (!scan.truncated) return ParsedCandidate(ParseOutcome.Invalid(JSON_PARSE_FAILED), parsed = false)
        return when (val repair = repair(fragment, scan)) {
            is Repair.Repaired -> {
                val repaired = parseElement(repair.text)
                    ?: return ParsedCandidate(ParseOutcome.Invalid(REPAIR_STILL_INVALID), parsed = false)
                ParsedCandidate(validate(repaired), parsed = true)
            }
            is Repair.Failed -> ParsedCandidate(ParseOutcome.Invalid(repair.reason), parsed = false)
        }
    }

    /**
     * 按栈补全被截断的 JSON（设计 4.2 第 4 级）：深度未归零即补，不附加截断点位置
     * 条件——停在完整值之后只缺闭合括号，正是模型被 token 预算截断时最常见的形态。
     * 一次最多补 [MAX_REPAIR_MARKS] 个记号；若补完仍无法解析（如停在逗号处），
     * 由 [parseCandidate] 转 Invalid 交纠错重试。
     */
    private fun repair(fragment: String, scan: Scan): Repair {
        val trimmed = if (scan.inString) fragment else fragment.trimEnd()
        if (scan.trailingEscape) return Repair.Failed("JSON 截断在转义符之后，无法补全")
        val suffix = buildString {
            if (scan.inString) append('"')
            for (index in scan.open.indices.reversed()) {
                append(if (scan.open[index] == '{') '}' else ']')
            }
        }
        if (suffix.isEmpty()) return Repair.Failed("JSON 不完整")
        if (suffix.length > MAX_REPAIR_MARKS) {
            return Repair.Failed("JSON 截断过深：需补 ${suffix.length} 个闭合记号（上限 $MAX_REPAIR_MARKS 个）")
        }
        return Repair.Repaired(trimmed + suffix)
    }

    /** L3 配平扫描：字符串字面量内不计数；括号错配立即判定。 */
    private fun scanBalanced(source: String, start: Int): Scan {
        val open = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var index = start
        while (index < source.length) {
            val char = source[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{', '[' -> open.addLast(char)
                    '}', ']' -> {
                        if (open.isEmpty() || !pairsWith(open.removeLast(), char)) {
                            return Scan(index + 1, truncated = false, mismatch = true, inString = false, trailingEscape = false, open = emptyList())
                        }
                        if (open.isEmpty()) {
                            return Scan(index + 1, truncated = false, mismatch = false, inString = false, trailingEscape = false, open = emptyList())
                        }
                    }
                }
            }
            index++
        }
        return Scan(
            endExclusive = source.length,
            truncated = true,
            mismatch = false,
            inString = inString,
            trailingEscape = escaped,
            open = open.toList(),
        )
    }

    private fun pairsWith(open: Char, close: Char): Boolean =
        (open == '{' && close == '}') || (open == '[' && close == ']')

    // ---------------------------------------------------------------- L5 字段校验

    private fun validate(element: JsonElement): ParseOutcome = when (element) {
        is JsonObject -> validateCall(element)
        is JsonArray -> validateArray(element)
        else -> ParseOutcome.Invalid("工具调用必须是 JSON 对象或数组")
    }

    /** 单调用校验：canonical 优先，缺失时兜底接受 name/arguments。 */
    private fun validateCall(obj: JsonObject): ParseOutcome {
        val tool = resolveTool(obj) ?: return ParseOutcome.Invalid("缺少 tool 字段")
        if (tool !in TOOL_WHITELIST) return ParseOutcome.Invalid("未知工具：$tool")
        val args = resolveArgs(obj) ?: return ParseOutcome.Invalid("args 必须是 JSON 对象")
        return ParseOutcome.Calls(listOf(ToolCall(tool = tool, argsJson = args.toString())))
    }

    private fun resolveTool(obj: JsonObject): String? {
        val canonical = (obj[KEY_TOOL] as? JsonPrimitive)?.contentOrNull
        if (!canonical.isNullOrBlank()) return canonical
        return (obj[KEY_NAME] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun resolveArgs(obj: JsonObject): JsonObject? {
        val element = obj[KEY_ARGS] ?: obj[KEY_ARGUMENTS]
        return when {
            element == null || element is JsonNull -> JsonObject(emptyMap())
            element is JsonObject -> element
            else -> null
        }
    }

    /** 数组按序展开；任一条目非法即整体失败并标明序号。 */
    private fun validateArray(array: JsonArray): ParseOutcome {
        if (array.isEmpty()) return ParseOutcome.NoToolCall
        val calls = mutableListOf<ToolCall>()
        array.forEachIndexed { index, element ->
            val obj = element as? JsonObject
                ?: return ParseOutcome.Invalid("数组第 ${index + 1} 个元素不是 JSON 对象")
            when (val outcome = validateCall(obj)) {
                is ParseOutcome.Calls -> calls += outcome.calls
                is ParseOutcome.Invalid ->
                    return ParseOutcome.Invalid("数组第 ${index + 1} 个元素：${outcome.reason}")
                ParseOutcome.NoToolCall ->
                    return ParseOutcome.Invalid("数组第 ${index + 1} 个元素不是工具调用")
            }
        }
        return ParseOutcome.Calls(calls)
    }

    private fun parseElement(text: String): JsonElement? =
        runCatching { json.parseToJsonElement(text) }.getOrNull()

    // ---------------------------------------------------------------- 内部数据结构

    /** 配平扫描结果；[open] 为未闭合的开启符栈（自底向上）。 */
    private data class Scan(
        val endExclusive: Int,
        val truncated: Boolean,
        val mismatch: Boolean,
        val inString: Boolean,
        val trailingEscape: Boolean,
        val open: List<Char>,
    )

    private data class Candidate(val outcome: ParseOutcome, val parsed: Boolean, val nextIndex: Int)

    private data class ParsedCandidate(val outcome: ParseOutcome, val parsed: Boolean)

    private sealed interface Repair {
        data class Repaired(val text: String) : Repair
        data class Failed(val reason: String) : Repair
    }

    companion object {
        private const val KEY_TOOL = "tool"
        private const val KEY_NAME = "name"
        private const val KEY_ARGS = "args"
        private const val KEY_ARGUMENTS = "arguments"
        private const val FENCE = "```"
        private const val MAX_REPAIR_MARKS = 3
        private const val JSON_PARSE_FAILED = "JSON 解析失败"
        private const val MISMATCH_REASON = "JSON 括号不匹配"
        private const val REPAIR_STILL_INVALID = "JSON 截断补全后仍无法解析"

        private val LANGUAGE_TAG = Regex("^[A-Za-z0-9_+.#-]{1,16}$")

        /** 工具键标记：用于围栏优先级与“正文数组不展开”判定。 */
        private val TOOL_KEY_MARKER = Regex("""["']?\b(?:tool|name)\b["']?\s*:""")

        private val json = Json { ignoreUnknownKeys = true }

        /** 四工具白名单（设计文档 3.3）。 */
        val TOOL_WHITELIST: Set<String> = setOf("read", "write", "edit", "draw_image")

        private fun looksLikeToolPayload(text: String): Boolean =
            TOOL_KEY_MARKER.containsMatchIn(text)
    }
}
