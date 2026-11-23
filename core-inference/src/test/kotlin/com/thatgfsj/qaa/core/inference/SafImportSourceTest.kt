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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** SafImportSource 单测：文档包装语义与经 ModelImporter 的复制、大小校验、清单登记。 */
class SafImportSourceTest {

    @Test
    fun 文档包装_开流与体积查询直通() {
        val source = SafImportSource(FakeSafDocument("Demo.gguf", bytes(16), size = 16L))
        assertEquals("Demo.gguf", source.suggestedName)
        assertEquals(16L, source.size("任意名称"))
        assertArrayEquals(bytes(16), source.open("任意名称")!!.readBytes())
    }

    @Test
    fun 展示名缺失_建议名为空() {
        assertNull(SafImportSource(FakeSafDocument(null, bytes(4))).suggestedName)
    }

    @Test
    fun 经导入器落盘_内容与登记一致(@TempDir dir: File) = runTest {
        val content = bytes(4096)
        val catalog = ModelCatalog(dir, ioDispatcher = Dispatchers.Unconfined)
        val imported = ModelImporter(dir, catalog, Dispatchers.Unconfined)
            .importModel(SafImportSource(FakeSafDocument("Ble-Q4_1.gguf", content, size = 4096)), "Ble-Q4_1.gguf")
        assertArrayEquals(content, imported.file.readBytes())
        val entry = catalog.list().first { it.name == "Ble" }
        assertEquals(ModelSource.IMPORT, entry.source)
        assertEquals(4096L, entry.sizeBytes)
    }

    @Test
    fun 声明体积不符_拒绝并清理临时文件(@TempDir dir: File) = runTest {
        val error = runCatching {
            ModelImporter(dir, null, Dispatchers.Unconfined)
                .importModel(SafImportSource(FakeSafDocument("Bad.gguf", bytes(100), size = 99)), "Bad.gguf")
        }.exceptionOrNull()
        assertTrue(error is IOException, "应为 IOException，实际：$error")
        assertFalse(File(dir, "Bad.gguf").exists())
        assertFalse(File(dir, "Bad.gguf.tmp").exists())
    }

    @Test
    fun 输入流不可用_导入失败(@TempDir dir: File) = runTest {
        val source = SafImportSource(FakeSafDocument("Gone.gguf", ByteArray(0), size = null, stream = null))
        val error = runCatching {
            ModelImporter(dir, null, Dispatchers.Unconfined).importModel(source, "Gone.gguf")
        }.exceptionOrNull()
        assertTrue(error is IOException)
    }
}

private class FakeSafDocument(
    private val name: String?,
    private val content: ByteArray,
    private val size: Long? = content.size.toLong(),
    private val stream: InputStream? = ByteArrayInputStream(content),
) : SafDocument {
    override fun displayName(): String? = name
    override fun sizeBytes(): Long? = size
    override fun openStream(): InputStream? = stream
}

private fun bytes(size: Int): ByteArray = ByteArray(size) { index -> (index % 251).toByte() }
