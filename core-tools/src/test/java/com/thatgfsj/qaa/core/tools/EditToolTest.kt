package com.thatgfsj.qaa.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** edit 工具用例（设计附录 A.4 T6–T11 / T13 / T14）。 */
class EditToolTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val tool = EditTool()
    private fun ctx(sandbox: SandboxFs) = TestToolContext(sandbox = sandbox)

    @Test
    fun contractConstants() {
        assertEquals("edit", tool.name)
        assertTrue(tool.requiresConfirmation)
    }

    @Test
    fun replacesUniqueMatch() = runBlocking {
        val (sandbox, root) = tempSandbox(tmp)
        sandbox.writeText("f.txt", "hello QAA world")
        val result = tool.execute(
            """{"path":"f.txt","oldText":"QAA","newText":"QAA2"}""",
            ctx(sandbox),
        )
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.output.contains("已替换 1 处"))
        assertEquals("hello QAA2 world", File(root, "f.txt").readText())
    }

    @Test
    fun multiMatchWithoutReplaceAllFailsWithCount() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        sandbox.writeText("m.txt", "aXbXc")
        val result = tool.execute(
            """{"path":"m.txt","oldText":"X","newText":"Y"}""",
            ctx(sandbox),
        )
        assertEquals(ToolResultStatus.FAILURE, result.status)
        assertEquals(ToolErrorCode.MULTI_MATCH, result.errorCode)
        assertTrue(result.output.contains("2 处"))
    }

    @Test
    fun multiMatchWithReplaceAllSucceeds() = runBlocking {
        val (sandbox, root) = tempSandbox(tmp)
        sandbox.writeText("m.txt", "aXbXc")
        val result = tool.execute(
            """{"path":"m.txt","oldText":"X","newText":"Y","replaceAll":true}""",
            ctx(sandbox),
        )
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.output.contains("已替换 2 处"))
        assertEquals("aYbYc", File(root, "m.txt").readText())
    }

    @Test
    fun noMatchIncludesNearestHint() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        sandbox.writeText("n.txt", "QAA version 3.0 is ready")
        val result = tool.execute(
            """{"path":"n.txt","oldText":"QAA version 2.0","newText":"QAA version 2.1"}""",
            ctx(sandbox),
        )
        assertEquals(ToolResultStatus.FAILURE, result.status)
        assertEquals(ToolErrorCode.NOT_FOUND, result.errorCode)
        assertTrue(result.output.contains("相似片段"))
    }

    @Test
    fun noMatchWithoutHintSuggestsRead() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        sandbox.writeText("n2.txt", "完全无关的内容")
        val result = tool.execute(
            """{"path":"n2.txt","oldText":"zzzz-nothing-here-qqqq","newText":"x"}""",
            ctx(sandbox),
        )
        assertEquals(ToolErrorCode.NOT_FOUND, result.errorCode)
        assertTrue(result.output.contains("无相似片段"))
    }

    @Test
    fun emptyNewTextDeletesFragment() = runBlocking {
        val (sandbox, root) = tempSandbox(tmp)
        sandbox.writeText("d.txt", "abcXYZdef")
        val result = tool.execute(
            """{"path":"d.txt","oldText":"XYZ","newText":""}""",
            ctx(sandbox),
        )
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals("abcdef", File(root, "d.txt").readText())
    }

    @Test
    fun emptyOldTextRejected() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        sandbox.writeText("e.txt", "content")
        val result = tool.execute(
            """{"path":"e.txt","oldText":"","newText":"x"}""",
            ctx(sandbox),
        )
        assertEquals(ToolResultStatus.FAILURE, result.status)
        assertEquals(ToolErrorCode.EMPTY_OLD_TEXT, result.errorCode)
    }

    @Test
    fun missingTargetIsNotFoundAndNotCreated() = runBlocking {
        val (sandbox, root) = tempSandbox(tmp)
        val result = tool.execute(
            """{"path":"ghost.txt","oldText":"a","newText":"b"}""",
            ctx(sandbox),
        )
        assertEquals(ToolResultStatus.FAILURE, result.status)
        assertEquals(ToolErrorCode.NOT_FOUND, result.errorCode)
        assertFalse(File(root, "ghost.txt").exists())
    }

    @Test
    fun pathEscapeRejected() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        val result = tool.execute(
            """{"path":"../x.txt","oldText":"a","newText":"b"}""",
            ctx(sandbox),
        )
        assertEquals(ToolErrorCode.PATH_ESCAPE, result.errorCode)
    }

    @Test
    fun invalidArgsVariants() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        assertEquals(
            ToolErrorCode.ARGS_INVALID,
            tool.execute("""{"path":"a.txt"}""", ctx(sandbox)).errorCode,
        )
        assertEquals(
            ToolErrorCode.ARGS_INVALID,
            tool.execute("""{"path":"a.txt","oldText":"a"}""", ctx(sandbox)).errorCode,
        )
        assertEquals(
            ToolErrorCode.ARGS_INVALID,
            tool.execute("""{"path":true,"oldText":"a","newText":"b"}""", ctx(sandbox)).errorCode,
        )
        assertEquals(
            ToolErrorCode.ARGS_INVALID,
            tool.execute("""{"path":"a.txt","oldText":"a","newText":"b","replaceAll":"yes"}""", ctx(sandbox)).errorCode,
        )
    }

    @Test
    fun unknownFieldsIgnored() = runBlocking {
        val (sandbox, root) = tempSandbox(tmp)
        sandbox.writeText("u.txt", "v1 v2")
        val result = tool.execute(
            """{"path":"u.txt","oldText":"v1","newText":"v9","extra":123}""",
            ctx(sandbox),
        )
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals("v9 v2", File(root, "u.txt").readText())
    }
}
