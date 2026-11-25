package com.thatgfsj.qaa.feature.normal.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 工作区页 ViewModel（Issue #126）：清单加载、新建（内置目录）、切换、重命名、删除。
 *
 * SAF 目录选择由宿主 Activity 负责：页面只触发 [onPickWorkspaceTree] 回调，选中结果经
 * 端口 [NormalWorkspacePort.createWorkspaceFromTree] 登记；宿主把回执写入
 * [NormalWorkspacePort.workspaceEvents]，本 ViewModel 订阅后刷新清单并回显。
 */
class WorkspaceViewModel : ViewModel() {

    private val _entries = MutableStateFlow<List<NormalWorkspaceEntry>>(emptyList())
    val entries: StateFlow<List<NormalWorkspaceEntry>> = _entries.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 正在执行操作的工作区 id（null 表示空闲）：按钮据此显示忙碌态。 */
    private val _busyId = MutableStateFlow<String?>(null)
    val busyId: StateFlow<String?> = _busyId.asStateFlow()

    /** 新建内置工作区的名称输入。 */
    private val _createName = MutableStateFlow("")
    val createName: StateFlow<String> = _createName.asStateFlow()

    /** 待二次确认删除的工作区（null 表示无确认框）。 */
    private val _pendingRemove = MutableStateFlow<NormalWorkspaceEntry?>(null)
    val pendingRemove: StateFlow<NormalWorkspaceEntry?> = _pendingRemove.asStateFlow()

    /** 待重命名的工作区（null 表示无对话框）。 */
    private val _pendingRename = MutableStateFlow<NormalWorkspaceEntry?>(null)
    val pendingRename: StateFlow<NormalWorkspaceEntry?> = _pendingRename.asStateFlow()

    /** 重命名输入框草稿。 */
    private val _renameName = MutableStateFlow("")
    val renameName: StateFlow<String> = _renameName.asStateFlow()

    private val _portBound = MutableStateFlow(false)
    val portBound: StateFlow<Boolean> = _portBound.asStateFlow()

    private var port: NormalWorkspacePort? = null
    private var eventsJob: Job? = null

    /** 装配层注入端口（null 复位骨架占位）；重复注入同一实例忽略。 */
    fun bindPort(newPort: NormalWorkspacePort?) {
        if (port === newPort) return
        port = newPort
        eventsJob?.cancel()
        eventsJob = null
        _portBound.value = newPort != null
        _notice.value = null
        _busyId.value = null
        _pendingRemove.value = null
        _pendingRename.value = null
        _createName.value = ""
        if (newPort == null) {
            _entries.value = emptyList()
            return
        }
        eventsJob = viewModelScope.launch {
            newPort.workspaceEvents.collect { text ->
                _notice.value = text
                refreshInternal(newPort)
            }
        }
        viewModelScope.launch { refreshInternal(newPort) }
    }

    /** 进入页面时刷新（幂等）。 */
    fun refresh() {
        val port = this.port ?: return
        viewModelScope.launch { refreshInternal(port) }
    }

    /** 新建名称输入变化。 */
    fun onCreateNameChange(text: String) {
        _createName.value = text
    }

    /** 新建内置工作区：空白名称本地拦截，结果交给装配层校验重名。 */
    fun createInternal() {
        val port = this.port
        if (port == null) {
            _notice.value = "工作区入口未接线：请确认 Normal 窗口由正式装配启动。"
            return
        }
        if (_busyId.value != null) return
        val name = _createName.value.trim()
        if (name.isEmpty()) {
            _notice.value = "请输入工作区名称。"
            return
        }
        runBusy(port, key = NEW_KEY, onSuccess = { _createName.value = "" }) {
            port.createWorkspace(name)
        }
    }

    /** 切换到指定工作区。 */
    fun select(entry: NormalWorkspaceEntry) {
        val port = this.port ?: return
        if (entry.current || _busyId.value != null) return
        runBusy(port, key = entry.id) { port.selectWorkspace(entry.id) }
    }

    /** 请求删除：当前工作区与内置默认工作区直接拒绝，其余进入二次确认。 */
    fun requestRemove(entry: NormalWorkspaceEntry) {
        if (entry.current) {
            _notice.value = "当前工作区不可删除：请先切换到其他工作区。"
            return
        }
        if (entry.id == BUILT_IN_ID) {
            _notice.value = "内置默认工作区不可删除。"
            return
        }
        if (_busyId.value != null) return
        _pendingRemove.value = entry
    }

    /** 取消删除确认。 */
    fun cancelRemove() {
        _pendingRemove.value = null
    }

    /** 确认删除：有会话的工作区自动携带 force（对话框已提示会话会一并删除）。 */
    fun confirmRemove() {
        val entry = _pendingRemove.value ?: return
        _pendingRemove.value = null
        val port = this.port ?: return
        if (_busyId.value != null) return
        runBusy(port, key = entry.id) { port.removeWorkspace(entry.id, force = entry.sessionCount > 0) }
    }

    /** 打开重命名对话框。 */
    fun requestRename(entry: NormalWorkspaceEntry) {
        if (_busyId.value != null) return
        _pendingRename.value = entry
        _renameName.value = entry.name
    }

    /** 重命名输入变化。 */
    fun onRenameNameChange(text: String) {
        _renameName.value = text
    }

    /** 取消重命名。 */
    fun cancelRename() {
        _pendingRename.value = null
    }

    /** 确认重命名：空白名称本地拦截。 */
    fun confirmRename() {
        val entry = _pendingRename.value ?: return
        _pendingRename.value = null
        val name = _renameName.value.trim()
        if (name.isEmpty()) {
            _notice.value = "工作区名称不能为空。"
            return
        }
        val port = this.port ?: return
        if (_busyId.value != null) return
        runBusy(port, key = entry.id) { port.renameWorkspace(entry.id, name) }
    }

    /** 清除当前提示（页面消费 Snackbar 后调用）。 */
    fun consumeNotice() {
        _notice.value = null
    }

    private fun runBusy(
        port: NormalWorkspacePort,
        key: String,
        onSuccess: () -> Unit = {},
        block: suspend () -> String,
    ) {
        _busyId.value = key
        viewModelScope.launch {
            try {
                val text = runCatching { block() }
                    .getOrElse { "工作区操作失败：" + (it.message ?: it::class.simpleName) }
                _notice.value = text
                onSuccess()
                refreshInternal(port)
            } finally {
                _busyId.value = null
            }
        }
    }

    private suspend fun refreshInternal(port: NormalWorkspacePort) {
        val entries = runCatching { port.workspaceEntries() }.getOrElse { error ->
            _notice.value = "工作区清单读取失败：" + (error.message ?: error::class.simpleName)
            return
        }
        _entries.value = entries
    }

    companion object {
        /** 新建动作的忙碌键（不对应任何工作区 id）。 */
        const val NEW_KEY = "new"

        /** 内置默认工作区 id（core-harness WorkspaceStore.DEFAULT_ID 的 UI 侧常量）。 */
        const val BUILT_IN_ID = "default"
    }
}
