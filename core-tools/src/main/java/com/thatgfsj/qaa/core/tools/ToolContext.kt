package com.thatgfsj.qaa.core.tools

/**
 * 工具上下文（docs/design/harness-core-design.md §3.2）。
 * 由宿主（Agent Loop 装配层）构造并逐次传入工具执行。
 */
interface ToolContext {

    /** 所属会话（树文件标识）。 */
    val sessionId: String

    /** 当前工具步序号。 */
    val stepIndex: Int

    /** 路径门面：所有路径先归一化并校验授权根，越界即拒绝。 */
    val sandbox: SandboxFs

    /** 输出截断预算。 */
    val limits: ToolLimits
}

/**
 * 输出截断预算（docs/design/harness-core-design.md §3.2）。
 * 默认值：read 8000 字符、write 64KB。
 */
data class ToolLimits(
    /** read：单次返回的最大字符数，超出截断并附截断标记与总长。 */
    val readMaxChars: Int = 8_000,
    /** write：单次写入的最大字节数（UTF-8）。 */
    val writeMaxBytes: Int = 64 * 1024,
) {
    init {
        require(readMaxChars > 0) { "readMaxChars 必须为正数" }
        require(writeMaxBytes > 0) { "writeMaxBytes 必须为正数" }
    }
}
