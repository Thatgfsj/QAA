package com.thatgfsj.qaa.core.tools

/**
 * 工具执行契约（docs/ARCHITECTURE.md 第 3 节 / docs/design/harness-core-design.md §3.1）。
 *
 * 四个原语工具的实现在本包内：read / write / edit / draw_image；
 * [ToolContext] 与 [ToolResult] 的正式定义见同包对应文件；
 * 确认挂起流（ConfirmationGate）与统一路由属 :core-harness 职责。
 */
interface ToolExecutor {
    val name: String
    val requiresConfirmation: Boolean
    suspend fun execute(argsJson: String, context: ToolContext): ToolResult
}
