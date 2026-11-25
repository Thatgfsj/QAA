package com.thatgfsj.qaa.core.harness

import java.io.File
import java.io.IOException

/**
 * 工作区来源（Issue #126）：App 私有目录内的工作区目录，或用户在系统目录选择器授权的 SAF 树。
 */
enum class WorkspaceSource {
    /** App 私有目录：`files/workspaces/<id>/`，无需任何系统授权。 */
    INTERNAL,

    /** 系统 SAF 授权树：内容经 DocumentsContract 访问，不映射 /storage 真实路径。 */
    SAF,
}

/**
 * 工作区（Issue #126）：工具沙盒的默认根 + 名称 + 来源。
 *
 * [id] 稳定不变（持久化键），[name] 可重命名；SAF 工作区额外记录 [treeUri]。
 * 会话目录按工作区隔离为 `files/sessions/<id>/`，与会话文件内容无关。
 */
data class Workspace(
    val id: String,
    val name: String,
    val source: WorkspaceSource,
    /** SAF 树的持久化 Uri；INTERNAL 工作区为 null。 */
    val treeUri: String? = null,
)

/** 工作区写操作的结果：成功返回最新工作区（删除 / 切换返回被操作对象），失败给出结构化原因。 */
sealed interface WorkspaceResult {
    /** 成功；[workspace] 为本次操作涉及的工作区。 */
    data class Ok(val workspace: Workspace) : WorkspaceResult

    /** 拒绝执行；原因由 :app 装配层翻译为中文回执。 */
    data class Refused(val reason: WorkspaceRefusal) : WorkspaceResult
}

/** 工作区操作被拒绝的结构化原因。 */
enum class WorkspaceRefusal {
    /** 目标工作区不存在（id 失配或已被删除）。 */
    NOT_FOUND,

    /** 内置默认工作区不可删除。 */
    BUILT_IN,

    /** 当前工作区不可删除（先切换到其他工作区）。 */
    CURRENT,

    /** 名称不合法（空白、超长或含控制字符）。 */
    INVALID_NAME,

    /** 名称与现有工作区重复（不区分大小写）。 */
    NAME_TAKEN,
}

/**
 * 工作区持久化清单（Issue #126，纯 JVM，零 Android 依赖，可 JVM 单测）。
 *
 * 文件为 TSV：`# current=<id>` 记录当前工作区，数据行为 `id\tname\tsource\ttreeUri`；
 * 写入走「临时文件 + 原子重命名」（与 ModelManifest 同一模式）。
 * 清单缺失或损坏时回退到内置默认工作区（[DEFAULT_ID]），不阻断启动。
 */
class WorkspaceStore(private val file: File) {

    private var workspaces: List<Workspace> = emptyList()
    private var current: String = DEFAULT_ID

    init {
        load()
    }

    /** 当前工作区清单（默认工作区固定首位，其余按名称稳定排序以便终端序号稳定）。 */
    @Synchronized
    fun list(): List<Workspace> = workspaces

    /** 当前工作区 id（清单损坏时回退到默认工作区）。 */
    @Synchronized
    fun currentId(): String = current

    /** 当前工作区对象（清单保证非空）。 */
    @Synchronized
    fun current(): Workspace = workspaces.firstOrNull { it.id == current } ?: workspaces.first()

    /** 新建 App 私有工作区：目录由装配层创建，这里只登记清单。 */
    @Synchronized
    fun createInternal(name: String): WorkspaceResult {
        val trimmed = validateName(name) ?: return WorkspaceResult.Refused(WorkspaceRefusal.INVALID_NAME)
        if (isNameTaken(trimmed, exceptId = null)) return WorkspaceResult.Refused(WorkspaceRefusal.NAME_TAKEN)
        val workspace = Workspace(id = nextId(), name = trimmed, source = WorkspaceSource.INTERNAL)
        workspaces = workspaces + workspace
        persist()
        return WorkspaceResult.Ok(workspace)
    }

    /** 新建 SAF 工作区：持久化系统授权与树 Uri 由装配层负责，这里只登记清单。 */
    @Synchronized
    fun createSaf(name: String, treeUri: String): WorkspaceResult {
        val trimmed = validateName(name) ?: return WorkspaceResult.Refused(WorkspaceRefusal.INVALID_NAME)
        if (treeUri.isBlank()) return WorkspaceResult.Refused(WorkspaceRefusal.INVALID_NAME)
        if (isNameTaken(trimmed, exceptId = null)) return WorkspaceResult.Refused(WorkspaceRefusal.NAME_TAKEN)
        val workspace = Workspace(id = nextId(), name = trimmed, source = WorkspaceSource.SAF, treeUri = treeUri)
        workspaces = workspaces + workspace
        persist()
        return WorkspaceResult.Ok(workspace)
    }

    /** 切换当前工作区；[id] 不存在时拒绝。 */
    @Synchronized
    fun select(id: String): WorkspaceResult {
        val workspace = workspaces.firstOrNull { it.id == id }
            ?: return WorkspaceResult.Refused(WorkspaceRefusal.NOT_FOUND)
        current = workspace.id
        persist()
        return WorkspaceResult.Ok(workspace)
    }

    /** 重命名工作区；内置默认工作区允许改名（id 与根目录不变）。 */
    @Synchronized
    fun rename(id: String, newName: String): WorkspaceResult {
        val workspace = workspaces.firstOrNull { it.id == id }
            ?: return WorkspaceResult.Refused(WorkspaceRefusal.NOT_FOUND)
        val trimmed = validateName(newName) ?: return WorkspaceResult.Refused(WorkspaceRefusal.INVALID_NAME)
        if (isNameTaken(trimmed, exceptId = id)) return WorkspaceResult.Refused(WorkspaceRefusal.NAME_TAKEN)
        val updated = workspace.copy(name = trimmed)
        workspaces = workspaces.map { if (it.id == id) updated else it }
        persist()
        return WorkspaceResult.Ok(updated)
    }

    /** 删除工作区：内置默认工作区与当前工作区拒绝；目录与会话清理由装配层执行。 */
    @Synchronized
    fun remove(id: String): WorkspaceResult {
        val workspace = workspaces.firstOrNull { it.id == id }
            ?: return WorkspaceResult.Refused(WorkspaceRefusal.NOT_FOUND)
        if (workspace.id == DEFAULT_ID) return WorkspaceResult.Refused(WorkspaceRefusal.BUILT_IN)
        if (workspace.id == current) return WorkspaceResult.Refused(WorkspaceRefusal.CURRENT)
        workspaces = workspaces.filterNot { it.id == id }
        persist()
        return WorkspaceResult.Ok(workspace)
    }

    /** 按 id 或名称（不区分大小写）查找；供解析层把用户输入映射为 id。 */
    @Synchronized
    fun resolve(token: String): Workspace? {
        val trimmed = token.trim()
        return workspaces.firstOrNull { it.id == trimmed }
            ?: workspaces.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
    }

    private fun load() {
        val loaded = runCatching { readManifest() }.getOrDefault(ManifestEntries(emptyList(), null))
        workspaces = loaded.entries.ifEmpty { listOf(defaultWorkspace()) }
        current = loaded.current?.takeIf { id -> workspaces.any { it.id == id } } ?: workspaces.first().id
    }

    private fun readManifest(): ManifestEntries {
        if (!file.isFile) return ManifestEntries(emptyList(), null)
        var currentId: String? = null
        val entries = mutableListOf<Workspace>()
        file.readLines().forEach { line ->
            val trimmed = line.trimEnd('\r')
            if (trimmed.isBlank()) return@forEach
            if (trimmed.startsWith("# current=")) {
                currentId = trimmed.removePrefix("# current=").trim().ifEmpty { null }
                return@forEach
            }
            if (trimmed.startsWith("#")) return@forEach
            val fields = trimmed.split('\t')
            if (fields.size < 3) return@forEach
            val id = fields[0].trim().ifEmpty { return@forEach }
            val source = WorkspaceSource.entries.firstOrNull { it.name == fields[2].trim() } ?: return@forEach
            val treeUri = fields.getOrNull(3)?.trim()?.ifEmpty { null }
            entries += Workspace(id = id, name = fields[1].trim().ifEmpty { id }, source = source, treeUri = treeUri)
        }
        return ManifestEntries(entries, currentId)
    }

    private fun persist() {
        val parent = file.absoluteFile.parentFile ?: File(".")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("无法创建工作区目录：" + parent.absolutePath)
        val tmp = File(parent, file.name + ".tmp")
        try {
            tmp.bufferedWriter().use { writer ->
                writer.write("# current=" + current)
                writer.newLine()
                writer.write(HEADER)
                writer.newLine()
                workspaces.forEach { workspace ->
                    writer.write(
                        listOf(
                            workspace.id,
                            workspace.name.replace('\t', ' ').replace('\n', ' '),
                            workspace.source.name,
                            workspace.treeUri.orEmpty(),
                        ).joinToString("\t"),
                    )
                    writer.newLine()
                }
            }
            if (file.exists() && !file.delete()) throw IOException("无法覆盖工作区清单：" + file.absolutePath)
            if (!tmp.renameTo(file)) throw IOException("工作区清单改名失败：" + tmp.absolutePath)
        } catch (throwable: Throwable) {
            tmp.delete()
            throw throwable
        }
    }

    private fun defaultWorkspace(): Workspace =
        Workspace(id = DEFAULT_ID, name = DEFAULT_NAME, source = WorkspaceSource.INTERNAL)

    private fun validateName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) return null
        if (trimmed.any { it.isISOControl() }) return null
        return trimmed
    }

    private fun isNameTaken(name: String, exceptId: String?): Boolean =
        workspaces.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) }

    private fun nextId(): String {
        val highest = workspaces.mapNotNull { workspace ->
            workspace.id.removePrefix(ID_PREFIX).toIntOrNull()
        }.maxOrNull() ?: 0
        return ID_PREFIX + (highest + 1)
    }

    private data class ManifestEntries(val entries: List<Workspace>, val current: String?)

    companion object {
        /** 持久化文件名（位于 App 私有目录）。 */
        const val FILE_NAME = "workspaces.tsv"

        /** 内置默认工作区 id：旧版会话文件（`files/sessions/` 下的 `*.jsonl`）迁入 `files/sessions/default/`。 */
        const val DEFAULT_ID = "default"

        /** 内置默认工作区展示名。 */
        const val DEFAULT_NAME = "默认工作区"

        /** 单次输入的工作区名称长度上限。 */
        const val MAX_NAME_LENGTH = 24

        private const val ID_PREFIX = "ws-"

        private const val HEADER = "# id\tname\tsource\ttreeUri"

        /** 工作区清单文件（[appDir] 下的 [FILE_NAME]）。 */
        fun manifestFile(appDir: File): File = File(appDir, FILE_NAME)

        /** App 私有工作区根目录（`files/workspaces/<id>/`）。 */
        fun internalDir(workspacesDir: File, id: String): File = File(workspacesDir, id)

        /** 工作区会话目录（`files/sessions/<id>/`）。 */
        fun sessionsDir(sessionsRoot: File, id: String): File = File(sessionsRoot, id)

        /**
         * 迁移旧版会话（Issue #126）：把 [sessionsRoot] 根下的 `*.jsonl` 移入 `sessions/default/`。
         *
         * 幂等：根下无会话文件时返回 0；目标文件已存在时保留旧文件不覆盖（宁可留待人工处理，不丢历史）。
         * @return 实际迁移的会话文件数
         */
        fun migrateLegacySessions(sessionsRoot: File, defaultId: String = DEFAULT_ID): Int {
            val target = sessionsDir(sessionsRoot, defaultId)
            val legacy = sessionsRoot.listFiles { candidate ->
                candidate.isFile && candidate.name.endsWith(".jsonl")
            }.orEmpty()
            if (legacy.isEmpty()) return 0
            if (!target.exists() && !target.mkdirs()) return 0
            var moved = 0
            legacy.forEach { source ->
                val destination = File(target, source.name)
                if (destination.exists()) return@forEach
                if (source.renameTo(destination)) moved++
            }
            return moved
        }
    }
}
