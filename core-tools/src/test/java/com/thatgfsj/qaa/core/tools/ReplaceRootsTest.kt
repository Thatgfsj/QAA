package com.thatgfsj.qaa.core.tools

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 授权根整体替换（Issue #126 工作区切换）：默认根切换、附加根保留、非法入参拒绝。 */
class ReplaceRootsTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    @Test
    fun 替换默认根后读写落到新目录() = runBlocking {
        val first = tmp.newFolder("first")
        val second = tmp.newFolder("second")
        val sandbox = LocalSandboxFs(listOf(SandboxRoot.File("files", first.toPath())))
        sandbox.writeText("a.txt", "旧工作区")

        sandbox.replaceRoots(listOf(SandboxRoot.File("files", second.toPath())))

        assertFalse(sandbox.exists("a.txt"))
        sandbox.writeText("b.txt", "新工作区")
        assertEquals("新工作区", sandbox.readText("b.txt", 10))
        assertEquals("新工作区", File(second, "b.txt").readText())
        assertFalse(File(first, "b.txt").exists())
        assertEquals(listOf("files"), sandbox.roots().map { it.id })
    }

    @Test
    fun 替换时保留附加SAF根() = runBlocking {
        val first = tmp.newFolder("first")
        val second = tmp.newFolder("second")
        val gateway = FakeSafGateway()
        val ext = SandboxRoot.Saf("ext", "content://tree/ext", "外部目录", gateway)
        val sandbox = LocalSandboxFs(listOf(SandboxRoot.File("files", first.toPath()), ext))
        sandbox.writeText("ext/keep.txt", "保留")

        val extras = sandbox.roots().drop(1)
        sandbox.replaceRoots(listOf(SandboxRoot.File("files", second.toPath())) + extras)

        assertEquals(listOf("files", "ext"), sandbox.roots().map { it.id })
        assertEquals("保留", sandbox.readText("ext/keep.txt", 10))
        val resolved = sandbox.resolve("ext/keep.txt")
        assertTrue(resolved.root is SandboxRoot.Saf)
        assertNull(resolved.absolutePath)
    }

    @Test
    fun 空根表与重复id被拒绝() {
        val dir = tmp.newFolder("files")
        val sandbox = LocalSandboxFs(listOf(SandboxRoot.File("files", dir.toPath())))
        try {
            sandbox.replaceRoots(emptyList())
            fail("空根表应当拒绝")
        } catch (_: IllegalArgumentException) {
        }
        try {
            sandbox.replaceRoots(
                listOf(
                    SandboxRoot.File("files", dir.toPath()),
                    SandboxRoot.File("files", dir.toPath()),
                ),
            )
            fail("重复 id 应当拒绝")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(1, sandbox.roots().size)
    }

    @Test
    fun 根目录不存在时拒绝替换且保留旧根() = runBlocking {
        val dir = tmp.newFolder("files")
        val sandbox = LocalSandboxFs(listOf(SandboxRoot.File("files", dir.toPath())))
        try {
            sandbox.replaceRoots(listOf(SandboxRoot.File("files", File(dir, "missing").toPath())))
            fail("不存在的根目录应当拒绝")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(dir.toPath().toRealPath(), sandbox.roots().single().let { (it as SandboxRoot.File).directory })
    }
}
