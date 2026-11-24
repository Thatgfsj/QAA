package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.inference.ModelCatalog
import com.thatgfsj.qaa.core.inference.SafDocument
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 文件选择器导入协调器单测：扩展名校验、落盘、清单序号与中文回显。 */
class ModelImportCoordinatorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun coordinator(models: File): ModelImportCoordinator = ModelImportCoordinator(
        modelsDir = models,
        catalog = ModelCatalog(models, ioDispatcher = Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun 导入成功_回显名称体积路径与清单序号() = runBlocking {
        val models = folder.newFolder("models")
        val text = coordinator(models).import(FakeDocument("Demo-Q4_0.gguf", ByteArray(2048)))
        val file = File(models, "Demo-Q4_0.gguf")
        assertTrue(text, file.isFile)
        assertTrue(text, text.contains("模型导入完成：Demo-Q4_0.gguf（Q4_0）"))
        assertTrue(text, text.contains("体积 2.0 KB（2048 字节）"))
        assertTrue(text, text.contains("路径 " + file.absolutePath))
        assertTrue(text, text.contains("清单序号 1"))
    }

    @Test
    fun 非gguf文件_拒绝且不落盘() = runBlocking {
        val models = folder.newFolder("models")
        val text = coordinator(models).import(FakeDocument("notes.txt", ByteArray(8)))
        assertTrue(text, text.contains("不是 .gguf 模型"))
        assertFalse(File(models, "notes.txt").exists())
    }

    @Test
    fun 读取失败_回显中文错误并且不留下临时文件() = runBlocking {
        val models = folder.newFolder("models")
        val text = coordinator(models).import(FakeDocument("Broken.gguf", ByteArray(8), stream = null))
        assertTrue(text, text.contains("模型导入失败"))
        assertFalse(File(models, "Broken.gguf").exists())
        assertFalse(File(models, "Broken.gguf.tmp").exists())
    }

    @Test
    fun 体积格式化_边界与单位() {
        assertEquals("512 字节", ModelImportCoordinator.formatBytes(512))
        assertEquals("1.5 KB（1536 字节）", ModelImportCoordinator.formatBytes(1536))
        assertEquals("1.0 MB（1048576 字节）", ModelImportCoordinator.formatBytes(1024L * 1024))
    }
}

private class FakeDocument(
    private val name: String?,
    private val content: ByteArray,
    private val declaredSize: Long? = content.size.toLong(),
    private val stream: InputStream? = ByteArrayInputStream(content),
) : SafDocument {
    override fun displayName(): String? = name
    override fun sizeBytes(): Long? = declaredSize
    override fun openStream(): InputStream? = stream
}
