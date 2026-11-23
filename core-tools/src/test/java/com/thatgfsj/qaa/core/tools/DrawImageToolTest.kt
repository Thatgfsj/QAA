package com.thatgfsj.qaa.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** draw_image 占位用例（设计附录 A.4 T12 / T13 / T14）。 */
class DrawImageToolTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val tool = DrawImageTool()
    private fun ctx(sandbox: SandboxFs) = TestToolContext(sandbox = sandbox)

    @Test
    fun contractConstants() {
        assertEquals("draw_image", tool.name)
        assertTrue(tool.requiresConfirmation)
    }

    @Test
    fun engineMissingFailsFast() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        val result = tool.execute("""{"prompt":"一只打字的猫"}""", ctx(sandbox))
        assertEquals(ToolResultStatus.FAILURE, result.status)
        assertEquals(ToolErrorCode.ENGINE_NOT_AVAILABLE, result.errorCode)
    }

    @Test
    fun outputPathOptionalButMustBeNonEmptyString() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        assertEquals(
            ToolErrorCode.ENGINE_NOT_AVAILABLE,
            tool.execute("""{"prompt":"p","outputPath":"images/cat.png"}""", ctx(sandbox)).errorCode,
        )
        assertEquals(
            ToolErrorCode.ARGS_INVALID,
            tool.execute("""{"prompt":"p","outputPath":""}""", ctx(sandbox)).errorCode,
        )
        assertEquals(
            ToolErrorCode.ARGS_INVALID,
            tool.execute("""{"prompt":"p","outputPath":42}""", ctx(sandbox)).errorCode,
        )
    }

    @Test
    fun invalidArgsVariants() = runBlocking {
        val (sandbox, _) = tempSandbox(tmp)
        assertEquals(ToolErrorCode.ARGS_INVALID, tool.execute("{}", ctx(sandbox)).errorCode)
        assertEquals(ToolErrorCode.ARGS_INVALID, tool.execute("""{"prompt":1}""", ctx(sandbox)).errorCode)
        assertEquals(ToolErrorCode.ARGS_INVALID, tool.execute("not-json", ctx(sandbox)).errorCode)
    }
}
