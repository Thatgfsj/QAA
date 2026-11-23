package com.thatgfsj.qaa.feature.normal.confirm

import com.thatgfsj.qaa.core.harness.ConfirmationDecision
import com.thatgfsj.qaa.core.harness.ConfirmationGate
import com.thatgfsj.qaa.core.harness.ToolCall
import com.thatgfsj.qaa.core.harness.summarizeToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 待确认请求：requestId 单调递增，供对话框显示与挂起恢复配对。 */
data class PendingToolConfirmation(val requestId: Long, val description: String)

/**
 * Normal 端确认门（设计文档 3.6）：挂起等待中文确认对话框的决策。
 *
 * 状态机归本类所有：[pending] 非空即展示对话框，用户点「允许 / 拒绝」由 [allow] /
 * [deny] 完成挂起；同一时刻只允许一条挂起（Mutex 串行化）；挂起被取消时清空
 * [pending] 并向上传播 CancellationException，由 AgentLoop 放弃当前工具执行。
 */
class NormalGate : ConfirmationGate {

    private data class ActiveRequest(
        val requestId: Long,
        val decision: CompletableDeferred<ConfirmationDecision>,
    )

    private val mutex = Mutex()
    private var nextRequestId = 1L
    private var active: ActiveRequest? = null

    private val _pending = MutableStateFlow<PendingToolConfirmation?>(null)

    /** 当前待确认请求；null 表示无挂起。 */
    val pending: StateFlow<PendingToolConfirmation?> = _pending.asStateFlow()

    override suspend fun awaitConfirmation(call: ToolCall): ConfirmationDecision =
        mutex.withLock {
            val request = PendingToolConfirmation(nextRequestId++, summarizeToolCall(call))
            val decision = CompletableDeferred<ConfirmationDecision>()
            active = ActiveRequest(request.requestId, decision)
            _pending.value = request
            try {
                decision.await()
            } finally {
                if (active?.requestId == request.requestId) active = null
                if (_pending.value?.requestId == request.requestId) _pending.value = null
            }
        }

    /** 用户点击「允许」。 */
    fun allow() = resolve(ConfirmationDecision.ALLOW)

    /** 用户点击「拒绝」。 */
    fun deny() = resolve(ConfirmationDecision.DENY)

    private fun resolve(decision: ConfirmationDecision) {
        val current = active ?: return
        active = null
        current.decision.complete(decision)
    }
}
