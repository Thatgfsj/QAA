package com.thatgfsj.qaa.feature.normal.workspace

import kotlinx.coroutines.flow.SharedFlow

/**
 * 工作区页条目（Issue #126）：由 :-app 装配层把 WorkspaceStore + 沙盒根投影给 UI。
 *
 * [rootDisplay] 为根目录中文展示；[sessionCount] 是删除前的影响面提示；[writable] 反映
 * SAF 树是否具备写权限（内置目录恒可写）。
 */
data class NormalWorkspaceEntry(
    val id: String,
    val name: String,
    val sourceLabel: String,
    val rootDisplay: String,
    val sessionCount: Int,
    val writable: Boolean,
    val current: Boolean,
)

/**
 * Normal 工作区端口（Issue #126）：页面只声明「要什么」，实现由 :-app 注入。
 *
 * SAF 目录选择依赖 Activity 结果 API，因此选择动作由宿主回调触发，选中后调用
 * [createWorkspaceFromTree]；[workspaceEvents] 用于把宿主侧的创建结果推回页面
 * （页面据此刷新清单并回显，避免 UI 与 :app 各自维护状态）。
 */
interface NormalWorkspacePort {

    /** 宿主侧工作区变更事件（SAF 新建完成 / 取消等中文回执），页面订阅后刷新清单。 */
    val workspaceEvents: SharedFlow<String>

    /** 当前工作区清单。 */
    suspend fun workspaceEntries(): List<NormalWorkspaceEntry>

    /** 切换当前工作区（切换工具沙盒默认根与会话目录）。 */
    suspend fun selectWorkspace(id: String): String

    /** 新建 App 私有工作区。 */
    suspend fun createWorkspace(name: String): String

    /** 重命名工作区（id 与根目录不变）。 */
    suspend fun renameWorkspace(id: String, newName: String): String

    /** 删除非当前工作区；[force] 为 true 时允许连带删除会话文件。 */
    suspend fun removeWorkspace(id: String, force: Boolean): String

    /** SAF 目录选择结果（:app 启动器回传树 Uri 字符串）：授权并登记为外部工作区。 */
    suspend fun createWorkspaceFromTree(treeUri: String): String
}
