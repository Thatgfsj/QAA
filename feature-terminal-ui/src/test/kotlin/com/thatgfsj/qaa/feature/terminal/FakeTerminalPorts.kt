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

    val turns = mutableListOf<String>()
    val steers = mutableListOf<String>()
    val followUps = mutableListOf<String>()
    val selectedModels = mutableListOf<String>()
    val backends = mutableListOf<BackendRequest>()

    var treeText = "● n-000001 [user] 你好"
    var forkText = "已从节点 n-000001 分叉"
    var compactText = "已压缩上下文：1 段"
    var skillsText = "技能注册表：注册 0 个"
    var statusText = "模型清单：1 项"

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

    override suspend fun selectModel(token: String): String =
        "模型已载入：$token".also { selectedModels += token }

    override suspend fun tree(): String = treeText

    override suspend fun fork(nodeId: String?): String =
        forkText + (nodeId?.let { "（节点 $it）" } ?: "")

    override suspend fun compact(): String = compactText

    override suspend fun skills(): String = skillsText

    override suspend fun statusExtra(): String = statusText
}

/** 模型清单替身（序号与名称沿用 LocalModelEntry）。 */
class FakeTerminalModelList(private val fixed: List<LocalModelEntry>) : TerminalModelListPort {

    override suspend fun entries(): List<LocalModelEntry> = fixed
}

/** 拉取替身：一次性发出脚本内的事件序列。 */
class FakeTerminalModelPull(private val events: List<TerminalPullEvent>) : TerminalModelPullPort {

    override fun pull(url: String): Flow<TerminalPullEvent> = flowOf(*events.toTypedArray())
}
