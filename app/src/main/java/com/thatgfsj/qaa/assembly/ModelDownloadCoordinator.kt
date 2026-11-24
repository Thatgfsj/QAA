package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.inference.ModelCatalog
import com.thatgfsj.qaa.core.inference.ModelPullClient
import com.thatgfsj.qaa.core.inference.ModelPullEvent
import com.thatgfsj.qaa.core.inference.ModelPullTarget
import com.thatgfsj.qaa.core.inference.ModelSource
import com.thatgfsj.qaa.core.inference.RegisteredModel
import com.thatgfsj.qaa.core.inference.parseModelPullTarget
import com.thatgfsj.qaa.feature.normal.models.NormalDownloadState
import com.thatgfsj.qaa.feature.normal.models.NormalResumeTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Normal 模型下载编排：来源校验（复用 core-inference 解析规则）→ [ModelPullClient.pull]
 * → 进度/完成/失败投影为 UI 状态，并与 [ModelDownloadTracker]（持久化）、[ModelCatalog]（登记）联动。
 *
 * 续传语义：GenieX 保留已下载分块，本类只负责「用同一 URL 再调一次 pull」，
 * 因此「继续下载」等价于对记录 URL 重新 [start]；登记成功后清空续传提示。
 */
class ModelDownloadCoordinator(
    private val tracker: ModelDownloadTracker,
    private val catalog: ModelCatalog,
    private val scope: CoroutineScope,
    private val pullClient: ModelPullClient = ModelPullClient(),
) {

    private val _state = MutableStateFlow<NormalDownloadState>(NormalDownloadState.Idle)

    /** 当前下载状态（Idle / 进行中 / 可续传失败 / 完成）。 */
    val state: StateFlow<NormalDownloadState> = _state.asStateFlow()

    private val _resumeTask = MutableStateFlow<NormalResumeTask?>(null)

    /** 待续传任务（启动恢复检测或可续传失败后出现；继续/放弃后清空）。 */
    val resumeTask: StateFlow<NormalResumeTask?> = _resumeTask.asStateFlow()

    @Volatile
    private var job: Job? = null

    /** 校验模型来源（HF/ModelScope URL 或 `org/repo`）；null 表示可下载。 */
    fun validate(source: String): String? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return "请填写模型链接（Hugging Face 或 ModelScope）"
        val error = parseModelPullTarget(trimmed).exceptionOrNull() ?: return null
        return error.message ?: ("无法解析的模型来源：" + trimmed)
    }

    /** 开始下载；返回立即失败的中文原因，null 表示已启动。 */
    fun start(source: String): String? {
        val trimmed = source.trim()
        validate(trimmed)?.let { return it }
        val target: ModelPullTarget = parseModelPullTarget(trimmed)
            .getOrElse { return it.message ?: ("无法解析的模型来源：" + trimmed) }
        if (job?.isActive == true) return "已有下载任务在进行，请等待完成或先取消。"
        val name = target.modelName.substringAfterLast('/').ifBlank { target.modelName }
        _resumeTask.value = null
        _state.value = NormalDownloadState.Running(name = name, bytes = 0, totalBytes = null, percent = null)
        job = scope.launch { download(trimmed, target, name) }
        return null
    }

    /** 继续最近的可续传任务；返回中文原因或 null。 */
    fun resume(): String? {
        val task = _resumeTask.value ?: return "没有可继续的下载任务。"
        return start(task.url)
    }

    /** 取消进行中的下载（SDK 保留分块，记录转为可续传）。 */
    fun cancel() {
        job?.cancel()
    }

    /** 放弃续传：清除记录与提示（不改动 SDK 已下载分块）。 */
    fun discard() {
        val task = _resumeTask.value ?: return
        _resumeTask.value = null
        if (_state.value is NormalDownloadState.Failed) _state.value = NormalDownloadState.Idle
        scope.launch { tracker.discard(task.url) }
    }

    /** 恢复检测（幂等）：把进程退出遗留的进行中任务转为可续传并暴露到 [resumeTask]。 */
    suspend fun recover() {
        if (job?.isActive == true) return
        val record = tracker.recover() ?: return
        _resumeTask.value = toResumeTask(record)
    }

    private suspend fun download(source: String, target: ModelPullTarget, name: String) {
        tracker.start(source, name)
        try {
            pullClient.pull(target).collect { event -> handle(source, name, event) }
        } catch (cancelled: CancellationException) {
            tracker.markInterrupted(source)
            _state.value = NormalDownloadState.Idle
            publishResume(source)
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: (error::class.simpleName ?: "下载中断")
            // 传输异常按可续传处理：部分文件已落盘，同参数重跑即续传。
            tracker.markFailed(source, message, resumable = true)
            _state.value = NormalDownloadState.Failed(message, resumable = true)
            publishResume(source)
        }
    }

    private suspend fun handle(source: String, name: String, event: ModelPullEvent) {
        when (event) {
            is ModelPullEvent.Progress -> {
                tracker.progress(source, event.bytes, event.totalBytes)
                _state.value = NormalDownloadState.Running(name, event.bytes, event.totalBytes, event.percent)
            }
            is ModelPullEvent.Completed -> {
                val display = event.modelName.substringAfterLast('/').ifBlank { name }
                val ordinal = register(event, display)
                tracker.markCompleted(source)
                _resumeTask.value = null
                _state.value = NormalDownloadState.Completed(display, event.resolvedPath, ordinal)
            }
            is ModelPullEvent.Failed -> {
                val record = tracker.markFailed(source, event.message, event.resumable)
                _state.value = NormalDownloadState.Failed(event.message, event.resumable)
                _resumeTask.value = record?.takeIf { event.resumable }?.let(::toResumeTask)
            }
        }
    }

    /** 完成即登记清单（来源 PULL）并解析展示序号；登记失败不影响完成态。 */
    private suspend fun register(event: ModelPullEvent.Completed, display: String): Int? {
        runCatching { catalog.register(RegisteredModel(event.modelName, display, ModelSource.PULL)) }
        return runCatching {
            catalog.list().firstOrNull { entry ->
                (event.resolvedPath != null && entry.path == event.resolvedPath) || entry.name == display
            }?.ordinal
        }.getOrNull()
    }

    private suspend fun publishResume(source: String) {
        val record = tracker.find(source) ?: return
        _resumeTask.value = record.takeIf { it.resumable }?.let(::toResumeTask)
    }

    private fun toResumeTask(record: DownloadRecord): NormalResumeTask = NormalResumeTask(
        url = record.url,
        target = record.target,
        bytes = record.bytes,
        totalBytes = record.totalBytes,
    )
}
