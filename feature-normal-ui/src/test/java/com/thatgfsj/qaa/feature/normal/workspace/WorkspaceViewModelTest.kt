package com.thatgfsj.qaa.feature.normal.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 工作区页 JVM 单测（Issue #126）：清单加载、新建、切换、重命名、删除与宿主事件回推。 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun 绑定端口_清单来自装配层() = runTest {
        val vm = WorkspaceViewModel()
        val port = FakeWorkspacePort()
        vm.bindPort(port)
        advanceUntilIdle()

        assertTrue(vm.portBound.value)
        assertEquals(listOf("default", "ws-1"), vm.entries.value.map { it.id })
        assertEquals(listOf(true, false), vm.entries.value.map { it.current })
    }

    @Test
    fun 未绑定端口时新建给出接线提示() {
        val vm = WorkspaceViewModel()
        vm.onCreateNameChange("资料库")
        vm.createInternal()

        assertTrue(vm.notice.value!!.contains("未接线"))
    }

    @Test
    fun 新建空白名称本地拦截不触达端口() = runTest {
        val vm = WorkspaceViewModel()
        val port = FakeWorkspacePort()
        vm.bindPort(port)
        advanceUntilIdle()

        vm.onCreateNameChange("   ")
        vm.createInternal()
        advanceUntilIdle()

        assertTrue(vm.notice.value!!.contains("请输入工作区名称"))
        assertTrue(port.created.isEmpty())
    }

    @Test
    fun 新建调用端口并刷新清单() = runTest {
        val vm = WorkspaceViewModel()
        val port = FakeWorkspacePort()
        vm.bindPort(port)
        advanceUntilIdle()

        vm.onCreateNameChange("资料库")
        vm.createInternal()
        advanceUntilIdle()

        assertEquals(listOf("资料库"), port.created)
        assertEquals("", vm.createName.value)
        assertEquals("已新建工作区：资料库", vm.notice.value)
        assertEquals(3, vm.entries.value.size)
    }

    @Test
    fun 切换当前工作区不重复调用端口() = runTest {
        val vm = WorkspaceViewModel()
        val port = FakeWorkspacePort()
        vm.bindPort(port)
        advanceUntilIdle()

        vm.select(vm.entries.value.first { it.current })
        advanceUntilIdle()

        assertTrue(port.selected.isEmpty())
    }

    @Test
    fun 切换调用端口并更新当前标记() = runTest {
        val vm = WorkspaceViewModel()
        val port = FakeWorkspacePort()
        vm.bindPort(port)
        advanceUntilIdle()

        vm.select(vm.entries.value.first { it.id == "ws-1" })
        advanceUntilIdle()

        assertEquals(listOf("ws-1"), port.selected)
        assertTrue(vm.entries.value.first { it.id == "ws-1" }.current)
    }

    @Test
    fun 删除当前工作区直接拒绝() = runTest {
        val vm = WorkspaceViewModel()
        val port = FakeWorkspacePort()
        vm.bindPort(port)
        advanceUntilIdle()

        vm.requestRemove(vm.entries.value.first { it.current })
        advanceUntilIdle()

        assertNull(vm.pendingRemove.value)
        assertTrue(vm.notice.value!!.contains("当前工作区不可删除"))
        assertTrue(port.removed.isEmpty())
    }

    @Test
    fun 删除内置默认工作区直接拒绝() = runTest {
        val vm = WorkspaceViewModel()
        val port = FakeWorkspacePort()
        vm.bindPort(port)
        advanceUntilIdle()

        val builtIn = vm.entries.value.first { it.id == "default" }
        vm.requestRemove(builtIn.copy(current = false))
        advanceUntilIdle()

        assertTrue(vm.notice.value!!.contains("内置默认工作区不可删除"))
    }

    @Test
    fun 删除有会话的工作区确认后携带force() = runTest {
        val vm = WorkspaceViewModel()
        val port = FakeWorkspacePort()
        vm.bindPort(port)
        advanceUntilIdle()

        vm.requestRemove(vm.entries.value.first { it.id == "ws-1" })
        advanceUntilIdle()
        assertEquals("ws-1", vm.pendingRemove.value?.id)

        vm.confirmRemove()
        advanceUntilIdle()

        assertEquals(listOf("ws-1" to true), port.removed)
        assertEquals(1, vm.entries.value.size)
    }

    @Test
    fun 取消删除确认不调用端口() = runTest {
        val vm = WorkspaceViewModel()
        val port = FakeWorkspacePort()
        vm.bindPort(port)
        advanceUntilIdle()

        vm.requestRemove(vm.entries.value.first { it.id == "ws-1" })
        vm.cancelRemove()
        advanceUntilIdle()

        assertNull(vm.pendingRemove.value)
        assertTrue(port.removed.isEmpty())
    }

    @Test
    fun 重命名对话框回填并提交端口() = runTest {
        val vm = WorkspaceViewModel()
        val port = FakeWorkspacePort()
        vm.bindPort(port)
        advanceUntilIdle()

        vm.requestRename(vm.entries.value.first { it.id == "ws-1" })
        assertEquals("资料库", vm.renameName.value)
        vm.onRenameNameChange("资料库 2")
        vm.confirmRename()
        advanceUntilIdle()

        assertEquals(listOf("ws-1" to "资料库 2"), port.renamed)
    }

    @Test
    fun 宿主事件推送回显并刷新清单() = runTest {
        val vm = WorkspaceViewModel()
        val port = FakeWorkspacePort()
        vm.bindPort(port)
        advanceUntilIdle()
        val callsBefore = port.entriesCalls

        port.events.emit("已新建工作区：手机目录（id ws-9，外部目录）。")
        advanceUntilIdle()

        assertTrue(vm.notice.value!!.contains("已新建工作区：手机目录"))
        assertTrue(port.entriesCalls > callsBefore)
    }

    @Test
    fun 消费提示后清空() = runTest {
        val vm = WorkspaceViewModel()
        vm.bindPort(FakeWorkspacePort())
        advanceUntilIdle()
        vm.onCreateNameChange("资料库")
        vm.createInternal()
        advanceUntilIdle()

        vm.consumeNotice()
        assertNull(vm.notice.value)
    }
}

/** 工作区端口替身：清单与回执可脚本化，记录全部写操作。 */
private class FakeWorkspacePort(
    private var entries: List<NormalWorkspaceEntry> = defaultEntries(),
) : NormalWorkspacePort {

    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val workspaceEvents: SharedFlow<String> = events

    val created = mutableListOf<String>()
    val selected = mutableListOf<String>()
    val renamed = mutableListOf<Pair<String, String>>()
    val removed = mutableListOf<Pair<String, Boolean>>()
    var entriesCalls = 0

    override suspend fun workspaceEntries(): List<NormalWorkspaceEntry> {
        entriesCalls += 1
        return entries
    }

    override suspend fun selectWorkspace(id: String): String {
        selected += id
        entries = entries.map { it.copy(current = it.id == id) }
        return "已切换工作区：$id"
    }

    override suspend fun createWorkspace(name: String): String {
        created += name
        entries = entries + NormalWorkspaceEntry(
            id = "ws-${entries.size}",
            name = name,
            sourceLabel = "内置目录",
            rootDisplay = "files/workspaces/ws-${entries.size}",
            sessionCount = 0,
            writable = true,
            current = false,
        )
        return "已新建工作区：$name"
    }

    override suspend fun renameWorkspace(id: String, newName: String): String {
        renamed += id to newName
        return "已重命名工作区：$newName"
    }

    override suspend fun removeWorkspace(id: String, force: Boolean): String {
        removed += id to force
        entries = entries.filterNot { it.id == id }
        return "已删除工作区：$id"
    }

    override suspend fun createWorkspaceFromTree(treeUri: String): String = "已新建外部工作区：$treeUri"
}

/** 默认清单：内置默认工作区（当前）+ 一个带 2 个会话的内置工作区。 */
private fun defaultEntries(): List<NormalWorkspaceEntry> = listOf(
    NormalWorkspaceEntry(
        id = "default",
        name = "默认工作区",
        sourceLabel = "内置目录",
        rootDisplay = "files/workspaces/default",
        sessionCount = 1,
        writable = true,
        current = true,
    ),
    NormalWorkspaceEntry(
        id = "ws-1",
        name = "资料库",
        sourceLabel = "内置目录",
        rootDisplay = "files/workspaces/ws-1",
        sessionCount = 2,
        writable = true,
        current = false,
    ),
)
