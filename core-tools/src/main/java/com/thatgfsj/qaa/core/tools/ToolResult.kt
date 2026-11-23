package com.thatgfsj.qaa.core.tools

/**
 * 工具结果四态（docs/design/harness-core-design.md §3.4）。
 */
enum class ToolResultStatus { SUCCESS, FAILURE, DENIED, PENDING }

/**
 * 统一工具结果（docs/design/harness-core-design.md §3.4）。
 *
 * @param status 四态之一。
 * @param output 回注 LLM 的文本，四态皆有：SUCCESS 为内容/结果摘要；FAILURE 为
 *   人可读原因（含稳定错误码）；DENIED / PENDING 为固定话术。
 * @param dataJson 可选结构化附加数据（如图片路径），无则 null。
 * @param errorCode 仅 FAILURE 携带的稳定错误码（见 [ToolErrorCode]）。
 * @param durationMs 工具执行耗时（毫秒）。
 */
data class ToolResult(
    val status: ToolResultStatus,
    val output: String,
    val dataJson: String? = null,
    val errorCode: String? = null,
    val durationMs: Long = 0L,
) {
    companion object {

        /** DENIED 固定话术（用户拒绝后回注）。 */
        const val DENIED_OUTPUT = "已拒绝执行该工具。可调整方案，或改用纯文字继续。"

        /** PENDING 固定话术（仅 UI 与内存态，不回注 LLM、不落树）。 */
        const val PENDING_OUTPUT = "等待用户决策（Allow / Deny）。"

        fun success(output: String, dataJson: String? = null, durationMs: Long = 0L): ToolResult =
            ToolResult(ToolResultStatus.SUCCESS, output, dataJson, null, durationMs)

        fun failure(errorCode: String, output: String, durationMs: Long = 0L): ToolResult =
            ToolResult(ToolResultStatus.FAILURE, output, null, errorCode, durationMs)

        fun denied(durationMs: Long = 0L): ToolResult =
            ToolResult(ToolResultStatus.DENIED, DENIED_OUTPUT, null, null, durationMs)

        fun pending(durationMs: Long = 0L): ToolResult =
            ToolResult(ToolResultStatus.PENDING, PENDING_OUTPUT, null, null, durationMs)
    }
}

/** 工具执行耗时换算（纳秒起点 → 毫秒）。 */
internal fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

/**
 * 稳定错误码（docs/design/harness-core-design.md §3.5，共 10 个）。
 * 错误码是对 LLM 与测试共用的稳定标识，一经发布不复用改名。
 */
object ToolErrorCode {

    /** 参数缺失 / 类型错误 / JSON 解析失败。 */
    const val ARGS_INVALID = "E_ARGS_INVALID"

    /** 路径越界（../ 段、绝对路径、反斜杠、盘符样式、符号链接逃逸、NUL 字符）。 */
    const val PATH_ESCAPE = "E_PATH_ESCAPE"

    /** 目标文件不存在（read / edit），或待替换片段在文件中未出现。 */
    const val NOT_FOUND = "E_NOT_FOUND"

    /** edit：待替换片段出现多处且未要求全量替换。 */
    const val MULTI_MATCH = "E_MULTI_MATCH"

    /** edit：oldText 为空串。 */
    const val EMPTY_OLD_TEXT = "E_EMPTY_OLD_TEXT"

    /** read：内容判定为二进制文件（前 8KB 含 NUL）。 */
    const val BINARY_FILE = "E_BINARY_FILE"

    /** write：内容超出截断预算。 */
    const val SIZE_LIMIT = "E_SIZE_LIMIT"

    /** 底层 IO 故障。 */
    const val IO = "E_IO"

    /** draw_image：画图引擎未接入（占位行为）。 */
    const val ENGINE_NOT_AVAILABLE = "E_ENGINE_NOT_AVAILABLE"

    /** 画图与 LLM 内存互斥校验失败。 */
    const val MEMORY_MUTEX = "E_MEMORY_MUTEX"
}
