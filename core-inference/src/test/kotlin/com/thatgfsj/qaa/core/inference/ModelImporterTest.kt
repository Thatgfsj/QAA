package com.thatgfsj.qaa.core.inference

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** ModelImporter 单测：原子写、大小校验、失败清理与清单登记。 */
class ModelImporterTest {

    @Test
    fun 导入成功_内容一致且覆盖同名旧文件(@TempDir dir: File) = runTest {
        File(dir, "Smoke-Q4_0.gguf").writeBytes(bytes(8))
        val content = bytes(4096)
        val imported = ModelImporter(dir, null, Dispatchers.Unconfined)
            .importModel(FakeImportSource(mapOf("Smoke-Q4_0.gguf" to content)), "Smoke-Q4_0.gguf")
        assertArrayEquals(content, imported.file.readBytes())
        assertEquals("Smoke", imported.name)
        assertEquals("Q4_0", imported.quantization)
        assertEquals(content.size.toLong(), imported.sizeBytes)
        assertEquals(ModelSource.IMPORT, imported.source)
        assertFalse(File(dir, "Smoke-Q4_0.gguf.tmp").exists())
    }

    @Test
    fun 大小校验失败或来源缺失_清理临时文件且不落目标(@TempDir dir: File) = runTest {
        val importer = ModelImporter(dir, null, Dispatchers.Unconfined)
        val mismatch = FakeImportSource(mapOf("bad.gguf" to bytes(100)), declaredSize = 200L)
        val sizeError = runCatching { importer.importModel(mismatch, "bad.gguf") }.exceptionOrNull()
        assertTrue(sizeError is IOException, "应为 IOException，实际：" + sizeError)
        assertFalse(File(dir, "bad.gguf").exists())
        assertFalse(File(dir, "bad.gguf.tmp").exists())
        val missing = runCatching { importer.importModel(FakeImportSource(emptyMap()), "none.gguf") }
            .exceptionOrNull()
        assertTrue(missing is IOException)
        assertFalse(File(dir, "none.gguf.tmp").exists())
    }

    @Test
    fun 声明大小未知_按实际复制字节(@TempDir dir: File) = runTest {
        val source = FakeImportSource(mapOf("Unknown.gguf" to bytes(33)), unknownSize = true)
        val imported = ModelImporter(dir, null, Dispatchers.Unconfined)
            .importModel(source, "Unknown.gguf")
        assertEquals(33L, imported.sizeBytes)
    }

    @Test
    fun 非法文件名_拒绝路径穿越与非GGUF(@TempDir dir: File) = runTest {
        val importer = ModelImporter(dir, null, Dispatchers.Unconfined)
        listOf("../evil.gguf", "model.bin", " ").forEach { name ->
            val error = runCatching { importer.importModel(FakeImportSource(emptyMap()), name) }
                .exceptionOrNull()
            assertTrue(error is IllegalArgumentException, "应拒绝文件名：" + name)
        }
    }

    @Test
    fun 导入登记清单_可被catalog检索(@TempDir dir: File) = runTest {
        val catalog = ModelCatalog(dir, ioDispatcher = Dispatchers.Unconfined)
        ModelImporter(dir, catalog, Dispatchers.Unconfined)
            .importModel(FakeImportSource(mapOf("Foo-Q8_0.gguf" to bytes(64))), "Foo-Q8_0.gguf")
        val entries = catalog.list()
        assertEquals(2, entries.size)
        assertEquals(ModelCatalog.BUILT_IN_NAME, entries[0].name)
        assertEquals("Foo", entries[1].name)
        assertEquals(ModelSource.IMPORT, entries[1].source)
        assertEquals("Q8_0", entries[1].quantization)
        assertEquals(64L, entries[1].sizeBytes)
        assertEquals(File(dir, "Foo-Q8_0.gguf").absolutePath, entries[1].path)
    }

    @Test
    fun 不受支持的量化档位_拒绝导入且不落目标也不登记(@TempDir dir: File) = runTest {
        val catalog = ModelCatalog(dir, ioDispatcher = Dispatchers.Unconfined)
        val content = GgufFixture.build(
            fileType = 15L,
            tensors = listOf(
                GgufFixture.matmul("blk.0.attn_q.weight", GgufQuantization.Q4_K),
                GgufFixture.matmul("blk.0.ffn_down.weight", GgufQuantization.Q6_K),
            ),
        )
        val error = runCatching {
            ModelImporter(dir, catalog, Dispatchers.Unconfined)
                .importModel(FakeImportSource(mapOf("Qwen3-4B-Q4_K_M.gguf" to content)), "Qwen3-4B-Q4_K_M.gguf")
        }.exceptionOrNull()
        assertTrue(error is IOException, "应为 IOException，实际：" + error)
        val message = error?.message.orEmpty()
        assertTrue(message.contains("不受支持"), "文案应说明不受支持：" + message)
        assertTrue(message.contains("Q4_0"), "文案应给出改用 Q4_0 的建议：" + message)
        assertFalse(File(dir, "Qwen3-4B-Q4_K_M.gguf").exists())
        assertFalse(File(dir, "Qwen3-4B-Q4_K_M.gguf.tmp").exists())
        assertEquals(1, catalog.list().size, "被拒文件不得登记清单")
    }

    @Test
    fun 受支持的Q4_0文件头_放行导入(@TempDir dir: File) = runTest {
        val content = GgufFixture.build(
            fileType = 2L,
            tensors = listOf(GgufFixture.matmul("blk.0.attn_q.weight", GgufQuantization.Q4_0)),
        )
        val imported = ModelImporter(dir, null, Dispatchers.Unconfined)
            .importModel(FakeImportSource(mapOf("Ok-Q4_0.gguf" to content)), "Ok-Q4_0.gguf")
        assertEquals("Q4_0", imported.quantization)
        assertArrayEquals(content, imported.file.readBytes())
    }
}

private class FakeImportSource(
    private val content: Map<String, ByteArray>,
    private val declaredSize: Long? = null,
    private val unknownSize: Boolean = false,
) : ImportSource {
    override fun open(name: String): InputStream? = content[name]?.let(::ByteArrayInputStream)
    override fun size(name: String): Long? = when {
        unknownSize -> null
        declaredSize != null -> declaredSize
        else -> content[name]?.size?.toLong()
    }
}

private fun bytes(size: Int): ByteArray = ByteArray(size) { index -> (index % 251).toByte() }
