package com.thatgfsj.qaa.core.inference

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * 旁加载来源抽象（`model import` 的文件选择器）。
 *
 * SAF / Content Uri 实现由 Android 装配层提供，本接口让导入逻辑在 JVM 单测中以
 * 字节数组直接驱动。
 */
interface ImportSource {

    /** 打开只读输入流；来源不存在或不可读返回 null。 */
    fun open(name: String): InputStream?

    /** 声称的字节数（SAF 的 OpenableColumns.SIZE）；未知返回 null。 */
    fun size(name: String): Long?
}

/** 导入结果：落盘文件 + 清单展示元信息。 */
data class ImportedModel(
    val file: File,
    /** 展示名（去掉扩展名与量化后缀） */
    val name: String,
    val quantization: String?,
    val sizeBytes: Long,
    val source: ModelSource = ModelSource.IMPORT,
)

/**
 * 本地模型旁加载：把 [ImportSource] 中的 GGUF 复制进模型目录（约定 filesDir/models）。
 *
 * 流程与 [AssetModelReleaser] 一致：临时文件 → 字节数校验 → 原子改名，失败或取消清理
 * 临时文件；成功后登记进 [ModelCatalog]，供 `model list` 与 `chat <序号>` 使用。
 * 大小校验：声明长度须逐字节相等（未知时以实际字节为准），空文件拒绝。
 *
 * @param modelsDir 模型目录（约定 filesDir/models）
 * @param catalog 可选清单：登记失败以 [IOException] 冒泡，模型文件已就位
 * @param ioDispatcher 复制 IO 调度器
 */
class ModelImporter(
    private val modelsDir: File,
    private val catalog: ModelCatalog? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * 把 [source] 中的 [name] 复制进模型目录并登记。
     *
     * @throws IllegalArgumentException 文件名非法（非 .gguf、含路径分隔符或 `..`）
     * @throws IOException 目录创建失败、来源不可读、复制中断、大小校验失败，或量化档位
     *   不受支持（#102：非 Q4_0 拒绝导入并清理临时文件）
     */
    suspend fun importModel(source: ImportSource, name: String): ImportedModel =
        withContext(ioDispatcher) {
            requireImportName(name)
            if (!modelsDir.exists() && !modelsDir.mkdirs()) {
                throw IOException("无法创建模型目录：" + modelsDir.absolutePath)
            }
            val target = File(modelsDir, name)
            val copied = copy(source, name, target, source.size(name))
            val imported = ImportedModel(
                file = target,
                name = displayNameOf(name),
                quantization = quantizationOf(name),
                sizeBytes = copied,
            )
            catalog?.register(RegisteredModel(name, imported.name, imported.source))
            imported
        }

    private suspend fun copy(source: ImportSource, name: String, target: File, declaredSize: Long?): Long {
        val tmp = File(modelsDir, name + TMP_SUFFIX)
        try {
            val input = source.open(name) ?: throw IOException("无法打开导入来源：" + name)
            var copied = 0L
            input.use { stream ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        // 块间让出调度：长拷贝保持可取消，取消时走下方清理。
                        yield()
                    }
                    output.flush()
                }
            }
            if (declaredSize != null && copied != declaredSize) {
                throw IOException(
                    "模型 " + name + " 导入大小校验失败：期望 " + declaredSize +
                        " 字节，实际 " + copied + " 字节",
                )
            }
            if (copied <= 0L) throw IOException("导入的模型文件为空：" + name)
            // #102 兼容门禁：先验头再落位，避免把不受支持的档位登记进清单。
            ModelCompatibilityGate.rejectionMessage(
                action = ModelCompatibilityGate.ModelAction.IMPORT,
                modelLabel = displayNameOf(name) + "（" + name + "）",
                compatibility = ModelCompatibilityGate.assessFile(tmp),
            )?.let { throw IOException(it) }
            if (target.exists() && !target.delete()) {
                throw IOException("无法覆盖已有模型文件：" + target.absolutePath)
            }
            if (!tmp.renameTo(target)) {
                throw IOException("模型改名失败：" + tmp.absolutePath + " -> " + target.absolutePath)
            }
            return target.length()
        } catch (throwable: Throwable) {
            tmp.delete()
            throw throwable
        }
    }

    private fun requireImportName(name: String) {
        require(name.isNotBlank()) { "模型文件名为空" }
        require(!name.contains('/') && !name.contains('\\') && !name.contains("..")) {
            "模型文件名不能包含路径：" + name
        }
        require(name.endsWith(GGUF_SUFFIX, ignoreCase = true)) { "仅支持导入 .gguf 模型：" + name }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val TMP_SUFFIX = ".tmp"
        const val GGUF_SUFFIX = ".gguf"
    }
}
