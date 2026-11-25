package com.thatgfsj.qaa.feature.terminal

import com.thatgfsj.qaa.core.harness.WorkspaceSource

/**
 * 工作区快照条目（Issue #126）：解析器据此解析 `/workspace use|rename|rm` 的序号 / id，
 * 概览与列表文案由解析层生成，:-app 只提供真实数据。
 *
 * [sessionCount] 为该工作区会话目录下的会话文件数（删除前提示影响面）；[writable] 为 SAF
 * 树是否具备写权限；[rootDisplay] 为根的中文展示（内置目录显示相对路径，SAF 显示树名）。
 */
data class WorkspaceEntry(
    val index: Int,
    val id: String,
    val name: String,
    val source: WorkspaceSource,
    val rootDisplay: String,
    val sessionCount: Int,
    val writable: Boolean,
    val current: Boolean,
) {
    /** 来源中文标签。 */
    val sourceLabel: String get() = if (source == WorkspaceSource.SAF) "外部目录" else "内置目录"

    /** 单行展示文本：名称（来源，id），当前工作区追加标记。 */
    val displayText: String
        get() = "$name（$sourceLabel，$id）" + if (current) " · 当前" else ""
}

/** `/workspace` 子命令（解析产物）：执行体统一接收本请求，避免 UI 层再解析字符串。 */
sealed interface WorkspaceRequest {

    /** 无参：当前工作区概览（名称 / 来源 / 根 / 可写 / 会话数）。 */
    data object Overview : WorkspaceRequest

    /** `list`：列出全部工作区。 */
    data object ListAll : WorkspaceRequest

    /** `new <名称>`：新建 App 私有工作区。 */
    data class Create(val name: String) : WorkspaceRequest

    /** `use <序号|id>`：切换当前工作区（切换工具沙盒默认根与会话目录）。 */
    data class Use(val id: String) : WorkspaceRequest

    /** `rename <序号|id> <新名>`：重命名（id 与根目录不变）。 */
    data class Rename(val id: String, val newName: String) : WorkspaceRequest

    /** `rm <序号|id> [--force]`：删除非当前工作区（有会话时需 --force）。 */
    data class Remove(val id: String, val force: Boolean) : WorkspaceRequest
}
