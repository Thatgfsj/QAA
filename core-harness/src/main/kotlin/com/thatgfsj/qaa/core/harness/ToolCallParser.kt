package com.thatgfsj.qaa.core.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * 工具调用解析器——本期最简占位：trim + 直接 JSON 解析 + 四工具白名单校验。
 * 五级容错提取管线（围栏剥离 / 首花括号定位 / 括号配平 / 截断修补 / 数组展开）
 * 由解析容错议题按设计文档 4.2 替换本实现，接口形状保持不变。
 */
class ToolCallParser {

    fun parse(text: String): ParseOutcome {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return ParseOutcome.NoToolCall
        val root = runCatching { json.parseToJsonElement(trimmed) }
            .getOrElse { return ParseOutcome.Invalid("JSON 解析失败") }
        val obj = root as? JsonObject
            ?: return ParseOutcome.Invalid("顶层不是 JSON 对象")
        val tool = (obj[KEY_TOOL] as? JsonPrimitive)?.content
            ?: return ParseOutcome.Invalid("缺少 tool 字段")
        if (tool !in TOOL_WHITELIST) return ParseOutcome.Invalid("未知工具：$tool")
        val args = (obj[KEY_ARGS] as? JsonObject) ?: JsonObject(emptyMap())
        return ParseOutcome.Calls(
            listOf(ToolCall(tool = tool, argsJson = args.toString()))
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val KEY_TOOL = "tool"
        private const val KEY_ARGS = "args"

        /** 四工具白名单（设计文档 3.3）。 */
        val TOOL_WHITELIST: Set<String> = setOf("read", "write", "edit", "draw_image")
    }
}
