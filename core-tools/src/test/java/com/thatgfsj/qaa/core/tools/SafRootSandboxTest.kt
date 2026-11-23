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

/** 组合根路由：File 根与 SAF 根的读写、隔离、逃逸语义与错误码翻译。 */
class SafRootSandboxTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun sandbox(gateway: FakeSafGateway = FakeSafGateway()): Pair<LocalSandboxFs, FakeSafGateway> {
        val main = tmp.newFolder("main")
        val sandbox = LocalSandboxFs(
            listOf(
                SandboxRoot.File("main", main.toPath()),
                SandboxRoot.Saf("ext", TREE_URI, "QAA 目录", gateway),
            )
        )
        return sandbox to gateway
    }

    @Test
    fun 文件根与SAF根各自读写往返() = runBlocking {
        val (sandbox, fake) = sandbox()

        sandbox.writeText("notes.txt", "私有目录")
        sandbox.writeText("ext/docs/notes.txt", "SAF 内容")

        assertEquals("私有目录", sandbox.readText("notes.txt", 100))
        assertEquals("SAF 内容", sandbox.readText("ext/docs/notes.txt", 100))
        assertEquals("SAF 内容", fake.textOf("docs/notes.txt"))
        assertTrue(sandbox.exists("ext/docs/notes.txt"))
        assertFalse(sandbox.exists("ext/docs/missing.txt"))
        assertTrue(sandbox.isDirectory("ext/docs"))
        assertEquals("SAF 内容".length.toLong(), sandbox.textLength("ext/docs/notes.txt"))
    }

    @Test
    fun 路径首段决定根且SAF根无真实路径() = runBlocking {
        val (sandbox, _) = sandbox()

        val filePath = sandbox.resolve("a.txt")
        assertTrue(filePath.root is SandboxRoot.File)
        assertTrue(filePath.absolutePath != null)

        val safPath = sandbox.resolve("ext/a.txt")
        assertTrue(safPath.root is SandboxRoot.Saf)
        assertNull(safPath.absolutePath)
        assertEquals("a.txt", safPath.relativePath)

        val safRoot = sandbox.resolve("ext")
        assertEquals("", safRoot.relativePath)
        assertEquals("a/b.txt", sandbox.resolve("ext/./a//b.txt").relativePath)
    }

    @Test
    fun 跨根同名相对路径互不影响() = runBlocking {
        val (sandbox, _) = sandbox()

        sandbox.writeText("shared.txt", "私有")
        sandbox.writeText("ext/shared.txt", "外部")
        assertEquals("私有", sandbox.readText("shared.txt", 10))
        assertEquals("外部", sandbox.readText("ext/shared.txt", 10))

        sandbox.writeText("only-private.txt", "x")
        assertFalse(sandbox.exists("ext/only-private.txt"))
    }

    @Test
    fun SAF根仍拒绝词法逃逸() {
        val (sandbox, _) = sandbox()
        for (path in listOf("ext/../escape.txt", "/ext/a.txt", "ext\\a.txt")) {
            try {
                runBlocking { sandbox.resolve(path) }
                fail("应当拒绝路径: " + path)
            } catch (_: SandboxPathException) {
            }
        }
    }

    @Test
    fun SAF根写入为覆盖语义() = runBlocking {
        val (sandbox, fake) = sandbox()

        sandbox.writeText("ext/a.txt", "123456")
        assertEquals("123456", fake.textOf("a.txt"))
        sandbox.writeText("ext/a.txt", "xy")

        assertEquals("xy", fake.textOf("a.txt"))
        assertEquals("xy", sandbox.readText("ext/a.txt", 10))
        assertEquals(2, fake.openWriteCalls)
    }

    @Test
    fun SAF根编辑为全读改写() = runBlocking {
        val (sandbox, fake) = sandbox()
        sandbox.writeText("ext/doc.txt", "alpha beta alpha")

        assertTrue(sandbox.applyEdit("ext/doc.txt", "alpha", "gamma", false) is EditOutcome.MultiMatch)
        val applied = sandbox.applyEdit("ext/doc.txt", "alpha", "gamma", true)
        assertEquals(EditOutcome.Applied(2, "gamma beta gamma".length), applied)
        assertEquals("gamma beta gamma", fake.textOf("doc.txt"))
        assertTrue(sandbox.applyEdit("ext/doc.txt", "missing", "x", false) is EditOutcome.NotFound)
    }

    @Test
    fun SAF根目录列出全部子项并按名称排序() = runBlocking {
        val (sandbox, fake) = sandbox()
        fake.seedDirectory("dir")
        (1..30).forEach { index ->
            fake.seed("dir/f" + index.toString().padStart(2, '0') + ".txt", "x".repeat(index))
        }
        fake.seedDirectory("dir/sub")

        val entries = sandbox.listDirectory("ext/dir")
        assertEquals(31, entries.size)
        assertEquals(entries.map { it.name }.sorted(), entries.map { it.name })
        assertEquals(1L, entries.first { it.name == "f01.txt" }.sizeBytes)
        assertTrue(entries.first { it.name == "sub" }.isDirectory)
        assertEquals(-1L, entries.first { it.name == "sub" }.sizeBytes)

        val rootEntries = sandbox.listDirectory("ext")
        assertEquals(listOf("dir"), rootEntries.map { it.name })
    }

    @Test
    fun SAF根大量子项无遗漏() = runBlocking {
        val (sandbox, fake) = sandbox()
        val total = 512
        repeat(total) { index ->
            fake.seed("bulk/file-" + index.toString().padStart(3, '0') + ".txt", "x")
        }

        val entries = sandbox.listDirectory("ext/bulk")
        assertEquals(total, entries.size)
        assertEquals("file-000.txt", entries.first().name)
        assertEquals("file-511.txt", entries.last().name)
    }

    @Test
    fun SAF根二进制文件拒绝读取() {
        val (sandbox, fake) = sandbox()
        fake.seedBytes("bin.dat", byteArrayOf(1, 0, 2))
        try {
            runBlocking { sandbox.readText("ext/bin.dat", 10) }
            fail("二进制文件应当被拒绝")
        } catch (e: SandboxBinaryFileException) {
            assertTrue(e.message!!.contains("bin.dat"))
        }
    }

    @Test
    fun SAF根缺失文件映射为不存在() {
        val (sandbox, _) = sandbox()
        try {
            runBlocking { sandbox.readText("ext/missing.txt", 10) }
            fail("缺失文件应当报不存在")
        } catch (_: SandboxFileNotFoundException) {
        }
        try {
            runBlocking { sandbox.applyEdit("ext/missing.txt", "a", "b", false) }
            fail("缺失文件应当报不存在")
        } catch (_: SandboxFileNotFoundException) {
        }
        try {
            runBlocking { sandbox.listDirectory("ext/missing") }
            fail("缺失目录应当报 IO 错误")
        } catch (_: SandboxIoException) {
        }
    }

    @Test
    fun SAF根网关失败映射为稳定沙盒错误并保留原因() {
        val (sandbox, fake) = sandbox()

        fake.failOpenWrite = SafGatewayException("存储空间不足")
        try {
            runBlocking { sandbox.writeText("ext/a.txt", "x") }
            fail("写入失败应当抛出 SandboxIoException")
        } catch (e: SandboxIoException) {
            assertTrue(e.message!!.contains("存储空间不足"))
        }

        fake.failOpenWrite = null
        fake.seed("a.txt", "ok")
        fake.failOpenRead = SafGatewayException("提供者断开")
        try {
            runBlocking { sandbox.readText("ext/a.txt", 10) }
            fail("读取失败应当抛出 SandboxIoException")
        } catch (e: SandboxIoException) {
            assertTrue(e.message!!.contains("提供者断开"))
        }

        fake.failOpenRead = SafNotFoundException("已被移除")
        try {
            runBlocking { sandbox.readText("ext/a.txt", 10) }
            fail("读取缺失应当抛出 SandboxFileNotFoundException")
        } catch (_: SandboxFileNotFoundException) {
        }

        fake.failOpenRead = null
        fake.failQuery = SafGatewayException("授权失效")
        try {
            runBlocking { sandbox.exists("ext/a.txt") }
            fail("查询失败应当抛出 SandboxIoException")
        } catch (e: SandboxIoException) {
            assertTrue(e.message!!.contains("授权失效"))
        }
    }

    @Test
    fun SAF根可动态挂载并替换() = runBlocking {
        val first = FakeSafGateway().apply { seed("a.txt", "one") }
        val second = FakeSafGateway().apply { seed("a.txt", "two") }
        val main = tmp.newFolder("mount-main")
        val sandbox = LocalSandboxFs(listOf(SandboxRoot.File("main", main.toPath())))

        assertNull(sandbox.addRoot(SandboxRoot.Saf("ext", TREE_URI, "一", first)))
        assertEquals("one", sandbox.readText("ext/a.txt", 10))

        val replaced = sandbox.addRoot(SandboxRoot.Saf("ext", TREE_URI, "二", second))
        assertTrue(replaced is SandboxRoot.Saf)
        assertEquals("一", (replaced as SandboxRoot.Saf).displayName)
        assertEquals("two", sandbox.readText("ext/a.txt", 10))
    }

    private companion object {
        const val TREE_URI = "content://test/tree/qaa"
    }
}
