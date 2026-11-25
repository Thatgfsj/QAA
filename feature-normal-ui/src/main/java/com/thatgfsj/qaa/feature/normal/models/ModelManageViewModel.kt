package com.thatgfsj.qaa.feature.normal.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thatgfsj.qaa.feature.normal.model.ModelInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 模型管理页 ViewModel：下载区 + 真实清单（Issue #80）。
 *
 * 未注入 [NormalModelPort]（骨架预览）时退回 Issue #8 的占位目录与占位加载动作；
 * 注入后清单来自装配层的 ModelCatalog 投影，下载与续传走 ModelPullClient。
 */
class ModelManageViewModel : ViewModel() {

    private val _models = MutableStateFlow(defaultCatalog())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _urlError = MutableStateFlow<String?>(null)
    val urlError: StateFlow<String?> = _urlError.asStateFlow()

    private val _download = MutableStateFlow<NormalDownloadState>(NormalDownloadState.Idle)
    val download: StateFlow<NormalDownloadState> = _download.asStateFlow()

    private val _resumeTask = MutableStateFlow<NormalResumeTask?>(null)
    val resumeTask: StateFlow<NormalResumeTask?> = _resumeTask.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _portBound = MutableStateFlow(false)
    val portBound: StateFlow<Boolean> = _portBound.asStateFlow()

    /** 正在载入的模型序号（null 表示空闲）：按钮据此显示载入中态。 */
    private val _loadingOrdinal = MutableStateFlow<Int?>(null)
    val loadingOrdinal: StateFlow<Int?> = _loadingOrdinal.asStateFlow()

    /** 待二次确认删除的模型（null 表示无确认框）。 */
    private val _pendingDelete = MutableStateFlow<ModelInfo?>(null)
    val pendingDelete: StateFlow<ModelInfo?> = _pendingDelete.asStateFlow()

    /** 正在删除的模型序号（null 表示空闲）：删除按钮据此显示删除中态。 */
    private val _deletingOrdinal = MutableStateFlow<Int?>(null)
    val deletingOrdinal: StateFlow<Int?> = _deletingOrdinal.asStateFlow()

    private var port: NormalModelPort? = null
    private var collector: Job? = null

    /** 装配层注入下载端口（null 复位为骨架占位）；重复注入同一实例忽略。 */
    fun bindPort(newPort: NormalModelPort?) {
        if (port === newPort) return
        port = newPort
        collector?.cancel()
        collector = null
        _portBound.value = newPort != null
        _download.value = NormalDownloadState.Idle
        _resumeTask.value = null
        _urlError.value = null
        _notice.value = null
        _loadingOrdinal.value = null
        _pendingDelete.value = null
        _deletingOrdinal.value = null
        if (newPort == null) {
            _models.value = defaultCatalog()
            return
        }
        collector = viewModelScope.launch {
            launch { newPort.downloadState.collect(::onDownloadState) }
            launch { newPort.resumeTask.collect { _resumeTask.value = it } }
            refreshModels(newPort)
        }
    }

    /** 进入页面时刷新：恢复检测（幂等）后重读清单。 */
    fun refresh() {
        val port = this.port ?: return
        viewModelScope.launch {
            runCatching { port.recoverDownload() }
            refreshModels(port)
        }
    }

    /** 链接输入变化：清掉上一次的校验错误。 */
    fun onUrlChange(text: String) {
        _urlInput.value = text
        if (_urlError.value != null) _urlError.value = null
    }

    /** 提交下载：空值本地拦截，HF/ModelScope 形态由装配层解析规则给出中文原因。 */
    fun submitDownload() {
        val port = this.port
        if (port == null) {
            _notice.value = "下载入口未接线：请确认 Normal 窗口由正式装配启动。"
            return
        }
        if (_download.value is NormalDownloadState.Running) return
        val source = _urlInput.value.trim()
        if (source.isEmpty()) {
            _urlError.value = "请填写模型链接（Hugging Face 或 ModelScope）"
            return
        }
        port.validateSource(source)?.let { reason ->
            _urlError.value = reason
            return
        }
        _urlError.value = null
        port.startDownload(source)?.let { _urlError.value = it }
    }

    /** 继续下载：优先使用待续传记录的 URL，没有记录时按输入框重试。 */
    fun continueDownload() {
        val task = _resumeTask.value
        if (task == null) {
            submitDownload()
            return
        }
        val port = this.port ?: return
        _urlInput.value = task.url
        _urlError.value = null
        port.startDownload(task.url)?.let { _urlError.value = it }
    }

    /** 取消进行中的下载。 */
    fun cancelDownload() {
        port?.cancelDownload()
    }

    /** 放弃续传：清除记录与提示。 */
    fun discardDownload() {
        port?.discardDownload()
        _resumeTask.value = null
        if (_download.value is NormalDownloadState.Failed) _download.value = NormalDownloadState.Idle
    }

    /** 目录项动作：接线后走真实引擎加载，未接线时保持占位切换。 */
    fun activate(model: ModelInfo) {
        val port = this.port
        if (port == null) {
            toggleLoaded(model.id)
            return
        }
        if (_loadingOrdinal.value != null) return
        // 载入中态跟随真实挂起调用：开始置序号、结束（无论成败）复位。
        _loadingOrdinal.value = model.ordinal
        viewModelScope.launch {
            try {
                _notice.value = runCatching { port.loadModel(model.ordinal) }
                    .getOrElse { "模型加载失败：" + (it.message ?: it::class.simpleName) }
                refreshModels(port)
            } finally {
                _loadingOrdinal.value = null
            }
        }
    }

    /** 请求删除：内置 0 号直接拒绝，其余进入二次确认（不直接删）。 */
    fun requestDelete(model: ModelInfo) {
        if (port == null) {
            _notice.value = "删除入口未接线：请确认 Normal 窗口由正式装配启动。"
            return
        }
        if (model.ordinal == 0) {
            _notice.value = "内置冒烟模型不可删除：0 号模型用于冒烟与降级，请保留。"
            return
        }
        if (_deletingOrdinal.value != null) return
        _pendingDelete.value = model
    }

    /** 取消删除确认（不发起删除）。 */
    fun cancelDelete() {
        _pendingDelete.value = null
    }

    /** 确认删除：转端口执行并回显中文结果，成功后刷新清单（#111）。 */
    fun confirmDelete() {
        val model = _pendingDelete.value ?: return
        _pendingDelete.value = null
        val port = this.port ?: return
        if (_deletingOrdinal.value != null) return
        _deletingOrdinal.value = model.ordinal
        viewModelScope.launch {
            try {
                _notice.value = runCatching { port.deleteModel(model.ordinal) }
                    .getOrElse { "删除失败：" + (it.message ?: it::class.simpleName) }
                refreshModels(port)
            } finally {
                _deletingOrdinal.value = null
            }
        }
    }

    /** 占位动作：切换某条目的加载状态（骨架预览）。 */
    fun toggleLoaded(modelId: String) {
        _models.update { list ->
            list.map { if (it.id == modelId) it.copy(loaded = !it.loaded) else it }
        }
    }

    private fun onDownloadState(state: NormalDownloadState) {
        _download.value = state
        when (state) {
            is NormalDownloadState.Running -> _notice.value = null
            is NormalDownloadState.Completed -> {
                // 完成即登记：清空输入并重读清单，让新条目出现在列表里。
                _urlInput.value = ""
                port?.let { bound -> viewModelScope.launch { refreshModels(bound) } }
            }
            is NormalDownloadState.Failed -> _notice.value = "下载失败：" + state.message
            NormalDownloadState.Idle -> Unit
        }
    }

    private suspend fun refreshModels(port: NormalModelPort) {
        val entries = runCatching { port.modelEntries() }.getOrElse { error ->
            _notice.value = "模型清单读取失败：" + (error.message ?: error::class.simpleName)
            return
        }
        _models.value = entries.map(::toModelInfo)
    }

    private fun toModelInfo(entry: NormalModelEntry): ModelInfo = ModelInfo(
        id = "model-" + entry.ordinal,
        name = entry.name,
        quantization = entry.quantization ?: "未知",
        sizeBytes = entry.sizeBytes,
        source = entry.sourceLabel,
        loaded = false,
        ordinal = entry.ordinal,
        compatibilityNote = entry.compatibilityNote,
    )

    private fun defaultCatalog(): List<ModelInfo> = listOf(
        ModelInfo(
            id = "supra2.5-pro-instruct-q4_0",
            name = "Supra2.5-Pro-Instruct",
            quantization = "Q4_0",
            sizeBytes = 40L * 1024 * 1024,
            source = "liodon-ai/Supra2.5-Pro-Instruct-GGUF",
            loaded = false,
        ),
    )
}
