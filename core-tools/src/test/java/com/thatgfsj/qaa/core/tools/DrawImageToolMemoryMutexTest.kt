package com.thatgfsj.qaa.core.tools

import com.thatgfsj.qaa.core.inference.CoordinatorConfig
import com.thatgfsj.qaa.core.inference.CoordinatorState
import com.thatgfsj.qaa.core.inference.ModelMemoryCoordinator
import com.thatgfsj.qaa.core.inference.OutputFileCheck
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** draw_image 与内存互斥状态机的接线用例（设计 §9.5 / 附录 A.4 T12）。 */
class DrawImageToolMemoryMutexTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private class Harness(tmp: TemporaryFolder) {
        val root = tmp.newFolder()
        val sandbox = LocalSandboxFs(listOf(SandboxRoot.File("main", root.toPath())))
        val llm = FakeLlmHandle()
        val engine = FakeImageGenEngine()
        val imageHandle = ImageGenEngineMemoryHandle(engine)
        val probe = FakeMemoryProbe()
        val sleeper = RecordingSleeper()
        val coordinator = ModelMemoryCoordinator(
            llm = llm,
            imageGen = imageHandle,
            memoryProbe = probe,
            outputProbe = SandboxOutputFileProbe(sandbox),
            config = CoordinatorConfig(memorySafeWatermarkBytes = 100L),
            sleeper = sleeper,
        )
        val tool = DrawImageTool(engine, coordinator)
        val context = TestToolContext(sandbox = sandbox)

        suspend fun run(args: String): ToolResult = tool.execute(args, context)
    }

    @Test
    fun engineAndCoordinatorMissingFailsFast() = runBlocking {
        val root = tmp.newFolder("bare")
        val sandbox = LocalSandboxFs(listOf(SandboxRoot.File("main", root.toPath())))
        val result = DrawImageTool().execute(
            """{"prompt":"一只猫"}""",
            TestToolContext(sandbox = sandbox),
        )
        assertEquals(ToolResultStatus.FAILURE, result.status)
        assertEquals(ToolErrorCode.ENGINE_NOT_AVAILABLE, result.errorCode)
    }

    @Test
    fun engineWithoutCoordinatorFailsFast() = runBlocking {
        val root = tmp.newFolder("engine-only")
        val sandbox = LocalSandboxFs(listOf(SandboxRoot.File("main", root.toPath())))
        val engine = FakeImageGenEngine()
        val result = DrawImageTool(engine).execute(
            """{"prompt":"一只猫"}""",
            TestToolContext(sandbox = sandbox),
        )
        assertEquals(ToolErrorCode.ENGINE_NOT_AVAILABLE, result.errorCode)
        assertEquals(0, engine.generateCount)
    }

    @Test
    fun fullCycleSucceedsAndReportsPath() = runBlocking {
        val h = Harness(tmp)
        val result = h.run("""{"prompt":"一只猫","outputPath":"images/cat.png"}""")
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.output, result.output.contains("images/cat.png"))
        assertTrue(result.dataJson!!, result.dataJson.contains("\"path\":\"images/cat.png\""))
        assertTrue(Files.exists(h.root.toPath().resolve("images/cat.png")))
        assertEquals(1, h.engine.loadCount)
        assertEquals(1, h.engine.generateCount)
        assertEquals(1, h.engine.unloadCount)
        assertEquals(1, h.llm.unloadCount)
        assertEquals(1, h.llm.reloadCount)
        assertEquals(1, h.llm.injectCount)
        assertEquals("images/cat.png", h.llm.lastInjectedPath)
        assertEquals(CoordinatorState.LLM_LOADED, h.coordinator.state.value)
    }

    @Test
    fun defaultOutputPathLandsUnderImages() = runBlocking {
        val h = Harness(tmp)
        val result = h.run("""{"prompt":"一只猫"}""")
        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.dataJson!!, result.dataJson.contains("\"path\":\"images/draw_"))
        val images = h.root.toPath().resolve("images").toFile()
        assertTrue(images.isDirectory)
        assertTrue(images.listFiles()!!.isNotEmpty())
    }

    @Test
    fun outputPathEscapeIsRejectedBeforeCycle() = runBlocking {
        val h = Harness(tmp)
        val result = h.run("""{"prompt":"一只猫","outputPath":"../evil.png"}""")
        assertEquals(ToolResultStatus.FAILURE, result.status)
        assertEquals(ToolErrorCode.PATH_ESCAPE, result.errorCode)
        assertEquals(0, h.engine.generateCount)
        assertEquals(CoordinatorState.LLM_LOADED, h.coordinator.state.value)
    }

    @Test
    fun missingOutputFileMapsToMemoryMutexAndRecovers() = runBlocking {
        val h = Harness(tmp)
        h.engine.writeOutput = false
        val result = h.run("""{"prompt":"一只猫","outputPath":"images/missing.png"}""")
        assertEquals(ToolResultStatus.FAILURE, result.status)
        assertEquals(ToolErrorCode.MEMORY_MUTEX, result.errorCode)
        assertEquals(CoordinatorState.LLM_LOADED, h.coordinator.state.value)
        assertEquals(1, h.llm.destroyCount)
        assertEquals(1, h.llm.rebuildCount)
        assertFalse(h.imageHandle.isLoaded)
        assertEquals(listOf(50L, 100L), h.sleeper.delays)
    }

    @Test
    fun engineGenerateFailureMapsToMemoryMutex() = runBlocking {
        val h = Harness(tmp)
        h.engine.failGenerate = true
        val result = h.run("""{"prompt":"一只猫","outputPath":"images/fail.png"}""")
        assertEquals(ToolResultStatus.FAILURE, result.status)
        assertEquals(ToolErrorCode.MEMORY_MUTEX, result.errorCode)
        assertEquals(3, h.engine.generateCount)
        assertEquals(CoordinatorState.LLM_LOADED, h.coordinator.state.value)
    }

    @Test
    fun blankPromptIsArgsInvalid() = runBlocking {
        val h = Harness(tmp)
        val result = h.run("""{"prompt":"   "}""")
        assertEquals(ToolErrorCode.ARGS_INVALID, result.errorCode)
    }

    @Test
    fun sandboxOutputProbeRejectsEmptyAndEscapedPaths() = runBlocking {
        val h = Harness(tmp)
        val empty = h.root.toPath().resolve("images/empty.png")
        Files.createDirectories(empty.parent)
        Files.createFile(empty)
        val probe = SandboxOutputFileProbe(h.sandbox)
        assertTrue(probe.inspect("images/empty.png") is OutputFileCheck.Invalid)
        assertTrue(probe.inspect("../outside.png") is OutputFileCheck.Invalid)
    }

    @Test
    fun concurrentRequestsAreSerializedByCoordinator() = runBlocking {
        val h = Harness(tmp)
        h.engine.generateDelayMillis = 50L
        val first = async(Dispatchers.Default) {
            h.run("""{"prompt":"甲","outputPath":"images/a.png"}""")
        }
        val second = async(Dispatchers.Default) {
            h.run("""{"prompt":"乙","outputPath":"images/b.png"}""")
        }
        val results = listOf(first.await(), second.await())
        results.forEach { assertEquals(ToolResultStatus.SUCCESS, it.status) }
        assertEquals(2, h.engine.generateCount)
        assertEquals(1, h.engine.maxConcurrentGenerates)
        assertEquals(CoordinatorState.LLM_LOADED, h.coordinator.state.value)
    }
}
