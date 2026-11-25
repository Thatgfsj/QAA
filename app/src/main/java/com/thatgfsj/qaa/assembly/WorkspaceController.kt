package com.thatgfsj.qaa.assembly

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.thatgfsj.qaa.core.harness.TreeSessionStore
import com.thatgfsj.qaa.core.harness.Workspace
import com.thatgfsj.qaa.core.harness.WorkspaceRefusal
import com.thatgfsj.qaa.core.harness.WorkspaceResult
import com.thatgfsj.qaa.core.harness.WorkspaceSource
import com.thatgfsj.qaa.core.harness.WorkspaceStore
import com.thatgfsj.qaa.core.tools.LocalSandboxFs
import com.thatgfsj.qaa.core.tools.SandboxRoot
import com.thatgfsj.qaa.feature.terminal.WorkspaceEntry
import java.io.File

/** 工作区根不可用（SAF 授权被回收 / 目录不可读）：切换时以中文回执拒绝，保留旧工作区。 */
internal class WorkspaceUnavailableException(message: String) : IllegalStateException(message)

/**
 * 工作区目录 / SAF 授权 / 会话统计辅助（Issue #126）。
 *
 * 内置工作区根为 `files/workspaces/<id>/`；SAF 工作区根为系统授权的目录树，
 * 内容经 DocumentsContract 访问、不映射 /storage 真实路径。会话目录恒在私有目录：
 * `files/sessions/<id>/`（与根来源无关，SAF 只影响工具沙盒默认根）。
 */
internal object WorkspaceRoots {

    /** App 私有工作区根目录名（`files/workspaces/`）。 */
    const val WORKSPACES_DIR = "workspaces"

    /** App 私有工作区根目录。 */
    fun internalDir(filesDir: File, id: String): File = File(File(filesDir, WORKSPACES_DIR), id)

    /** 确保内置工作区目录存在并返回。 */
    fun ensureInternalDir(filesDir: File, id: String): File {
        val dir = internalDir(filesDir, id)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 工作区会话目录（`files/sessions/<id>/`）。 */
    fun sessionsDir(filesDir: File, id: String): File =
        WorkspaceStore.sessionsDir(File(filesDir, TreeSessionStore.DEFAULT_SESSIONS_DIR), id)

    /** 工作区会话文件数（删除影响面提示）。 */
    fun sessionCount(filesDir: File, id: String): Int =
        sessionsDir(filesDir, id).listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }.orEmpty().size

    /** 根目录中文展示（内置目录给相对路径，SAF 给树名）。 */
    fun rootDisplay(context: Context, filesDir: File, workspace: Workspace): String =
        when (workspace.source) {
            WorkspaceSource.INTERNAL -> "files/" + WORKSPACES_DIR + "/" + workspace.id
            WorkspaceSource.SAF -> "外部目录：" + (workspace.treeUri?.let { treeName(context, it) } ?: "未授权")
        }

    /** 解析工作区对应的沙盒根；SAF 授权缺失 / 不可读时抛 [WorkspaceUnavailableException]。 */
    fun rootOf(context: Context, filesDir: File, workspace: Workspace): SandboxRoot =
        when (workspace.source) {
            WorkspaceSource.INTERNAL -> {
                val dir = ensureInternalDir(filesDir, workspace.id)
                SandboxRoot.File(AgentRuntime.SANDBOX_ROOT_ID, dir.toPath())
            }
            WorkspaceSource.SAF -> {
                val uri = workspace.treeUri?.let(Uri::parse)
                    ?: throw WorkspaceUnavailableException("工作区 ${workspace.name} 缺少目录授权：请在工作区页重新选择手机目录。")
                if (!hasReadPermission(context, uri)) {
                    throw WorkspaceUnavailableException(
                        "工作区 ${workspace.name} 的系统授权已失效：请重新选择该目录（或删除后重建工作区）。",
                    )
                }
                val display = treeName(context, uri.toString()) ?: "SAF 目录"
                SandboxRoot.Saf(
                    AgentRuntime.SANDBOX_ROOT_ID,
                    uri.toString(),
                    display,
                    ContentResolverSafGateway(context.contentResolver),
                )
            }
        }

    /** 持久化目录树授权（优先读写，提供者只给读权限时退化为只读）。 */
    fun persistPermission(context: Context, uri: Uri): Boolean {
        if (!DocumentsContract.isTreeUri(uri)) return false
        val resolver = context.contentResolver
        val writeGranted = runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (!writeGranted) {
            val readGranted = runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (!readGranted) return false
        }
        return hasReadPermission(context, uri)
    }

    /** 释放目录树持久化授权（删除工作区时）。 */
    fun releasePermission(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        var flags = 0
        if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (flags == 0) return
        runCatching { resolver.releasePersistableUriPermission(uri, flags) }
    }

    /** 系统是否仍持有该树的读授权。 */
    fun hasReadPermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /** 系统是否仍持有该树的写授权（工作区页「可写」标记）。 */
    fun hasWritePermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    /** 查询树根目录显示名；提供者不可用时返回 null。 */
    fun treeName(context: Context, treeUri: String): String? = runCatching {
        ContentResolverSafGateway(context.contentResolver).queryDocument(treeUri, "")?.name
    }.getOrNull()?.ifBlank { null }

    /** 删除工作区的私有数据：内置目录与其会话目录；SAF 工作区只释放授权、不动手机文件。 */
    fun deleteWorkspaceData(context: Context, filesDir: File, workspace: Workspace) {
        when (workspace.source) {
            WorkspaceSource.INTERNAL -> {
                runCatching { internalDir(filesDir, workspace.id).deleteRecursively() }
                runCatching { sessionsDir(filesDir, workspace.id).deleteRecursively() }
            }
            WorkspaceSource.SAF -> workspace.treeUri?.let { runCatching { releasePermission(context, Uri.parse(it)) } }
        }
    }
}

/**
 * 工作区控制器（Issue #126）：Store 清单 + 沙盒默认根切换 + SAF 授权 + 会话统计。
 *
 * 终端 `/workspace` 与 Normal 工作区页共用本控制器；会话文件切换（sessionStore /
 * SessionSwitcher 重建）由 [AgentRuntime] 在切换成功后执行，控制器只负责根与清单。
 */
class WorkspaceController(
    private val context: Context,
    private val filesDir: File,
    private val store: WorkspaceStore,
    private val sandbox: LocalSandboxFs,
) {

    init {
        // 启动即补齐内置目录：工作区目录缺失时工具沙盒根会构造失败。
        store.list().filter { it.source == WorkspaceSource.INTERNAL }
            .forEach { WorkspaceRoots.ensureInternalDir(filesDir, it.id) }
    }

    /** 当前工作区。 */
    fun current(): Workspace = store.current()

    /** 当前工作区 id。 */
    fun currentId(): String = store.currentId()

    /** 指定工作区的会话目录。 */
    fun sessionsDir(workspaceId: String): File = WorkspaceRoots.sessionsDir(filesDir, workspaceId)

    /** 按 id 或名称解析工作区。 */
    fun resolve(token: String): Workspace? = store.resolve(token)

    /** 终端工作区快照（序号按清单顺序，当前工作区带标记）。 */
    fun terminalEntries(): List<WorkspaceEntry> =
        store.list().mapIndexed { index, workspace ->
            WorkspaceEntry(
                index = index,
                id = workspace.id,
                name = workspace.name,
                source = workspace.source,
                rootDisplay = WorkspaceRoots.rootDisplay(context, filesDir, workspace),
                sessionCount = WorkspaceRoots.sessionCount(filesDir, workspace.id),
                writable = isWritable(workspace),
                current = workspace.id == store.currentId(),
            )
        }


    /** 把当前工作区的根挂载为沙盒默认根（保留 ext 等附加根）。 */
    fun attachCurrentRoot() {
        applyRoot(store.current())
    }

    /** 切换工作区并持久化；根不可用时抛出 [WorkspaceUnavailableException] 且不改清单。 */
    fun select(id: String): Workspace {
        val workspace = store.resolve(id) ?: throw WorkspaceUnavailableException("未找到工作区：$id。")
        applyRoot(workspace)
        when (val result = store.select(workspace.id)) {
            is WorkspaceResult.Ok -> return result.workspace
            is WorkspaceResult.Refused -> throw WorkspaceUnavailableException(refusalText(result.reason))
        }
    }

    /** 新建内置工作区；失败返回中文原因。 */
    fun createInternal(name: String): String {
        val created = when (val result = store.createInternal(name)) {
            is WorkspaceResult.Ok -> result.workspace
            is WorkspaceResult.Refused -> return refusalText(result.reason)
        }
        WorkspaceRoots.ensureInternalDir(filesDir, created.id)
        WorkspaceRoots.sessionsDir(filesDir, created.id).mkdirs()
        return "已新建工作区：${created.name}（id ${created.id}，内置目录 files/${WorkspaceRoots.WORKSPACES_DIR}/${created.id}）。" +
            "使用 /workspace use ${created.id} 切换。"
    }

    /** 新建 SAF 工作区：持久化授权 + 查询树名 + 登记清单；不切换当前工作区。 */
    fun createFromTree(uri: Uri): String {
        if (!DocumentsContract.isTreeUri(uri)) return "新建工作区失败：所选位置不是目录树。"
        if (!WorkspaceRoots.persistPermission(context, uri)) {
            return "新建工作区失败：无法持久化目录访问权限（系统拒绝或提供者不支持）。"
        }
        val existing = store.list().firstOrNull { it.source == WorkspaceSource.SAF && it.treeUri == uri.toString() }
        if (existing != null) return "该目录已作为工作区存在：${existing.name}（id ${existing.id}）。"
        val name = WorkspaceRoots.treeName(context, uri.toString()) ?: "外部工作区"
        val created = when (val result = store.createSaf(name, uri.toString())) {
            is WorkspaceResult.Ok -> result.workspace
            is WorkspaceResult.Refused -> {
                WorkspaceRoots.releasePermission(context, uri)
                return refusalText(result.reason)
            }
        }
        return "已新建工作区：${created.name}（id ${created.id}，外部目录）。切换请执行 /workspace use ${created.id}。"
    }

    /** 重命名；失败返回中文原因。 */
    fun rename(id: String, newName: String): String =
        when (val result = store.rename(id, newName)) {
            is WorkspaceResult.Ok -> "已重命名工作区：${result.workspace.name}（id ${result.workspace.id}）。"
            is WorkspaceResult.Refused -> refusalText(result.reason)
        }

    /** 删除工作区；有会话且未带 force 时拒绝（解析层已提示，这里兜底 Normal 入口）。 */
    fun remove(id: String, force: Boolean): String {
        val workspace = store.resolve(id) ?: return "未找到工作区：$id。"
        if (workspace.id == store.currentId()) return "当前工作区不可删除：请先切换到其他工作区。"
        if (workspace.id == WorkspaceStore.DEFAULT_ID) return "内置默认工作区不可删除。"
        val sessions = WorkspaceRoots.sessionCount(filesDir, workspace.id)
        if (sessions > 0 && !force) {
            return "工作区 ${workspace.name} 还有 $sessions 个会话：确认删除请携带 --force（会话文件将一并删除）。"
        }
        when (val result = store.remove(workspace.id)) {
            is WorkspaceResult.Refused -> return refusalText(result.reason)
            is WorkspaceResult.Ok -> Unit
        }
        WorkspaceRoots.deleteWorkspaceData(context, filesDir, workspace)
        return "已删除工作区：${workspace.name}（id ${workspace.id}）。"
    }

    private fun applyRoot(workspace: Workspace) {
        val root = WorkspaceRoots.rootOf(context, filesDir, workspace)
        val extras = sandbox.roots().drop(1)
        sandbox.replaceRoots(listOf(root) + extras)
    }

    private fun isWritable(workspace: Workspace): Boolean = when (workspace.source) {
        WorkspaceSource.INTERNAL -> true
        WorkspaceSource.SAF -> workspace.treeUri?.let { WorkspaceRoots.hasWritePermission(context, Uri.parse(it)) } ?: false
    }

    private fun refusalText(reason: WorkspaceRefusal): String = when (reason) {
        WorkspaceRefusal.NOT_FOUND -> "未找到工作区。"
        WorkspaceRefusal.BUILT_IN -> "内置默认工作区不可删除。"
        WorkspaceRefusal.CURRENT -> "当前工作区不可删除：请先切换到其他工作区。"
        WorkspaceRefusal.INVALID_NAME -> "工作区名称无效：需要 1-${WorkspaceStore.MAX_NAME_LENGTH} 个字符且不含控制字符。"
        WorkspaceRefusal.NAME_TAKEN -> "工作区名称已被占用：请换一个名称。"
    }
}
