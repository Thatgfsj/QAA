package com.thatgfsj.qaa.core.harness

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 工作区清单（Issue #126）：默认工作区、持久化、重名与保护规则、旧会话迁移幂等。 */
class WorkspaceStoreTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun store(): WorkspaceStore = WorkspaceStore(WorkspaceStore.manifestFile(tmp.root))

    @Test
    fun 首启只有默认工作区且当前指向它() {
        val store = store()

        assertEquals(listOf(WorkspaceStore.DEFAULT_ID), store.list().map { it.id })
        assertEquals(WorkspaceStore.DEFAULT_ID, store.currentId())
        assertEquals(WorkspaceStore.DEFAULT_NAME, store.current().name)
        assertEquals(WorkspaceSource.INTERNAL, store.current().source)
    }

    @Test
    fun 新建切换重命名重启后全部保留() {
        val first = store()
        val created = first.createInternal("资料库") as WorkspaceResult.Ok
        assertEquals(WorkspaceSource.INTERNAL, created.workspace.source)
        assertTrue(first.select(created.workspace.id) is WorkspaceResult.Ok)
        assertTrue(first.rename(created.workspace.id, "资料库 2") is WorkspaceResult.Ok)

        val reopened = store()
        assertEquals(listOf(WorkspaceStore.DEFAULT_ID, created.workspace.id), reopened.list().map { it.id })
        assertEquals(created.workspace.id, reopened.currentId())
        assertEquals("资料库 2", reopened.current().name)
    }

    @Test
    fun 名称非法或重复时拒绝写入() {
        val store = store()
        assertTrue(store.createInternal("   ") is WorkspaceResult.Refused)
        assertTrue(store.createInternal("x".repeat(WorkspaceStore.MAX_NAME_LENGTH + 1)) is WorkspaceResult.Refused)
        assertTrue(store.createInternal("资料库") is WorkspaceResult.Ok)
        val refused = store.createInternal("资料库")
        assertEquals(WorkspaceRefusal.NAME_TAKEN, (refused as WorkspaceResult.Refused).reason)
        assertEquals(2, store.list().size)
    }

    @Test
    fun 默认工作区与当前工作区不可删除() {
        val store = store()
        val created = (store.createInternal("临时") as WorkspaceResult.Ok).workspace

        assertEquals(WorkspaceRefusal.BUILT_IN, (store.remove(WorkspaceStore.DEFAULT_ID) as WorkspaceResult.Refused).reason)

        assertTrue(store.select(created.id) is WorkspaceResult.Ok)
        assertEquals(WorkspaceRefusal.CURRENT, (store.remove(created.id) as WorkspaceResult.Refused).reason)
        assertEquals(WorkspaceRefusal.CURRENT, (store.remove(created.id) as WorkspaceResult.Refused).reason)
        assertTrue(store.select(WorkspaceStore.DEFAULT_ID) is WorkspaceResult.Ok)
        assertTrue(store.remove(created.id) is WorkspaceResult.Ok)
        assertEquals(listOf(WorkspaceStore.DEFAULT_ID), store.list().map { it.id })
    }

    @Test
    fun SAF工作区记录树Uri且重启保留() {
        val store = store()
        val created = store.createSaf("手机目录", "content://tree/a") as WorkspaceResult.Ok
        assertEquals(WorkspaceSource.SAF, created.workspace.source)
        assertEquals("content://tree/a", store.resolve(created.workspace.id)?.treeUri)

        val reopened = store()
        assertEquals("content://tree/a", reopened.resolve(created.workspace.id)?.treeUri)
    }

    @Test
    fun 清单缺失或损坏时回退默认工作区() {
        WorkspaceStore.manifestFile(tmp.root).writeText("不是合法清单\n# current=ghost\nbroken\n")

        val store = store()
        assertEquals(1, store.list().size)
        assertEquals(WorkspaceStore.DEFAULT_ID, store.currentId())
    }

    @Test
    fun 旧会话迁移到默认工作区且幂等() {
        val sessionsRoot = File(tmp.root, "sessions")
        assertTrue(sessionsRoot.mkdirs())
        File(sessionsRoot, "main.jsonl").writeText("a\n")
        File(sessionsRoot, "main-2.jsonl").writeText("b\n")
        File(sessionsRoot, "notes.txt").writeText("keep")

        assertEquals(2, WorkspaceStore.migrateLegacySessions(sessionsRoot))
        assertTrue(File(sessionsRoot, "default/main.jsonl").isFile)
        assertTrue(File(sessionsRoot, "default/main-2.jsonl").isFile)
        assertTrue(File(sessionsRoot, "notes.txt").isFile)
        assertEquals(0, WorkspaceStore.migrateLegacySessions(sessionsRoot))
    }

    @Test
    fun 迁移目标已存在时不覆盖旧会话() {
        val sessionsRoot = File(tmp.root, "sessions")
        val target = File(sessionsRoot, "default")
        assertTrue(target.mkdirs())
        File(target, "main.jsonl").writeText("new")
        File(sessionsRoot, "main.jsonl").writeText("legacy")

        assertEquals(0, WorkspaceStore.migrateLegacySessions(sessionsRoot))
        assertEquals("new", File(target, "main.jsonl").readText())
        assertEquals("legacy", File(sessionsRoot, "main.jsonl").readText())
    }

    @Test
    fun 路径工具函数给出一致目录() {
        assertEquals(File(tmp.root, "workspaces/ws-3"), WorkspaceStore.internalDir(File(tmp.root, "workspaces"), "ws-3"))
        assertEquals(File(tmp.root, "sessions/ws-3"), WorkspaceStore.sessionsDir(File(tmp.root, "sessions"), "ws-3"))
        assertNull(store().current().treeUri)
        assertFalse(File(tmp.root, WorkspaceStore.FILE_NAME).isDirectory)
    }
}
