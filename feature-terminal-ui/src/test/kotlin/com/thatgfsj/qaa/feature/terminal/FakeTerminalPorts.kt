package com.thatgfsj.qaa.feature.terminal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** 命令执行体替身：记录收到的调用并按脚本返回中文回执。 */
class FakeTerminalAgentPort(
    initial: TerminalAgentState = TerminalAgentState.IDLE,
) : TerminalAgentPort {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<TerminalAgentState> = _state

    private val _modelLoad = MutableStateFlow<TerminalModelLoadState>(TerminalModelLoadState.Idle)
    override val modelLoad: StateFlow<TerminalModelLoadState> = _modelLoad

    /** 测试主动推送载入进度（状态流驱动，不依赖计时）。 */
    fun setModelLoad(next: TerminalModelLoadState) {
        _modelLoad.value = next
    }

    val turns = mutableListOf<String>()
    val steers = mutableListOf<String>()
    val followUps = mutableListOf<String>()
    val selectedModels = mutableListOf<String>()
    val backends = mutableListOf<BackendRequest>()

    var treeText = "● n-000001 [user] 你好"

    /** 非空时 tree 抛出该异常（#99：验证异常不再静默）。 */
    var treeError: Throwable? = null
    var forkText = "已从节点 n-000001 分叉"
    var compactText = "已压缩上下文：1 段"
    var skillsText = "技能注册表：注册 0 个"
    var exportText = "已导出当前分支轨迹：exports/session-main.jsonl（1 行）"
    val exportedPaths = mutableListOf<String?>()

    var logsExportText = "已导出 200 行日志 → exports/logs/log-20260101-000000-000.txt（缓冲共 200 行）"
    val exportedLogLines = mutableListOf<Int?>()
    var statusText = "模型清单：1 项"
    var newSessionText = "已新建会话：main-2"
    var newSessionCount = 0

    fun setState(next: TerminalAgentState) {
        _state.value = next
    }

    override suspend fun runTurn(text: String) {
        turns += text
    }

    override suspend fun steer(text: String): String = "已提交干预指令：$text".also { steers += text }

    override suspend fun followUp(text: String): String =
        "已加入 Follow-up 队列".also { followUps += text }

    override suspend fun lockBackend(request: BackendRequest): String =
        "已锁定请求后端：${request.displayName}".also { backends += request }

    /** 非空时覆盖 selectModel 回执（用于失败回显与静默路径测试）。 */
    var selectModelText: String? = null

    override suspend fun selectModel(token: String): String =
        (selectModelText ?: "模型已载入：$token").also { selectedModels += token }

    override suspend fun tree(): String = treeError?.let { throw it } ?: treeText

    override suspend fun fork(nodeId: String?): String =
        forkText + (nodeId?.let { "（节点 $it）" } ?: "")

    override suspend fun compact(): String = compactText

    override suspend fun skills(): String = skillsText

    override suspend fun exportTrace(path: String?): String =
        exportText.also { exportedPaths += path }

    override suspend fun exportLogs(lines: Int?): String =
        logsExportText.also { exportedLogLines += lines }

    override suspend fun statusExtra(): String = statusText

    // ---- #106 /ctx 上下文长度 ----
    var contextLengthText = "上下文：设置 32768（引擎 32768）→ 预算 31600 token\n" +
        "预计 KV 占用：≈ 4.5 GiB（f16 估算，依据 GGUF 头：32 层 × 8 KV heads × 128 维）\n" +
        "设备内存：可用 3.2 GiB / 总 15.4 GiB"
    var contextLengthSetText = "已保存上下文长度：20480 token（原 32768）；引擎尚未加载，下次载入模型时生效。"
    val contextLengths = mutableListOf<Int>()

    override suspend fun contextLength(): String = contextLengthText

    override suspend fun setContextLength(tokens: Int): String =
        contextLengthSetText.also { contextLengths += tokens }

    // ---- #105 /system 系统提示词 ----
    var systemPromptText = "系统提示词（来源：默认，5 字）\n你好"
    var systemPromptSetText = "已更新系统提示词（3 字，来源：自定义）；下一轮生效。"
    val systemPromptSets = mutableListOf<String?>()

    override suspend fun systemPrompt(): String = systemPromptText

    override suspend fun setSystemPrompt(text: String?): String =
        systemPromptSetText.also { systemPromptSets += text }

    override suspend fun newSession(): String = newSessionText.also { newSessionCount += 1 }

    /** model delete 回执与调用记录（#111）。 */
    var deleteModelText = "已删除模型：Qwen2.5-1.5B（释放约 12.3 MB），剩余清单 1 项。"
    val deletedModels = mutableListOf<Pair<Int, String>>()

    override suspend fun deleteModel(ordinal: Int, name: String): String =
        deleteModelText.also { deletedModels += ordinal to name }
}

/** 模型清单替身（序号与名称沿用 LocalModelEntry）。 */
class FakeTerminalModelList(private val fixed: List<LocalModelEntry>) : TerminalModelListPort {

    override suspend fun entries(): List<LocalModelEntry> = fixed
}

/** 拉取替身：一次性发出脚本内的事件序列。 */
class FakeTerminalModelPull(private val events: List<TerminalPullEvent>) : TerminalModelPullPort {

    override fun pull(url: String): Flow<TerminalPullEvent> = flowOf(*events.toTypedArray())
}
