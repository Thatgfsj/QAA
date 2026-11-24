package com.thatgfsj.qaa.core.inference

import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ModelPaths
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 模型删除链路 JVM 单测（Issue #111 验收）：
 * 三处同步（SDK 缓存 / 落盘文件 / 持久清单）、受保护条目拒绝、失败不改清单、重启不复活。
 *
 * 网关用可记录替身（[RecordingGateway]），既不加载 SDK，也能断言 remove 调用与状态码分支。
 */
class ModelDeleterTest {

    @Test
    fun 目录导入模型_删除文件并立即刷新清单与序号(@TempDir dir: File) = runTest {
        val target = writeGguf(dir, "Alpha-Q4_K_M.gguf", 20)
        writeGguf(dir, "Beta-Q8_0.gguf", 30)
        val catalog = ModelCatalog(dir, ioDispatcher = Dispatchers.Unconfined)
        val deleter = ModelDeleter(dir, catalog, ioDispatcher = Dispatchers.Unconfined)
        assertEquals(listOf(ModelCatalog.BUILT_IN_NAME, "Alpha", "Beta"), catalog.list().map { it.name })

        val result = deleter.deleteByName("alpha") as ModelDeleteResult.Deleted

        assertEquals("Alpha", result.name)
        assertEquals(20L, result.freedBytes)
        assertFalse(result.sdkRemoved)
        assertEquals(2, result.remaining)
        assertFalse(target.exists())
        // 删除后序号立即重排：Beta 从 2 号补到 1 号，内置仍在 0 号。
        assertEquals(listOf(ModelCatalog.BUILT_IN_NAME, "Beta"), catalog.list().map { it.name })
        assertEquals(listOf(0, 1), catalog.list().map { it.ordinal })
        // 新实例（模拟进程重启）读同一目录，被删条目不会复活。
        val reopened = ModelCatalog(dir, ioDispatcher = Dispatchers.Unconfined)
        assertEquals(listOf(ModelCatalog.BUILT_IN_NAME, "Beta"), reopened.list().map { it.name })
    }

    @Test
    fun 内置冒烟模型_拒绝删除且不改动清单(@TempDir dir: File) = runTest {
        val catalog = ModelCatalog(dir, ioDispatcher = Dispatchers.Unconfined)
        val deleter = ModelDeleter(dir, catalog, ioDispatcher = Dispatchers.Unconfined)

        val refused = deleter.delete(0)

        assertTrue(refused is ModelDeleteResult.Refused)
        assertEquals(ModelDeleter.BUILT_IN_REFUSED, (refused as ModelDeleteResult.Refused).reason)
        assertEquals(listOf(ModelCatalog.BUILT_IN_NAME), catalog.list().map { it.name })
    }

    @Test
    fun 未找到_序号与名称都返回NotFound(@TempDir dir: File) = runTest {
        val catalog = ModelCatalog(dir, ioDispatcher = Dispatchers.Unconfined)
        val deleter = ModelDeleter(dir, catalog, ioDispatcher = Dispatchers.Unconfined)

        assertEquals("99", (deleter.delete(99) as ModelDeleteResult.NotFound).token)
        assertEquals("ghost", (deleter.deleteByName("ghost") as ModelDeleteResult.NotFound).token)
    }

    @Test
    fun 同目录多模型_只删目标文件并保留其余(@TempDir dir: File) = runTest {
        val target = writeGguf(dir, "Alpha-Q4_K_M.gguf", 20)
        val keeper = writeGguf(dir, "Beta-Q8_0.gguf", 30)
        val catalog = ModelCatalog(dir, ioDispatcher = Dispatchers.Unconfined)
        val deleter = ModelDeleter(dir, catalog, ioDispatcher = Dispatchers.Unconfined)
        val entry = catalog.list().single { it.name == "Alpha" }

        val result = deleter.delete(entry.ordinal) as ModelDeleteResult.Deleted

        assertEquals(20L, result.freedBytes)
        assertFalse(target.exists())
        assertTrue(keeper.exists())
        assertEquals(listOf(ModelCatalog.BUILT_IN_NAME, "Beta"), catalog.list().map { it.name })
    }

    @Test
    fun 同名不同量化档_只删目标档并保留另一档(@TempDir dir: File) = runTest {
        val q4 = writeGguf(dir, "Qwen2.5-1.5B-Q4_K_M.gguf", 20)
        val q8 = writeGguf(dir, "Qwen2.5-1.5B-Q8_0.gguf", 40)
        val catalog = ModelCatalog(dir, ioDispatcher = Dispatchers.Unconfined)
        val deleter = ModelDeleter(dir, catalog, ioDispatcher = Dispatchers.Unconfined)
        val entry = catalog.list().single { it.path == q4.absolutePath }

        val result = deleter.delete(entry.ordinal) as ModelDeleteResult.Deleted

        assertEquals(20L, result.freedBytes)
        assertFalse(q4.exists())
        assertTrue(q8.exists())
        val left = catalog.list().single { it.name == "Qwen2.5-1.5B" }
        assertEquals(q8.absolutePath, left.path)
    }

    @Test
    fun SDK模型_调用网关删除并清理目录内全部分片(@TempDir dir: File, @TempDir sdkDir: File) = runTest {
        val modelDir = File(sdkDir, "hf/supra").apply { mkdirs() }
        val shard1 = writeGguf(modelDir, "Qwen3-4B-Q4_K_M-00001-of-00002.gguf", 10)
        val shard2 = writeGguf(modelDir, "Qwen3-4B-Q4_K_M-00002-of-00002.gguf", 10)
        // 同目录另一量化档：删 Q4_0 不得连带删 Q8_0。
        val otherQuant = writeGguf(modelDir, "Qwen3-4B-Q8_0.gguf", 7)
        val keep = File(modelDir, "README.md").apply { writeText("keep") }
        val gateway = RecordingGateway(sdkDir, shard1, modelDir)
        val catalog = ModelCatalog(dir, gateway, Dispatchers.Unconfined)
        catalog.register(RegisteredModel(SDK_NAME, "Qwen3-4B", ModelSource.PULL))
        val entry = catalog.list().single { it.path == shard1.absolutePath }
        assertEquals("Qwen3-4B", entry.name)

        val result = ModelDeleter(dir, catalog, gateway, Dispatchers.Unconfined)
            .delete(entry.ordinal) as ModelDeleteResult.Deleted

        assertTrue(result.sdkRemoved)
        assertEquals(listOf(SDK_NAME), gateway.removeCalls)
        assertFalse(shard1.exists())
        assertFalse(shard2.exists())
        // 同目录其他量化档与非 GGUF 文件保持原样。
        assertTrue(otherQuant.exists())
        assertTrue(keep.exists())
        assertEquals(listOf(ModelCatalog.BUILT_IN_NAME), catalog.list().map { it.name })
        // 持久清单同步注销：重启后（网关也不再列出该模型）清单只剩内置条目。
        val manifestText = File(dir, ModelCatalog.MANIFEST_FILE_NAME).readText()
        assertFalse(manifestText.contains(SDK_NAME))
        assertEquals(
            listOf(ModelCatalog.BUILT_IN_NAME),
            ModelCatalog(dir, gateway, Dispatchers.Unconfined).list().map { it.name },
        )
    }

    @Test
    fun SDK删除失败_不改清单也不删文件(@TempDir dir: File, @TempDir sdkDir: File) = runTest {
        val modelDir = File(sdkDir, "hf/supra").apply { mkdirs() }
        val shard = writeGguf(modelDir, "Qwen3-4B-Q4_K_M.gguf", 10)
        val gateway = RecordingGateway(sdkDir, shard, modelDir, removeCode = 3)
        val catalog = ModelCatalog(dir, gateway, Dispatchers.Unconfined)
        val entry = catalog.list().single { it.path == shard.absolutePath }

        val result = ModelDeleter(dir, catalog, gateway, Dispatchers.Unconfined).delete(entry.ordinal)

        assertTrue(result is ModelDeleteResult.Failed)
        assertTrue((result as ModelDeleteResult.Failed).reason.contains("code=3"))
        assertTrue(shard.exists())
        assertEquals(2, catalog.list().size)
    }

    @Test
    fun SDK异常_转为失败文案且不删文件(@TempDir dir: File, @TempDir sdkDir: File) = runTest {
        val modelDir = File(sdkDir, "hf/supra").apply { mkdirs() }
        val shard = writeGguf(modelDir, "Qwen3-4B-Q4_K_M.gguf", 10)
        val gateway = RecordingGateway(sdkDir, shard, modelDir, throwOnRemove = true)
        val catalog = ModelCatalog(dir, gateway, Dispatchers.Unconfined)
        val entry = catalog.list().single { it.path == shard.absolutePath }

        val result = ModelDeleter(dir, catalog, gateway, Dispatchers.Unconfined).delete(entry.ordinal)

        assertTrue(result is ModelDeleteResult.Failed)
        assertTrue((result as ModelDeleteResult.Failed).reason.startsWith("SDK 删除失败："))
        assertTrue(shard.exists())
        assertEquals(2, catalog.list().size)
    }

    /** 记录 remove 调用并按状态码/异常分派的网关替身。 */
    private class RecordingGateway(
        private val sdkDir: File,
        private val modelFile: File,
        private val modelDir: File,
        private val removeCode: Int = 0,
        private val throwOnRemove: Boolean = false,
    ) : GenieXModelGateway {

        val removeCalls = mutableListOf<String>()

        override suspend fun getPaths(modelName: String): ModelPaths? = if (modelName == SDK_NAME) {
            ModelPaths(
                model_path = modelFile.absolutePath,
                model_dir = modelDir.absolutePath,
                model_name = SDK_NAME,
                runtime_id = "llama_cpp",
                model_type = ModelType.LLM,
                mmproj_path = null,
                tokenizer_path = null,
            )
        } else {
            null
        }

        /** 只有删除成功才从 SDK 清单里消失；失败/异常的模型必须仍然列得出来（用于断言不误删）。 */
        private var removed = false

        override suspend fun list(): List<String> =
            if (removed) emptyList() else listOf(SDK_NAME)

        override fun pull(input: ModelPullInput): Flow<ModelManagerWrapper.PullEvent> =
            flowOf(ModelManagerWrapper.PullEvent.Completed)

        override suspend fun remove(modelName: String): Int {
            removeCalls += modelName
            if (throwOnRemove) error("SDK remove 不可用")
            if (removeCode == ModelDeleter.CODE_OK) removed = true
            return removeCode
        }
    }

    private companion object {
        const val SDK_NAME = "liodon-ai/Qwen3-4B-GGUF"
        const val SDK_DIR = "sdk"
    }
}

private fun writeGguf(dir: File, name: String, size: Int): File =
    File(dir, name).apply { writeBytes(ByteArray(size) { index -> (index % 251).toByte() }) }
