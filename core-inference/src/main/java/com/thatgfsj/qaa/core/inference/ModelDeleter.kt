package com.thatgfsj.qaa.core.inference

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 模型删除结果（#111）：成功 / 受保护拒绝 / 未找到 / 执行失败。
 *
 * [Failed] 表示清单保持不变（SDK 缓存或落盘文件可能已部分清理），可用同一命令重试；
 * [Deleted.freedBytes] 为释放空间估算：优先取实际删除的文件字节，SDK 侧缓存由
 * SDK 自行清理无法统计时退回清单记录的体积。
 */
sealed interface ModelDeleteResult {

    /** 删除成功；[remaining] 为删除后的清单条数。 */
    data class Deleted(
        val name: String,
        val freedBytes: Long,
        val remaining: Int,
        val sdkRemoved: Boolean,
    ) : ModelDeleteResult

    /** 受保护条目或前置条件不满足，未做任何改动。 */
    data class Refused(val reason: String) : ModelDeleteResult

    /** 清单中没有该序号 / 名称。 */
    data class NotFound(val token: String) : ModelDeleteResult

    /** 执行失败，清单保持不变。 */
    data class Failed(val reason: String) : ModelDeleteResult
}

/**
 * 模型删除链路（Issue #111）：三处同步，任一步失败即中止：
 * 1. SDK 缓存：经 [GenieXModelGateway.remove] 移除托管模型，状态码 0 视为成功；
 * 2. 落盘文件：删除条目主文件与已知模型目录内其余 `*.gguf`（多分片场景）；
 * 3. 持久清单：[ModelCatalog.unregister] 注销登记，保证清单立即正确、重启不复活。
 *
 * 安全边界：只删除「条目路径本身」与「已知模型目录」内的 GGUF 文件——目录取 SDK
 * getPaths 的 model_dir，或主文件父目录；父目录等于 [modelsDir] 时只删主文件，
 * 避免误删同目录下的其他导入模型。内置冒烟模型（0 号）永不删除。
 *
 * @param modelsDir 模型目录（约定 filesDir/models）
 * @param catalog 清单：删除后由它注销登记并给出剩余条数
 * @param gateway SDK 模型管理器网关（null 时只清理落盘文件与清单，用于 JVM 单测）
 */
class ModelDeleter(
    private val modelsDir: File,
    private val catalog: ModelCatalog,
    private val gateway: GenieXModelGateway? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** 按清单序号删除；序号按当前清单解析，找不到返回 [ModelDeleteResult.NotFound]。 */
    suspend fun delete(ordinal: Int): ModelDeleteResult = withContext(ioDispatcher) {
        val entry = catalog.resolve(ordinal)
            ?: return@withContext ModelDeleteResult.NotFound(ordinal.toString())
        deleteEntry(entry)
    }

    /** 按展示名 / 文件名删除（大小写不敏感）。 */
    suspend fun deleteByName(token: String): ModelDeleteResult = withContext(ioDispatcher) {
        val entry = catalog.resolveByName(token)
            ?: return@withContext ModelDeleteResult.NotFound(token)
        deleteEntry(entry)
    }

    private suspend fun deleteEntry(entry: CatalogEntry): ModelDeleteResult {
        if (entry.ordinal == 0 || entry.source == ModelSource.BUILT_IN) {
            return ModelDeleteResult.Refused(BUILT_IN_REFUSED)
        }
        val sdkModel = installedSdkModel(entry.path)
        val files = filesToDelete(entry, sdkModel)
        val freedBytes = files.sumOf { it.length() }
        if (sdkModel != null) {
            val code = runCatching { gateway?.remove(sdkModel.modelName) ?: REMOVE_UNSUPPORTED }
                .getOrElse { error ->
                    return ModelDeleteResult.Failed(
                        "SDK 删除失败：" + (error.message ?: error::class.simpleName),
                    )
                }
            if (code != CODE_OK) {
                return ModelDeleteResult.Failed("SDK 删除失败（code=$code），模型仍保留在清单中。")
            }
        }
        files.forEach { file ->
            if (file.exists() && !file.delete()) {
                return ModelDeleteResult.Failed("无法删除模型文件：" + file.absolutePath)
            }
        }
        // 尽力清理空目录（多分片模型删净后不留空壳）；只处理已知模型目录。
        files.mapNotNull { it.parentFile }.distinct().forEach { dir ->
            if (isKnownModelDir(dir, sdkModel)) dir.delete()
        }
        val keys = buildSet {
            sdkModel?.let { add(it.modelName) }
            entry.path?.let { add(File(it).name) }
        }
        keys.forEach { key ->
            runCatching { catalog.unregister(key) }.onFailure { error ->
                return ModelDeleteResult.Failed(
                    "清单注销失败：" + (error.message ?: error::class.simpleName),
                )
            }
        }
        return ModelDeleteResult.Deleted(
            name = entry.name,
            freedBytes = maxOf(freedBytes, entry.sizeBytes ?: 0L),
            remaining = catalog.list().size,
            sdkRemoved = sdkModel != null,
        )
    }

    /**
     * 待删文件：条目主文件 + 同目录内同一模型的分片（`-0000x-of-0000y.gguf`）。
     *
     * 只按主文件的「分片主名」精确匹配，绝不按目录通配 `*.gguf`：同一目录常存放同一模型的
     * 多个量化档（如 Q4_K_M 与 Q8_0），删一个档不得连带删掉另一个档。目录取 SDK getPaths 的
     * model_dir，或主文件父目录；目录等于 [modelsDir] 时只删主文件。
     */
    private fun filesToDelete(entry: CatalogEntry, sdkModel: InstalledSdkModel?): List<File> {
        val primary = entry.path?.let(::File)
            ?: sdkModel?.modelPath?.takeIf { it.isNotBlank() }?.let(::File)
            ?: return emptyList()
        val targets = LinkedHashSet<File>()
        if (primary.isFile) targets += primary
        val stem = shardStemOf(primary.name)
        val dir = sdkModel?.modelDir?.takeIf { it.isNotBlank() }?.let(::File)
            ?: primary.parentFile
        if (dir != null && dir.isDirectory && !samePath(dir, modelsDir)) {
            targets += dir.listFiles { file ->
                file.isFile &&
                    file.name.endsWith(GGUF_SUFFIX, ignoreCase = true) &&
                    shardStemOf(file.name) == stem
            }.orEmpty()
        }
        return targets.toList()
    }

    /** 分片主名：去掉 `.gguf` 后缀与 llama.cpp 的 `-00001-of-00002` 分片标记。 */
    private fun shardStemOf(name: String): String =
        name.substringBeforeLast('.', name).replace(SHARD_SUFFIX, "")

    /** 按条目路径定位 SDK 托管模型（对比 getPaths 的 model_path / model_dir）。 */
    private suspend fun installedSdkModel(path: String?): InstalledSdkModel? {
        val gateway = gateway ?: return null
        if (path.isNullOrBlank()) return null
        return runCatching {
            gateway.list().mapNotNull { modelName ->
                val paths = runCatching { gateway.getPaths(modelName) }.getOrNull()
                    ?: return@mapNotNull null
                InstalledSdkModel(modelName, paths.model_path, paths.model_dir)
            }.firstOrNull { model -> model.matches(path) }
        }.getOrNull()
    }

    private fun isKnownModelDir(dir: File, sdkModel: InstalledSdkModel?): Boolean {
        val sdkDir = sdkModel?.modelDir?.takeIf { it.isNotBlank() }?.let(::File)
        return samePath(dir, sdkDir) || isUnder(dir, sdkModel?.modelPath?.let(::File)?.parentFile)
    }

    private fun isUnder(dir: File, parent: File?): Boolean {
        if (parent == null) return false
        val dirPath = runCatching { dir.canonicalPath }.getOrNull() ?: return false
        val parentPath = runCatching { parent.canonicalPath }.getOrNull() ?: return false
        return dirPath != parentPath && dirPath.startsWith(parentPath + File.separator)
    }

    private fun samePath(left: File?, right: File?): Boolean {
        if (left == null || right == null) return false
        val leftPath = runCatching { left.canonicalPath }.getOrNull() ?: return false
        val rightPath = runCatching { right.canonicalPath }.getOrNull() ?: return false
        return leftPath == rightPath
    }

    private data class InstalledSdkModel(
        val modelName: String,
        val modelPath: String?,
        val modelDir: String?,
    ) {
        fun matches(path: String): Boolean {
            if (modelPath != null && modelPath == path) return true
            val dir = modelDir?.takeIf { it.isNotBlank() } ?: return false
            val prefix = dir.trimEnd(File.separatorChar) + File.separator
            return path == dir || path.startsWith(prefix)
        }
    }

    companion object {

        /** SDK 删除状态码：0 表示成功（与 pull 错误码同源）。 */
        const val CODE_OK = 0

        /** 受保护条目拒绝文案（终端与 Normal 共用）。 */
        const val BUILT_IN_REFUSED = "内置冒烟模型不可删除：0 号模型用于冒烟与降级，请保留。"

        private const val REMOVE_UNSUPPORTED = GenieXModelGateway.REMOVE_UNSUPPORTED
        private const val GGUF_SUFFIX = ".gguf"

        /** llama.cpp 多分片文件名的分片标记：`model-00001-of-00003.gguf`。 */
        private val SHARD_SUFFIX = Regex("-\\d{5}-of-\\d{5}" + "$")
    }
}
