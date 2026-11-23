package com.thatgfsj.qaa.core.inference

import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.ModelPullInput
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext

/** 拉取目标：hub/modelName/precision 对应 ModelPullInput；0.10.0 无单文件字段，[fileName] 仅推导精度。 */
data class ModelPullTarget(
    val input: String,
    val hub: HubSource,
    val modelName: String,
    val fileName: String? = null,
    val precision: String? = null,
)

/** 拉取事件：GenieX PullEvent 的 QAA 投影。 */
sealed interface ModelPullEvent {

    /** 多文件字节累加；totalBytes 未知（SDK 给 -1）为 null，percent 随之为 null。 */
    data class Progress(val bytes: Long, val totalBytes: Long?, val percent: Float?) : ModelPullEvent
    /** 完成；resolvedPath 为 getPaths 的 model_path，查询失败为 null。 */
    data class Completed(val modelName: String, val resolvedPath: String?) : ModelPullEvent
    /** 失败；resumable 表示部分文件已落盘，同参数重跑即续传（§8.2）。 */
    data class Failed(val code: Int, val message: String, val resumable: Boolean) : ModelPullEvent
}

/** 拉取链路用到的 GenieX FFI 错误码（§9.1）。 */
object GenieXPullErrorCodes {
    const val UNKNOWN = -100000
    const val INVALID_INPUT = -100001
    const val NETWORK = -100005
    const val CANCELLED = -100006
    const val AUTH = -100009
    const val HUB_MODEL_NOT_FOUND = -100010
    const val RATE_LIMITED = -100011
    const val HUB_SERVER = -100012
    const val INSUFFICIENT_DISK_SPACE = -100017

    /** 传输中断类错误可续传；参数非法、鉴权失败、hub 无此模型等重试不会推进。 */
    fun isResumable(code: Int): Boolean =
        code == NETWORK || code == CANCELLED || code == RATE_LIMITED ||
            code == HUB_SERVER || code == INSUFFICIENT_DISK_SPACE
}

/** 模型拉取客户端：解析输入 → 调 GenieX `pullFlow` → 投影为 [ModelPullEvent]。
 *
 * 断点续传由 SDK 原生负责（§8.2）：取消或中断后 Rust 侧保留分块，同参数重跑自动续传。
 * 采集方取消时 [CancellationException] 原样上抛，不转成 [ModelPullEvent.Failed]。
 */
class ModelPullClient(
    private val gateway: GenieXModelGateway = DefaultGenieXModelGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** 拉取 [source]（URL 或 `org/repo`）；解析失败直接发出一条 [ModelPullEvent.Failed]。 */
    fun pull(source: String, hfToken: String? = null): Flow<ModelPullEvent> = flow {
        val target = parseModelPullTarget(source).getOrElse { error ->
            emit(
                ModelPullEvent.Failed(
                    code = GenieXPullErrorCodes.INVALID_INPUT,
                    message = error.message ?: ("非法的模型来源：" + source),
                    resumable = false,
                ),
            )
            return@flow
        }
        emitAll(pull(target, hfToken))
    }

    /** 按已解析目标拉取（调用方已选定 hub/precision 时使用）。 */
    fun pull(target: ModelPullTarget, hfToken: String? = null): Flow<ModelPullEvent> = flow {
        val input = ModelPullInput(
            model_name = target.modelName,
            precision = target.precision,
            hub = target.hub,
            hf_token = hfToken,
        )
        try {
            emitAll(
                gateway.pull(input).transform { event ->
                    when (event) {
                        is ModelManagerWrapper.PullEvent.Progress -> emit(event.toPullEvent())
                        is ModelManagerWrapper.PullEvent.Completed ->
                            emit(ModelPullEvent.Completed(target.modelName, resolvePath(target.modelName)))
                        is ModelManagerWrapper.PullEvent.Error -> emit(
                            ModelPullEvent.Failed(
                                code = event.code,
                                message = event.message,
                                resumable = GenieXPullErrorCodes.isResumable(event.code),
                            ),
                        )
                    }
                },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // 传输期异常：SDK 已中断当前分块，部分文件可续传，按可重试失败上报。
            emit(
                ModelPullEvent.Failed(
                    code = GenieXPullErrorCodes.UNKNOWN,
                    message = error.message ?: "拉取中断",
                    resumable = true,
                ),
            )
        }
    }

    private suspend fun resolvePath(modelName: String): String? = withContext(ioDispatcher) {
        runCatching { gateway.getPaths(modelName)?.model_path }.getOrNull()
    }

    private fun ModelManagerWrapper.PullEvent.Progress.toPullEvent(): ModelPullEvent.Progress {
        val total = if (files.any { it.total_bytes < 0 }) null else files.sumOf { it.total_bytes }
        val bytes = files.sumOf { it.downloaded_bytes.coerceAtLeast(0) }
        val percent = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
        return ModelPullEvent.Progress(bytes = bytes, totalBytes = total, percent = percent)
    }
}

/**
 * 模型来源归一化规则：
 *
 * | 输入 | hub | model_name |
 * | --- | --- | --- |
 * | `org/repo` | AUTO（SDK 按名字路由） | `org/repo` |
 * | `https://huggingface.co/org/repo` | HUGGINGFACE | `org/repo` |
 * | `.../huggingface.co/org/repo/blob/main/file.gguf` | HUGGINGFACE | `org/repo`（文件名 → precision） |
 * | `.../huggingface.co/org/repo/tree/main` | HUGGINGFACE | `org/repo` |
 * | `https://modelscope.cn/models/org/repo` | MODELSCOPE | `org/repo` |
 * | `.../modelscope.cn/models/org/repo/resolve/master/file.gguf` | MODELSCOPE | `org/repo` |
 *
 * - 仅接受 http/https，host 限 huggingface.co（含 www）与 modelscope.cn/.com（含 www）；
 * - repo 末尾 `.git` 去掉；`blob|resolve|tree|raw` 后首段为版本号（main/master/latest
 *   或任意标签），其后剩余路径为文件名；
 * - 文件名只推导 precision（Q4_0 / Q4_K_M / IQ4_XS …）：0.10.0 的 ModelPullInput 无
 *   单文件字段，拉取粒度仍是 repo；
 * - ftp、未知站点、缺少 org/repo、空输入返回 [Result.failure]。
 */
fun parseModelPullTarget(raw: String): Result<ModelPullTarget> = runCatching {
    require(raw.isNotBlank()) { "模型来源为空" }
    val input = raw.trim()
    if (input.contains("://")) parseUrlTarget(input) else parseModelNameTarget(input)
}

private fun parseModelNameTarget(input: String): ModelPullTarget {
    val segments = input.split('/').filter { it.isNotBlank() }
    require(segments.size == 2) { "模型名应为 <org>/<repo> 形态：" + input }
    require(segments.none { it.any(Char::isWhitespace) }) { "模型名不能包含空白：" + input }
    return ModelPullTarget(input, HubSource.AUTO, modelNameOf(segments[0], segments[1]))
}

private fun parseUrlTarget(input: String): ModelPullTarget {
    val uri = runCatching { URI(input) }.getOrElse { throw IllegalArgumentException("无法解析的模型 URL：" + input) }
    val scheme = uri.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "仅支持 http(s) 模型 URL：" + input }
    val host = uri.host?.lowercase() ?: throw IllegalArgumentException("模型 URL 缺少主机：" + input)
    val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
    return when (host) {
        "huggingface.co", "www.huggingface.co" -> hubTarget(input, segments, HubSource.HUGGINGFACE)
        "modelscope.cn", "www.modelscope.cn", "modelscope.com" -> {
            val body = if (segments.firstOrNull() == "models") segments.drop(1) else segments
            hubTarget(input, body, HubSource.MODELSCOPE)
        }
        else -> throw IllegalArgumentException("不支持的模型站点：" + host)
    }
}

private fun hubTarget(input: String, segments: List<String>, hub: HubSource): ModelPullTarget {
    require(segments.size >= 2) { "模型 URL 需包含 <org>/<repo>：" + input }
    val fileName = fileSegments(segments.drop(2))
    return ModelPullTarget(
        input = input,
        hub = hub,
        modelName = modelNameOf(segments[0], segments[1]),
        fileName = fileName,
        precision = quantizationOf(fileName),
    )
}

private val REVISION_MARKERS = setOf("blob", "resolve", "tree", "raw")
private val REVISION_NAMES = setOf("main", "master", "latest")

/** 从 `<marker>/<revision>/<file…>` 取文件路径；没有文件段返回 null。 */
private fun fileSegments(rest: List<String>): String? {
    val first = rest.firstOrNull()?.lowercase()
    val hadMarker = first != null && first in REVISION_MARKERS
    val segments = if (hadMarker) rest.drop(1) else rest
    if (segments.isEmpty()) return null
    if (segments.size == 1) return segments.first().takeIf { it.lowercase() !in REVISION_NAMES }
    val dropRevision = hadMarker || segments.first().lowercase() in REVISION_NAMES
    return (if (dropRevision) segments.drop(1) else segments).joinToString("/").takeIf { it.isNotBlank() }
}

private fun modelNameOf(org: String, repo: String): String = org + "/" + repo.removeSuffix(".git")
