package com.thatgfsj.qaa.core.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 确认挂起展示文案（设计文档 3.6 与附录 B.3）：工具名 + 关键参数摘要，单行纯文本。
 *
 * 两端确认区（Terminal [TOOL] 行、Normal 确认对话框）共用同一文案，避免语义漂移；
 * 纯 JVM 函数，不依赖 Android。附录 B.3 的「新文件 / 覆盖标记」需沙盒探测，
 * 属可选字段，本期不展示。
 */

/** 单行文案上限：超长截断，避免撑破两端确认区。 */
private const val MAX_SUMMARY_LENGTH = 160

/** 变更片段上限：只保留可辨认的头部。 */
private const val MAX_SNIPPET_LENGTH = 24

/** draw_image 提示词上限。 */
private const val MAX_PROMPT_LENGTH = 60

/** 生成确认区展示文案；参数非法或工具未知时退化为「工具名 + 原始参数」。 */
fun summarizeToolCall(call: ToolCall): String {
    val args = runCatching { Json.parseToJsonElement(call.argsJson) as? JsonObject }.getOrNull()
    val summary = when (call.tool) {
        "write" -> writeSummary(args)
        "edit" -> editSummary(args)
        "draw_image" -> drawImageSummary(args)
        "read" -> readSummary(args)
        else -> null
    } ?: "${call.tool} ${call.argsJson.toSingleLine()}"
    return summary.toSingleLine().abbreviate(MAX_SUMMARY_LENGTH)
}

/** write：目标路径 + 内容字节数（附录 B.3 必展示字段）。 */
private fun writeSummary(args: JsonObject?): String? {
    val path = args.stringArg("path") ?: return null
    val bytes = (args.stringArg("content") ?: "").toByteArray().size
    return "write 路径=\"$path\" 内容=$bytes 字节"
}

/** edit：目标路径 + oldText/newText 前后片段。 */
private fun editSummary(args: JsonObject?): String? {
    val path = args.stringArg("path") ?: return null
    val oldText = args.stringArg("oldText") ?: return null
    val newText = args.stringArg("newText") ?: return null
    return "edit 路径=\"$path\" 替换 \"${oldText.abbreviate(MAX_SNIPPET_LENGTH)}\" → " +
        "\"${newText.abbreviate(MAX_SNIPPET_LENGTH)}\""
}

/** draw_image：提示词（参数名 prompt）。 */
private fun drawImageSummary(args: JsonObject?): String? {
    val prompt = args.stringArg("prompt") ?: return null
    return "draw_image 提示词=\"${prompt.abbreviate(MAX_PROMPT_LENGTH)}\""
}

/** read：目标路径（read 不触发确认，保留文案以覆盖其他调用方）。 */
private fun readSummary(args: JsonObject?): String? {
    val path = args.stringArg("path") ?: return null
    return "read 路径=\"$path\""
}

private fun JsonObject?.stringArg(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull

/** 多行参数折成单行，保证确认区一行展示。 */
private fun String.toSingleLine(): String = trim().replace(Regex("\\s+"), " ")

private fun String.abbreviate(max: Int): String =
    if (length <= max) this else take(max) + "…"
