package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.inference.ModelCatalog
import com.thatgfsj.qaa.core.inference.ModelImporter
import com.thatgfsj.qaa.core.inference.SafDocument
import com.thatgfsj.qaa.core.inference.SafImportSource
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件选择器导入的落盘与回显：校验扩展名、复制进模型目录、登记清单并生成中文结果。
 *
 * 选择器返回的文档经 [SafDocument] 描述，[import] 不直接接触 Android 类型，可在 JVM 单测中驱动。
 */
class ModelImportCoordinator(
    private val modelsDir: File,
    private val catalog: ModelCatalog,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** 导入单个文档；成功与失败都返回可直接回显的中文文本。 */
    suspend fun import(document: SafDocument): String = withContext(ioDispatcher) {
        val source = SafImportSource(document)
        val name = source.suggestedName?.trim().orEmpty()
        if (!name.endsWith(GGUF_SUFFIX, ignoreCase = true)) {
            return@withContext "模型导入失败：所选文件不是 .gguf 模型" +
                "（${name.ifEmpty { "未提供文件名" }}）。"
        }
        try {
            val imported = ModelImporter(modelsDir, catalog, ioDispatcher).importModel(source, name)
            val ordinal = runCatching {
                catalog.list().firstOrNull { it.path == imported.file.absolutePath }?.ordinal
            }.getOrNull()
            buildString {
                append("模型导入完成：")
                append(imported.file.name)
                imported.quantization?.let { append("（$it）") }
                append("，体积 ")
                append(formatBytes(imported.sizeBytes))
                append("，路径 ")
                append(imported.file.absolutePath)
                if (ordinal != null) {
                    append("，清单序号 $ordinal")
                } else {
                    append("，清单序号待刷新")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            "模型导入失败：${error.message ?: error::class.simpleName}"
        }
    }

    companion object {

        private const val GGUF_SUFFIX = ".gguf"
        private const val KIB = 1024.0

        /** 人类可读体积（保留一位小数）并附原始字节数。 */
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes 字节"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes / KIB
            var unit = 0
            while (value >= KIB && unit < units.lastIndex) {
                value /= KIB
                unit++
            }
            return String.format(Locale.ROOT, "%.1f %s（%d 字节）", value, units[unit], bytes)
        }
    }
}
