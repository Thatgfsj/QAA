package com.thatgfsj.qaa.feature.terminal

/**
 * 工作区端口（Issue #126）：由 :-app 装配层用 WorkspaceStore + 沙盒 / 会话目录实现。
 *
 * 终端只声明「要什么」：快照用于解析序号，[workspace] 返回可直接回显的中文结果。
 */
interface TerminalWorkspacePort {

    /** 当前工作区快照（含序号、会话数、是否当前），供解析器解析用户输入。 */
    suspend fun workspaces(): List<WorkspaceEntry>

    /** 执行工作区操作；成功与拒绝都返回中文回执。 */
    suspend fun workspace(request: WorkspaceRequest): String
}
