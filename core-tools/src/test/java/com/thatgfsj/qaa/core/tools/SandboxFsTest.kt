package com.thatgfsj.qaa.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** 沙盒路径逃逸与多授权根用例（设计附录 A.4 T3 + 基础行为）。 */
class SandboxFsTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    @Test
    fun resolveSimpleRelativeNormalizes() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        val resolved = sandbox.resolve("a/./b.txt")
        assertEquals("a/b.txt", resolved.relativePath)
        assertTrue(resolved.absolutePath!!.startsWith((resolved.root as SandboxRoot.File).directory))
    }

    @Test
    fun resolveCollapsesTrailingSlashAndEmptySegments() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        assertEquals("dir", sandbox.resolve("dir//").relativePath)
        assertEquals("dir", sandbox.resolve("dir/").relativePath)
    }

    @Test
    fun resolveRootItselfForDot() = runBlocking {
        val (sandbox, root) = tempSandbox(tmp)
        val resolved = sandbox.resolve(".")
        assertEquals("", resolved.relativePath)
        assertEquals(root.toPath().toRealPath(), resolved.absolutePath!!.toRealPath())
    }

    @Test
    fun rejectDotDotSegment() {
        val (sandbox, _) = tempSandbox(tmp)
        try {
            runBlocking { sandbox.resolve("a/../b") }
            fail("应当抛出 SandboxPathException")
        } catch (e: SandboxPathException) {
            assertTrue(e.message!!.contains(".."))
        }
    }

    @Test
    fun rejectAbsolutePath() {
        val (sandbox, _) = tempSandbox(tmp)
        try {
            runBlocking { sandbox.resolve("/etc/passwd") }
            fail("应当抛出 SandboxPathException")
        } catch (_: SandboxPathException) {
        }
    }

    @Test
    fun rejectBackslashAndDriveLetter() {
        val (sandbox, _) = tempSandbox(tmp)
        try {
            runBlocking { sandbox.resolve("a\\b") }
            fail("反斜杠路径应当被拒绝")
        } catch (_: SandboxPathException) {
        }
        try {
            runBlocking { sandbox.resolve("C:/Windows/win.ini") }
            fail("盘符路径应当被拒绝")
        } catch (_: SandboxPathException) {
        }
    }

    @Test
    fun rejectSymlinkEscapeFileLevel() {
        val (sandbox, root) = tempSandbox(tmp)
        val outside = tmp.newFolder("outside")
        val secret = File(outside, "secret.txt").apply { writeText("top secret") }
        Files.createSymbolicLink(File(root, "leak").toPath(), secret.toPath())
        try {
            runBlocking { sandbox.resolve("leak") }
            fail("符号链接逃逸应当被拒绝")
        } catch (e: SandboxPathException) {
            assertTrue(e.message!!.contains("逃逸"))
        }
    }

    @Test
    fun rejectSymlinkEscapeDirectoryLevel() {
        val (sandbox, root) = tempSandbox(tmp)
        val outside = tmp.newFolder("outside")
        val secret = File(outside, "secret.txt").apply { writeText("top secret") }
        Files.createSymbolicLink(File(root, "dirlink").toPath(), outside.toPath())
        try {
            runBlocking { sandbox.resolve("dirlink/secret.txt") }
            fail("目录级符号链接逃逸应当被拒绝")
        } catch (_: SandboxPathException) {
        }
    }

    @Test
    fun allowSymlinkInsideRoot() = runBlocking {
        val (sandbox, root) = tempSandbox(tmp)
        val target = File(root, "real.txt").apply { writeText("hi") }
        Files.createSymbolicLink(File(root, "alias.txt").toPath(), target.toPath())
        assertTrue(sandbox.exists("alias.txt"))
        assertEquals("hi", sandbox.readText("alias.txt", 100))
    }

    @Test
    fun resolveNonExistentDeepPathIsAllowed() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        val resolved = sandbox.resolve("newdir/sub/newfile.txt")
        assertFalse(sandbox.exists("newdir/sub/newfile.txt"))
        assertTrue(resolved.relativePath.startsWith("newdir"))
    }

    @Test
    fun multiRootRouting() = runBlocking {
        val main = tmp.newFolder("main")
        val ext = tmp.newFolder("ext")
        val sandbox = LocalSandboxFs(
            listOf(SandboxRoot.File("main", main.toPath()), SandboxRoot.File("ext", ext.toPath()))
        )
        assertEquals(main.toPath().toRealPath(), sandbox.resolve("main/f.txt").absolutePath!!.parent.toRealPath())
        assertEquals(ext.toPath().toRealPath(), sandbox.resolve("ext/g.txt").absolutePath!!.parent.toRealPath())
        assertEquals(main.toPath().toRealPath(), sandbox.resolve("f.txt").absolutePath!!.parent.toRealPath())
        assertEquals(ext.toPath().toRealPath(), sandbox.resolve("ext").absolutePath!!.toRealPath())
    }

    @Test
    fun addRootMountsNewRootAndReplacesSameId() = runBlocking {
        val main = tmp.newFolder("main")
        val first = tmp.newFolder("ext-first")
        val second = tmp.newFolder("ext-second")
        val sandbox = LocalSandboxFs(listOf(SandboxRoot.File("main", main.toPath())))

        assertNull(sandbox.addRoot(SandboxRoot.File("ext", first.toPath())))
        sandbox.writeText("ext/a.txt", "外部")
        assertEquals("外部", sandbox.readText("ext/a.txt", 10))
        assertEquals(
            first.toPath().toRealPath(),
            sandbox.resolve("ext/a.txt").absolutePath!!.parent.toRealPath(),
        )

        val replaced = sandbox.addRoot(SandboxRoot.File("ext", second.toPath()))
        assertEquals(first.toPath().toRealPath(), (replaced as SandboxRoot.File).directory.toRealPath())
        assertFalse(sandbox.exists("ext/a.txt"))
        sandbox.writeText("ext/b.txt", "新根")
        assertEquals(
            second.toPath().toRealPath(),
            sandbox.resolve("ext/b.txt").absolutePath!!.parent.toRealPath(),
        )
        assertEquals(
            main.toPath().toRealPath(),
            sandbox.resolve("a.txt").absolutePath!!.parent.toRealPath(),
        )
    }

    @Test
    fun addRootKeepsEscapeGuardsThroughMountedRoot() {
        val main = tmp.newFolder("main")
        val ext = tmp.newFolder("ext")
        val sandbox = LocalSandboxFs(listOf(SandboxRoot.File("main", main.toPath())))
        sandbox.addRoot(SandboxRoot.File("ext", ext.toPath()))
        try {
            runBlocking { sandbox.resolve("ext/../escape.txt") }
            fail("授权根内的 '..' 应当被拒绝")
        } catch (_: SandboxPathException) {
        }
        val outside = tmp.newFolder("outside")
        val secret = File(outside, "secret.txt").apply { writeText("secret") }
        Files.createSymbolicLink(File(ext, "leak").toPath(), secret.toPath())
        try {
            runBlocking { sandbox.resolve("ext/leak") }
            fail("挂载根内的符号链接逃逸应当被拒绝")
        } catch (_: SandboxPathException) {
        }
    }

    @Test
    fun addRootRejectsDefaultRootReplacementAndMissingDirectory() {
        val main = tmp.newFolder("main")
        val other = tmp.newFolder("other")
        val sandbox = LocalSandboxFs(listOf(SandboxRoot.File("main", main.toPath())))
        try {
            sandbox.addRoot(SandboxRoot.File("main", other.toPath()))
            fail("默认授权根不可替换")
        } catch (_: IllegalArgumentException) {
        }
        try {
            sandbox.addRoot(SandboxRoot.File("ext", File(main, "missing").toPath()))
            fail("不存在的授权根应当被拒绝")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun writeReadRoundTripThroughInterface() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        sandbox.writeText("nested/dir/a.txt", "你好，QAA")
        assertTrue(sandbox.exists("nested/dir/a.txt"))
        assertEquals("你好，QAA", sandbox.readText("nested/dir/a.txt", 100))
        assertEquals(6L, sandbox.textLength("nested/dir/a.txt"))
    }

    @Test
    fun listDirectorySortedByName() = runBlocking {
        val (sandbox, root) = tempSandbox(tmp)
        File(root, "b.txt").writeText("12345")
        File(root, "a.txt").writeText("12")
        File(root, "zdir").mkdirs()
        val entries = sandbox.listDirectory(".")
        assertEquals(listOf("a.txt", "b.txt", "zdir"), entries.map { it.name })
        assertFalse(entries[0].isDirectory)
        assertEquals(2L, entries[0].sizeBytes)
        assertTrue(entries[2].isDirectory)
        assertEquals(-1L, entries[2].sizeBytes)
    }

    @Test
    fun duplicateRootIdsRejected() {
        val a = tmp.newFolder("a")
        try {
            LocalSandboxFs(listOf(SandboxRoot.File("same", a.toPath()), SandboxRoot.File("same", a.toPath())))
            fail("重复根 id 应当被拒绝")
        } catch (_: IllegalArgumentException) {
        }
    }
}
