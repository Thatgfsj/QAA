package com.thatgfsj.qaa.core.inference

import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.ModelPaths
import com.geniex.sdk.bean.ModelPullInput
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** 模型管理器网关：隔离 ModelManagerWrapper 单例，便于 JVM 单测替换。 */
interface GenieXModelGateway {

    suspend fun getPaths(modelName: String): ModelPaths?

    /** 列出已托管模型名（docs/research/geniex-api.md §8.3；0.10.0 wrapper 无 listDetailed/query）。 */
    suspend fun list(): List<String>

    fun pull(input: ModelPullInput): Flow<ModelManagerWrapper.PullEvent>

    /**
     * 删除 SDK 托管模型，返回 SDK 状态码（0 成功；[REMOVE_UNSUPPORTED] 表示网关未实现）。
     * 删除只清理 SDK 缓存，清单与落盘文件由 [ModelDeleter] 同步（#111）。
     */
    suspend fun remove(modelName: String): Int = REMOVE_UNSUPPORTED

    companion object {
        /** 网关未实现删除（JVM 单测替身默认值）。 */
        const val REMOVE_UNSUPPORTED = -1
    }
}

/** 生产实现：直接转发到 GenieX ModelManagerWrapper（需先完成 SDK init）。 */
object DefaultGenieXModelGateway : GenieXModelGateway {

    override suspend fun getPaths(modelName: String): ModelPaths? =
        ModelManagerWrapper.getPaths(modelName)

    override suspend fun list(): List<String> = ModelManagerWrapper.list()

    override suspend fun remove(modelName: String): Int = ModelManagerWrapper.remove(modelName)

    override fun pull(input: ModelPullInput): Flow<ModelManagerWrapper.PullEvent> =
        ModelManagerWrapper.pullFlow(input)
}

/**
 * 本地 GGUF 模型导入（docs/research/geniex-api.md §4.1 路径 B）。
 *
 * 采用路径 B（HubSource.LOCALFS + local_path）而非路径 A（直接喂 model_path）的依据：
 * - 官方推荐本地模型经模型管理器托管；导入后与网络下载的模型同构，getPaths 返回的
 *   runtime_id 为权威值（§2.3 "authoritative — prefer over UI selection"）；
 * - SDK 缓存目录（filesDir/geniex/models/...）由模型管理器独占管理，模型清单、
 *   类型推断与后续下载/删除路径统一，避免应用侧自建第二套模型目录。
 *
 * 导入幂等：同 model_name 已存在则直接复用 getPaths，不再拷贝。
 *
 * 目录约定：SDK 的 local_path 取"包含 *.gguf 的目录"，目录内全部 GGUF 会归入同一
 * 模型条目；调用方应按模型分目录释放（当前冒烟模型目录只有一个 GGUF，Issue #6）。
 */
class GenieXModelResolver(
    private val gateway: GenieXModelGateway = DefaultGenieXModelGateway,
) : ModelResolver {

    override suspend fun resolve(modelSource: String): Result<String> = runCatching {
        val source = File(modelSource)
        require(source.exists()) { "模型路径不存在：" + source.absolutePath }
        val modelName = localModelNameOf(source)
        gateway.getPaths(modelName)?.let { paths -> return@runCatching paths.model_path }
        val localDir = if (source.isDirectory) {
            source
        } else {
            source.parentFile ?: error("模型文件没有父目录：" + source.absolutePath)
        }
        pullLocal(modelName, localDir)
        val paths = gateway.getPaths(modelName)
            ?: error("模型导入后仍取不到路径：" + modelName)
        paths.model_path
    }

    private suspend fun pullLocal(modelName: String, localDir: File) {
        val input = ModelPullInput(
            model_name = modelName,
            hub = HubSource.LOCALFS,
            local_path = localDir.absolutePath,
        )
        val terminal = gateway.pull(input).first { event ->
            event is ModelManagerWrapper.PullEvent.Completed ||
                event is ModelManagerWrapper.PullEvent.Error
        }
        if (terminal is ModelManagerWrapper.PullEvent.Error) {
            error("导入本地模型失败（code=" + terminal.code + "）：" + terminal.message)
        }
    }
}

/**
 * 由本地路径推导 SDK 模型名："local/" + 文件名（去扩展名）归一化。
 *
 * 归一化只保留 ASCII 小写字母、数字与 . - _，其余字符替换为 '-'，避免模型名在
 * 缓存路径中出现平台相关字符。
 */
internal fun localModelNameOf(source: File): String {
    val base = if (source.isDirectory) source.name else source.nameWithoutExtension
    val slug = base.lowercase()
        .map { character ->
            if (character in 'a'..'z' || character in '0'..'9' ||
                character == '.' || character == '-' || character == '_'
            ) {
                character
            } else {
                '-'
            }
        }
        .joinToString("")
        .trim('-', '.')
    require(slug.isNotEmpty()) { "无法从模型路径推导名称：" + source.absolutePath }
    return "local/" + slug
}
