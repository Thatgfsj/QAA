package com.thatgfsj.qaa.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** write 工具用例（设计附录 A.4 T4 / T5 / T13 / T14）。 */
class WriteToolTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val tool = WriteTool()
    private fun ctx(sandbox: SandboxFs, limits: ToolLimits = ToolLimits()) =
        TestToolContext(sandbox = sandbox, limits = limits)

    @Test
    fun contractConstants() {
        assertEquals("write", tool.name)
        assertTrue(tool.requiresConfirmation)
    }

    @Test
    fun createsFileWithParents() = runBlocking {
        val (sandbox, root) = tempSandbox(tmp)
        val result = tool.execute(
            """{"path":"d1/d2/new.txt","content":"第一行\n第二行"}""",
            ctx(sandbox),
        )
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.output.contains("已创建"))
        val file = File(root, "d1/d2/new.txt")
        assertTrue(file.exists())
        assertEquals("第一行\n第二行", file.readText())
    }

    @Test
    fun overwritesExistingFileFully() = runBlocking {
        val (sandbox, root) = tempSandbox(tmp)
        sandbox.writeText("f.txt", "旧内容旧内容旧内容")
        val result = tool.execute("""{"path":"f.txt","content":"新"}""", ctx(sandbox))
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.output.contains("已覆盖"))
        assertEquals("新", File(root, "f.txt").readText())
    }

    @Test
    fun sizeLimitRejectsAndDoesNotTouchTarget() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        sandbox.writeText("big.txt", "old")
        val limits = ToolLimits(writeMaxBytes = 8)
        val result = tool.execute("""{"path":"big.txt","content":"12345678901234"}""", ctx(sandbox, limits))
        assertEquals(ToolResultStatus.FAILURE, result.status)
        assertEquals(ToolErrorCode.SIZE_LIMIT, result.errorCode)
        assertEquals("old", sandbox.readText("big.txt", 100))
    }

    @Test
    fun halfWriteFailureKeepsOldContentAndCleansTemp() = runBlocking {
        val root = tmp.newFolder("root")
        val sandbox = HalfWriteFailingSandbox(root.toPath())
        sandbox.writeTextViaParent("f.txt", "旧内容")
        sandbox.injectFailure = true

        val result = tool.execute("""{"path":"f.txt","content":"新内容更长一些"}""", ctx(sandbox))
        assertEquals(ToolResultStatus.FAILURE, result.status)
        assertEquals(ToolErrorCode.IO, result.errorCode)

        assertEquals("旧内容", File(root, "f.txt").readText())
        val leftovers = root.listFiles { f -> f.name.startsWith(".qaa-tmp-") }.orEmpty()
        assertEquals(0, leftovers.size)
        assertTrue(sandbox.injected)
    }

    @Test
    fun pathEscapeRejected() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        val result = tool.execute("""{"path":"/abs/path.txt","content":"x"}""", ctx(sandbox))
        assertEquals(ToolErrorCode.PATH_ESCAPE, result.errorCode)
    }

    @Test
    fun invalidArgsVariants() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        assertEquals(ToolErrorCode.ARGS_INVALID, tool.execute("{}", ctx(sandbox)).errorCode)
        assertEquals(ToolErrorCode.ARGS_INVALID, tool.execute("""{"path":"a"}""", ctx(sandbox)).errorCode)
        assertEquals(ToolErrorCode.ARGS_INVALID, tool.execute("""{"content":"x"}""", ctx(sandbox)).errorCode)
        assertEquals(ToolErrorCode.ARGS_INVALID, tool.execute("bad", ctx(sandbox)).errorCode)
    }

    /** 注入半写失败：armed 后写一半字节再抛 IO 异常。 */
    private class HalfWriteFailingSandbox(rootDir: Path) :
        LocalSandboxFs(listOf(SandboxRoot("main", rootDir))) {

        var injectFailure = false
        var injected = false

        override fun stage(tmp: Path, bytes: ByteArray) {
            if (!injectFailure) {
                super.stage(tmp, bytes)
                return
            }
            injected = true
            Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                out.write(bytes, 0, bytes.size / 2)
                out.flush()
            }
            throw IOException("注入：写一半失败")
        }

        suspend fun writeTextViaParent(path: String, content: String) {
            writeText(path, content)
        }
    }
}
