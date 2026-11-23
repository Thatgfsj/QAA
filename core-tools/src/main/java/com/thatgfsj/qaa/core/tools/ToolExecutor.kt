package com.thatgfsj.qaa.core.tools

/**
 * 工具执行契约（docs/ARCHITECTURE.md 第 3 节）。
 *
 * 统一路由与四个原语工具（read / write / edit / draw_image）、沙盒文件系统
 * 属后续 Issue #12；此处仅钉住契约签名。
 */
interface ToolExecutor {
    val name: String
    val requiresConfirmation: Boolean
    suspend fun execute(argsJson: String, context: ToolContext): ToolResult
}

/** 工具上下文占位：字段（沙盒根、会话句柄等）随 Issue #12 敲定。 */
class ToolContext

/** 工具结果占位：字段（成功标记、内容、错误码）随 Issue #12 敲定。 */
class ToolResult
