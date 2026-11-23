package com.thatgfsj.qaa.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** read 工具用例（设计附录 A.4 T1 / T2 / T13 / T14 + 目录列出）。 */
class ReadToolTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val tool = ReadTool()
    private fun ctx(sandbox: SandboxFs, limits: ToolLimits = ToolLimits()) =
        TestToolContext(sandbox = sandbox, limits = limits)

    @Test
    fun contractConstants() {
        assertEquals("read", tool.name)
        assertFalse(tool.requiresConfirmation)
    }

    @Test
    fun readsSmallFileVerbatim() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        sandbox.writeText("a.txt", "hello QAA")
        val result = tool.execute("""{"path":"a.txt"}""", ctx(sandbox))
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals("hello QAA", result.output)
    }

    @Test
    fun missingFileIsNotFound() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        val result = tool.execute("""{"path":"nope.txt"}""", ctx(sandbox))
        assertEquals(ToolResultStatus.FAILURE, result.status)
        assertEquals(ToolErrorCode.NOT_FOUND, result.errorCode)
    }

    @Test
    fun truncatesWithMarkerAndTotalLength() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        val big = "a".repeat(9_000)
        sandbox.writeText("big.txt", big)
        val result = tool.execute("""{"path":"big.txt"}""", ctx(sandbox))
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.output.startsWith("a".repeat(8_000)))
        assertTrue(result.output.contains("已截断"))
        assertTrue(result.output.contains("9000"))
        assertTrue(result.output.contains("8000"))
    }

    @Test
    fun exactLimitSizeIsNotMarkedTruncated() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        sandbox.writeText("edge.txt", "b".repeat(8_000))
        val result = tool.execute("""{"path":"edge.txt"}""", ctx(sandbox))
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals("b".repeat(8_000), result.output)
    }

    @Test
    fun binaryFileIsRejected() = runBlocking {
        val (sandbox, root) = tempSandbox(tmp)
        Files.write(File(root, "bin.dat").toPath(), byteArrayOf(1, 2, 0, 3))
        val result = tool.execute("""{"path":"bin.dat"}""", ctx(sandbox))
        assertEquals(ToolResultStatus.FAILURE, result.status)
        assertEquals(ToolErrorCode.BINARY_FILE, result.errorCode)
    }

    @Test
    fun listsDirectoryInStableFormat() = runBlocking {
        val (sandbox, root) = tempSandbox(tmp)
        File(root, "a.txt").writeText("123")
        File(root, "z.txt").writeText("1")
        File(root, "sub").mkdirs()
        val result = tool.execute("""{"path":"."}""", ctx(sandbox))
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.output.contains("目录 .（3 项）"))
        val lines = result.output.split("\n")
        assertEquals("f a.txt（3 字节）", lines[1])
        assertEquals("d sub/", lines[2])
        assertEquals("f z.txt（1 字节）", lines[3])
    }

    @Test
    fun pathEscapeRejected() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        val result = tool.execute("""{"path":"../outside.txt"}""", ctx(sandbox))
        assertEquals(ToolResultStatus.FAILURE, result.status)
        assertEquals(ToolErrorCode.PATH_ESCAPE, result.errorCode)
    }

    @Test
    fun invalidArgsVariants() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        for (args in listOf("{}", """{"path":123}""", "not-json", "", """{"path":null}""")) {
            val result = tool.execute(args, ctx(sandbox))
            assertEquals(ToolResultStatus.FAILURE, result.status)
            assertEquals(ToolErrorCode.ARGS_INVALID, result.errorCode)
        }
    }

    @Test
    fun unknownFieldsIgnored() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        sandbox.writeText("u.txt", "ok")
        val result = tool.execute("""{"path":"u.txt","extra":1,"other":"x"}""", ctx(sandbox))
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals("ok", result.output)
    }

    @Test
    fun customLimitRespected() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        sandbox.writeText("c.txt", "0123456789")
        val result = tool.execute("""{"path":"c.txt"}""", ctx(sandbox, ToolLimits(readMaxChars = 4)))
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.output.startsWith("0123"))
        assertTrue(result.output.contains("已截断"))
        assertTrue(result.output.contains("共 10 字符"))
    }
}
