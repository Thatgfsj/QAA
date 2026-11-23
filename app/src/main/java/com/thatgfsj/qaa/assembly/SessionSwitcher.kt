package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.TreeSessionStore
import java.io.File

/**
 * 会话轮换：为 /new 分配稳定自增的会话 id，并在 sessions 目录下新建落盘文件。
 *
 * 新建动作只创建新文件，不读写已有会话文件；旧 store 由调用方关闭。
 */
internal class SessionSwitcher(
    private val sessionsDir: File,
    private val baseId: String = AgentRuntime.SESSION_ID,
) {

    /** 新建空会话（首行写 meta）；已有会话文件保持原样。 */
    fun openNext(): TreeSessionStore {
        val id = nextSessionId(existingIds())
        return TreeSessionStore(sessionId = id, file = TreeSessionStore.fileFor(sessionsDir, id))
    }

    /** 已存在的会话 id：只统计 baseId 与 baseId-<序号> 命名的会话文件。 */
    private fun existingIds(): Set<String> =
        sessionsDir.listFiles { file -> file.isFile && file.name.endsWith(SESSION_SUFFIX) }
            .orEmpty()
            .map { it.name.removeSuffix(SESSION_SUFFIX) }
            .filter(::isManagedId)
            .toSet()

    private fun isManagedId(id: String): Boolean =
        id == baseId || id.startsWith("$baseId-") && id.removePrefix("$baseId-").toIntOrNull() != null

    companion object {
        /** 会话文件后缀（sessions/<id>.jsonl）。 */
        const val SESSION_SUFFIX = ".jsonl"

        /** 下一个会话 id：baseId 未被占用时取 baseId，否则取最大序号 + 1。 */
        fun nextSessionId(existing: Collection<String>, baseId: String = AgentRuntime.SESSION_ID): String {
            if (baseId !in existing) return baseId
            val highest = existing.mapNotNull { id ->
                if (!id.startsWith("$baseId-")) null else id.removePrefix("$baseId-").toIntOrNull()
            }.maxOrNull() ?: 1
            return "$baseId-${highest + 1}"
        }
    }
}
