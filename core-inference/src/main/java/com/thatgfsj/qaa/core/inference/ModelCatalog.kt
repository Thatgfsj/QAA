package com.thatgfsj.qaa.core.inference

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 模型来源：内置资产 / hub 下载 / 本地旁加载导入。 */
enum class ModelSource { BUILT_IN, PULL, IMPORT }

/**
 * 模型清单条目：`model list` 一行，也是 `chat <序号>` 的解析目标。
 *
 * @param ordinal 从 1 起、按"内置优先 + 展示名排序"生成的稳定序号
 * @param sizeBytes 模型体积（多文件模型为各 GGUF 之和），未知为 null
 * @param path 主文件绝对路径；未落盘或 SDK 查询失败时为 null
 */
data class CatalogEntry(
    val ordinal: Int,
    val name: String,
    val quantization: String?,
    val sizeBytes: Long?,
    val source: ModelSource,
    val path: String?,
)

/** 持久清单条目（命名规则推不出的展示名与来源）；[key] 为 SDK 模型名或文件名。 */
data class RegisteredModel(val key: String, val name: String, val source: ModelSource)

/**
 * 本地模型清单：合并内置条目、GenieX 托管模型（list + getPaths，0.10.0 无
 * listDetailed/query）与模型目录 `*.gguf` 扫描结果，按绝对路径去重；体积按 model_dir
 * 内 GGUF 求和。
 *
 * 来源优先级：持久清单 > 命名规则（`local/` 前缀为 IMPORT，其余 SDK 模型为 PULL）；
 * 内置条目恒为序号 1，其余按展示名排序。
 */
class ModelCatalog(
    private val modelsDir: File,
    private val gateway: GenieXModelGateway? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val manifest = ModelManifest(File(modelsDir, MANIFEST_FILE_NAME))
    private val registerMutex = Mutex()

    /** 当前清单（内置条目恒为序号 1，其余按展示名排序后连续编号）。 */
    suspend fun list(): List<CatalogEntry> = withContext(ioDispatcher) { buildEntries() }

    /** 按序号取条目（1 起，越界或非正数为 null）。 */
    suspend fun resolve(ordinal: Int): CatalogEntry? =
        if (ordinal <= 0) null else list().firstOrNull { it.ordinal == ordinal }

    /** 按序号或展示名/文件名取条目，大小写不敏感；未命中为 null。 */
    suspend fun resolveByName(token: String): CatalogEntry? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null
        val entries = list()
        trimmed.toIntOrNull()?.let { ordinal -> return entries.firstOrNull { it.ordinal == ordinal } }
        val lower = trimmed.lowercase()
        return entries.firstOrNull {
            it.name.lowercase() == lower || it.path?.substringAfterLast('/')?.lowercase() == lower
        }
    }

    /** 登记展示名与来源（[RegisteredModel.key] 同名覆盖），清单写入为原子替换。 */
    suspend fun register(model: RegisteredModel) = withContext(ioDispatcher) {
        registerMutex.withLock {
            val entries = manifest.read().associateBy { it.key }.toMutableMap()
            entries[model.key] = model
            manifest.write(entries.values)
        }
    }

    private suspend fun buildEntries(): List<CatalogEntry> {
        val registered = manifest.read().associateBy { it.key }
        val builtInFile = File(modelsDir, BUILT_IN_FILE_NAME)
        val builtInExists = builtInFile.isFile
        val entries = mutableListOf(
            CatalogEntry(
                ordinal = 0,
                name = BUILT_IN_NAME,
                quantization = BUILT_IN_QUANTIZATION,
                sizeBytes = if (builtInExists) builtInFile.length() else BUILT_IN_SIZE_BYTES,
                source = ModelSource.BUILT_IN,
                path = builtInFile.takeIf { builtInExists }?.absolutePath,
            ),
        )
        val seenPaths = mutableSetOf<String>()
        if (builtInExists) seenPaths += builtInFile.absolutePath
        installedModels().forEach { installed ->
            val path = installed.path
            if (path != null && !seenPaths.add(path)) return@forEach
            val meta = registered[installed.modelName]
            entries += CatalogEntry(
                ordinal = 0,
                name = meta?.name ?: displayNameOf(installed.fileName ?: installed.modelName),
                quantization = quantizationOf(installed.fileName),
                sizeBytes = installed.sizeBytes,
                source = meta?.source ?: sourceOf(installed.modelName),
                path = path,
            )
        }
        modelFiles(modelsDir).forEach { file ->
            if (!seenPaths.add(file.absolutePath)) return@forEach
            val meta = registered[file.name]
            entries += CatalogEntry(
                ordinal = 0,
                name = meta?.name ?: displayNameOf(file.name),
                quantization = quantizationOf(file.name),
                sizeBytes = file.length(),
                source = meta?.source ?: ModelSource.IMPORT,
                path = file.absolutePath,
            )
        }
        return entries
            .sortedWith(compareBy({ it.source != ModelSource.BUILT_IN }, { it.name.lowercase() }))
            .mapIndexed { index, entry -> entry.copy(ordinal = index + 1) }
    }

    private suspend fun installedModels(): List<InstalledModel> {
        val gateway = gateway ?: return emptyList()
        return runCatching { gateway.list() }.getOrDefault(emptyList()).mapNotNull { modelName ->
            val paths = runCatching { gateway.getPaths(modelName) }.getOrNull() ?: return@mapNotNull null
            val path = paths.model_path?.takeIf { it.isNotBlank() }
            val dir = paths.model_dir?.takeIf { it.isNotBlank() }?.let(::File)
                ?: path?.let { File(it).parentFile }
            val size = dir?.let { modelFiles(it).sumOf { file -> file.length() } }?.takeIf { it > 0 }
                ?: path?.let { File(it).length() }
            InstalledModel(modelName, path, path?.substringAfterLast('/'), size)
        }
    }

    private fun modelFiles(dir: File): List<File> = dir.listFiles { file ->
        file.isFile && file.name.endsWith(GGUF_SUFFIX, ignoreCase = true)
    }?.sortedBy { it.name.lowercase() }.orEmpty()

    private fun sourceOf(modelName: String): ModelSource =
        if (modelName.startsWith(LOCAL_PREFIX)) ModelSource.IMPORT else ModelSource.PULL

    private data class InstalledModel(
        val modelName: String,
        val path: String?,
        val fileName: String?,
        val sizeBytes: Long?,
    )

    companion object {
        /** 内置冒烟模型（README 与 docs/ARCHITECTURE.md 第 1 节）。 */
        const val BUILT_IN_NAME = "Supra2.5-Pro-Instruct"
        const val BUILT_IN_QUANTIZATION = "Q4_0"
        const val BUILT_IN_SIZE_BYTES = 40L * 1024 * 1024
        const val BUILT_IN_FILE_NAME = "Supra2.5-Pro-Instruct-Q4_0.gguf"
        /** 持久清单文件名（位于模型目录内）。 */
        const val MANIFEST_FILE_NAME = "models.tsv"
        private const val GGUF_SUFFIX = ".gguf"
        private const val LOCAL_PREFIX = "local/"
    }
}

/**
 * 模型清单文件（TSV，`#` 开头为注释）：键、展示名、来源。
 * 写入先落 `.tmp` 再原子改名，失败清理临时文件（与 [AssetModelReleaser] 同一模式）。
 */
private class ModelManifest(private val file: File) {

    fun read(): List<RegisteredModel> {
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            val trimmed = line.trimEnd('\r')
            if (trimmed.isBlank() || trimmed.startsWith("#")) return@mapNotNull null
            val fields = trimmed.split('\t')
            if (fields.size < 3) return@mapNotNull null
            val key = fields[0].trim().ifEmpty { return@mapNotNull null }
            val source = ModelSource.entries.firstOrNull { it.name == fields[2].trim() }
                ?: return@mapNotNull null
            RegisteredModel(key, fields[1].trim().ifEmpty { key }, source)
        }
    }

    fun write(entries: Collection<RegisteredModel>) {
        val parent = file.absoluteFile.parentFile ?: File(".")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("无法创建模型目录：" + parent.absolutePath)
        }
        val tmp = File(parent, file.name + ".tmp")
        try {
            tmp.bufferedWriter().use { writer ->
                writer.write(HEADER)
                writer.newLine()
                entries.sortedBy { it.key.lowercase() }.forEach { entry ->
                    writer.write(listOf(entry.key, entry.name, entry.source.name).joinToString("\t"))
                    writer.newLine()
                }
            }
            if (file.exists() && !file.delete()) throw IOException("无法覆盖模型清单：" + file.absolutePath)
            if (!tmp.renameTo(file)) throw IOException("模型清单改名失败：" + tmp.absolutePath)
        } catch (throwable: Throwable) {
            tmp.delete()
            throw throwable
        }
    }

    private companion object {
        const val HEADER = "# key\tname\tsource"
    }
}
