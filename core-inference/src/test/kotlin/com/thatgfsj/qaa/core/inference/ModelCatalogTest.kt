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
    fun 空目录_仅内置条目且序号为零(@TempDir dir: File) = runTest {
        val entry = ModelCatalog(dir, ioDispatcher = Dispatchers.Unconfined).list().single()
        assertEquals(0, entry.ordinal)
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
        assertEquals(listOf(0, 1, 2), entries.map { it.ordinal })
        assertEquals("Q4_K_M", entries[1].quantization)
        assertEquals(20L, entries[1].sizeBytes)
        assertEquals(ModelSource.IMPORT, entries[1].source)
        assertEquals(entries, catalog.list())
    }

    @Test
    fun SDK清单合并_local前缀判IMPORT其余判PULL(@TempDir dir: File, @TempDir sdkDir: File) = runTest {
        val hfDir = File(sdkDir, "hf").apply { mkdirs() }
        val localDir = File(sdkDir, "local").apply { mkdirs() }
        val pulled = writeFile(hfDir, "Supra2.5-Pro-Instruct-Q4_K_M.gguf", 1234)
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
        assertEquals("Q4_K_M", pull.quantization)
        assertEquals(1234L, pull.sizeBytes)
        assertEquals(ModelSource.IMPORT, entries.single { it.path == imported.absolutePath }.source)
    }

    @Test
    fun 内置模型影子条目_多份工作副本只保留零号且不随读取累积(
        @TempDir dir: File,
        @TempDir sdkDir: File,
    ) = runTest {
        // 复现 #98：SDK 为内置模型保留多份托管名 / 缓存副本，文件名与内置资产一致但路径互不相同。
        val works = (1..5).map { index ->
            val workDir = File(sdkDir, "work-$index").apply { mkdirs() }
            writeFile(workDir, ModelCatalog.BUILT_IN_FILE_NAME, 100 + index)
        }
        val names = works.indices.map { "local/supra2.5-pro-instruct-q4_0-$it" }
        val gateway = FakeInventoryGateway(
            installed = names,
            paths = names.zip(works).toMap().mapValues { (_, file) -> modelPaths(file) },
        )
        val catalog = ModelCatalog(dir, gateway, Dispatchers.Unconfined)
        val entries = catalog.list()
        assertEquals(1, entries.size)
        assertEquals(ModelCatalog.BUILT_IN_NAME, entries.single().name)
        assertEquals(0, entries.single().ordinal)
        // 同一实例重复读取、以及「冷启动」新建实例都得到同一份清单：影子条目不累积、不复活。
        assertEquals(entries, catalog.list())
        assertEquals(entries, ModelCatalog(dir, gateway, Dispatchers.Unconfined).list())
    }

    @Test
    fun 同一模型多路径_按身份去重只出现一次(@TempDir dir: File, @TempDir sdkDir: File) = runTest {
        val first = writeFile(File(sdkDir, "a").apply { mkdirs() }, "Qwen3-4B-Q4_K_M.gguf", 10)
        val second = writeFile(File(sdkDir, "b").apply { mkdirs() }, "Qwen3-4B-Q4_K_M.gguf", 20)
        val gateway = FakeInventoryGateway(
            installed = listOf("local/qwen3-4b", "local/qwen3-4b-copy"),
            paths = mapOf(
                "local/qwen3-4b" to modelPaths(first),
                "local/qwen3-4b-copy" to modelPaths(second),
            ),
        )
        val entries = ModelCatalog(dir, gateway, Dispatchers.Unconfined).list()
        assertEquals(listOf(ModelCatalog.BUILT_IN_NAME, "Qwen3-4B"), entries.map { it.name })
        assertEquals(listOf(0, 1), entries.map { it.ordinal })
        assertEquals("Q4_K_M", entries[1].quantization)
        assertEquals(ModelSource.IMPORT, entries[1].source)
    }

    @Test
    fun 路径缺失_仍按身份去重(@TempDir dir: File) = runTest {
        // SDK 能报出托管名、但取不到可用文件路径（model_path 为空）：旧实现只按路径去重会漏；
        // 身份键必须兜底，同一模型的两个托管名不得变成两条清单条目，也不能混进内置模型身份。
        val noPath = ModelPaths(
            model_path = "",
            model_dir = "",
            model_name = "Qwen3-4B-Q4_K_M",
            runtime_id = "llama_cpp",
            model_type = ModelType.LLM,
            mmproj_path = null,
            tokenizer_path = null,
        )
        val gateway = FakeInventoryGateway(
            installed = listOf("Qwen3-4B-Q4_K_M", "Qwen3-4B-Q4_K_M"),
            paths = mapOf("Qwen3-4B-Q4_K_M" to noPath),
        )
        val entries = ModelCatalog(dir, gateway, Dispatchers.Unconfined).list()
        assertEquals(listOf(ModelCatalog.BUILT_IN_NAME, "Qwen3-4B"), entries.map { it.name })
        assertEquals(listOf(0, 1), entries.map { it.ordinal })
        assertNull(entries[1].path)
    }

    @Test
    fun 删除模型_清单收缩且序号连续不复活(@TempDir dir: File) = runTest {
        // #111 兼容：去重与序号都按当前清单推导，删除文件后清单收缩、序号连续，旧条目不再出现。
        val alpha = writeFile(dir, "Alpha-Q4_0.gguf", 3)
        writeFile(dir, "Zeta-Q8_0.gguf", 5)
        val catalog = ModelCatalog(dir, ioDispatcher = Dispatchers.Unconfined)
        assertEquals(listOf(ModelCatalog.BUILT_IN_NAME, "Alpha", "Zeta"), catalog.list().map { it.name })
        assertTrue(alpha.delete())
        val after = catalog.list()
        assertEquals(listOf(ModelCatalog.BUILT_IN_NAME, "Zeta"), after.map { it.name })
        assertEquals(listOf(0, 1), after.map { it.ordinal })
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
        assertEquals(ModelCatalog.BUILT_IN_NAME, catalog.resolve(0)?.name)
        assertEquals("Alpha", catalog.resolve(1)?.name)
        assertNull(catalog.resolve(2))
        assertNull(catalog.resolve(-1))
        assertNull(catalog.resolve(9))
        assertEquals(ModelCatalog.BUILT_IN_NAME, catalog.resolveByName("0")?.name)
        assertEquals("Alpha", catalog.resolveByName("alpha")?.name)
        assertEquals("Alpha", catalog.resolveByName("Alpha-Q4_0.gguf")?.name)
        assertEquals("Alpha", catalog.resolveByName("1")?.name)
        assertNull(catalog.resolveByName("missing"))
    }

    @Test
    fun 不随包内置_同名残留副本按普通模型列出(@TempDir dir: File) = runTest {
        // #124：release 不再内置模型后，旧版本释放到私有目录的副本必须作为普通条目出现（可删），
        // 不能被内置身份去重吞掉，也不应再出现 0 号内置占位。
        val leftover = writeFile(dir, ModelCatalog.BUILT_IN_FILE_NAME, 64)
        val catalog = ModelCatalog(
            dir,
            ioDispatcher = Dispatchers.Unconfined,
            builtinNames = emptySet(),
        )
        val entries = catalog.list()
        assertEquals(1, entries.size)
        assertEquals(ModelCatalog.BUILT_IN_NAME, entries[0].name)
        assertEquals(ModelCatalog.BUILT_IN_QUANTIZATION, entries[0].quantization)
        assertEquals(ModelSource.IMPORT, entries[0].source)
        assertEquals(0, entries[0].ordinal)
        assertEquals(leftover.absolutePath, entries[0].path)
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
