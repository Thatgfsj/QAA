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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** ModelCatalog 单测：内置条目、目录扫描、SDK 清单合并与序号稳定。 */
class ModelCatalogTest {

    @Test
    fun 空目录_仅内置条目且序号为一(@TempDir dir: File) = runTest {
        val entry = ModelCatalog(dir, ioDispatcher = Dispatchers.Unconfined).list().single()
        assertEquals(1, entry.ordinal)
        assertEquals(ModelCatalog.BUILT_IN_NAME, entry.name)
        assertEquals(ModelCatalog.BUILT_IN_QUANTIZATION, entry.quantization)
        assertEquals(ModelCatalog.BUILT_IN_SIZE_BYTES, entry.sizeBytes)
        assertEquals(ModelSource.BUILT_IN, entry.source)
        assertNull(entry.path)
    }

    @Test
    fun 目录扫描_排序稳定且忽略非GGUF(@TempDir dir: File) = runTest {
        writeFile(dir, "Zeta-Q8_0.gguf", 10)
        writeFile(dir, "Alpha-Q4_K_M.gguf", 20)
        writeFile(dir, "notes.txt", 5)
        val catalog = ModelCatalog(dir, ioDispatcher = Dispatchers.Unconfined)
        val entries = catalog.list()
        assertEquals(listOf(ModelCatalog.BUILT_IN_NAME, "Alpha", "Zeta"), entries.map { it.name })
        assertEquals(listOf(1, 2, 3), entries.map { it.ordinal })
        assertEquals("Q4_K_M", entries[1].quantization)
        assertEquals(20L, entries[1].sizeBytes)
        assertEquals(ModelSource.IMPORT, entries[1].source)
        assertEquals(entries, catalog.list())
    }

    @Test
    fun SDK清单合并_local前缀判IMPORT其余判PULL(@TempDir dir: File, @TempDir sdkDir: File) = runTest {
        val hfDir = File(sdkDir, "hf").apply { mkdirs() }
        val localDir = File(sdkDir, "local").apply { mkdirs() }
        val pulled = writeFile(hfDir, "Supra2.5-Pro-Instruct-Q4_0.gguf", 1234)
        val imported = writeFile(localDir, "Imported-Q8_0.gguf", 8)
        val gateway = FakeInventoryGateway(
            installed = listOf("liodon-ai/Supra2.5-Pro-Instruct-GGUF", "local/imported"),
            paths = mapOf(
                "liodon-ai/Supra2.5-Pro-Instruct-GGUF" to modelPaths(pulled),
                "local/imported" to modelPaths(imported),
            ),
        )
        val entries = ModelCatalog(dir, gateway, Dispatchers.Unconfined).list()
        val pull = entries.single { it.path == pulled.absolutePath }
        assertEquals(ModelSource.PULL, pull.source)
        assertEquals("Supra2.5-Pro-Instruct", pull.name)
        assertEquals("Q4_0", pull.quantization)
        assertEquals(1234L, pull.sizeBytes)
        assertEquals(ModelSource.IMPORT, entries.single { it.path == imported.absolutePath }.source)
    }

    @Test
    fun 清单登记_覆盖展示名与来源且幽灵条目不列表(@TempDir dir: File, @TempDir sdkDir: File) = runTest {
        val file = writeFile(sdkDir, "x-Q4_0.gguf", 9)
        val gateway = FakeInventoryGateway(listOf("liodon-ai/x"), mapOf("liodon-ai/x" to modelPaths(file)))
        val catalog = ModelCatalog(dir, gateway, Dispatchers.Unconfined)
        catalog.register(RegisteredModel("liodon-ai/x", "自定义模型", ModelSource.IMPORT))
        catalog.register(RegisteredModel("ghost/model", "Ghost", ModelSource.PULL))
        val entry = catalog.list().single { it.path == file.absolutePath }
        assertEquals("自定义模型", entry.name)
        assertEquals(ModelSource.IMPORT, entry.source)
        assertEquals("Q4_0", entry.quantization)
        assertTrue(catalog.list().none { it.name == "Ghost" })
    }

    @Test
    fun 序号与名称解析_大小写不敏感(@TempDir dir: File) = runTest {
        writeFile(dir, "Alpha-Q4_0.gguf", 3)
        val catalog = ModelCatalog(dir, ioDispatcher = Dispatchers.Unconfined)
        assertEquals(ModelCatalog.BUILT_IN_NAME, catalog.resolve(1)?.name)
        assertEquals("Alpha", catalog.resolve(2)?.name)
        assertNull(catalog.resolve(0))
        assertNull(catalog.resolve(9))
        assertEquals("Alpha", catalog.resolveByName("alpha")?.name)
        assertEquals("Alpha", catalog.resolveByName("Alpha-Q4_0.gguf")?.name)
        assertEquals("Alpha", catalog.resolveByName("2")?.name)
        assertNull(catalog.resolveByName("missing"))
    }
}
private fun writeFile(dir: File, name: String, size: Int): File =
    File(dir, name).apply { writeBytes(ByteArray(size) { index -> (index % 251).toByte() }) }

private fun modelPaths(file: File): ModelPaths = ModelPaths(
    model_path = file.absolutePath,
    model_dir = file.absolutePath.substringBeforeLast('/'),
    model_name = file.name,
    runtime_id = "llama_cpp",
    model_type = ModelType.LLM,
    mmproj_path = null,
    tokenizer_path = null,
)

private class FakeInventoryGateway(
    private val installed: List<String>,
    private val paths: Map<String, ModelPaths>,
) : GenieXModelGateway {
    override suspend fun getPaths(modelName: String): ModelPaths? = paths[modelName]
    override suspend fun list(): List<String> = installed
    override fun pull(input: ModelPullInput): Flow<ModelManagerWrapper.PullEvent> =
        flowOf(ModelManagerWrapper.PullEvent.Completed)
}
