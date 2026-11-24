package com.thatgfsj.qaa.feature.terminal

import com.thatgfsj.qaa.core.harness.ConfirmationDecision
import com.thatgfsj.qaa.core.harness.ConfirmationGate
import com.thatgfsj.qaa.core.harness.ToolCall
import com.thatgfsj.qaa.core.harness.summarizeToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [TOOL] 确认区宿主抽象：确认门只依赖这一个最小接口，便于用 fake 宿主做 JVM 单测
 *（真实 UI 交互由模拟器手测，归冒烟验收议题 #11）。
 */
interface TerminalToolConfirmHost {

    /** 整页快照；确认门只读其中的 [TerminalUiState.toolConfirm] 与 [TerminalUiState.confirmPolicy]。 */
    val uiState: StateFlow<TerminalUiState>

    /** 推送待确认项（工具名 + 参数摘要），同一时刻仅保留最新一条。 */
    fun requestToolConfirmation(description: String)

    /** 挂起被取消时收起待确认项，避免遗留无人决策的按钮。 */
    fun dismissToolConfirmation()
}

/**
 * Terminal 端确认门（设计文档 3.6）：把 [ToolCall] 转成展示文案推入 [TOOL] 确认区，
 * 挂起等待 Allow / Deny；决策由 [TerminalToolConfirmHost] 的按钮回调写入。
 *
 * 同一时刻只允许一条挂起：Mutex 串行化，防止连续工具调用把确认区顶掉；
 * 取消（轮次中断 / 页面销毁）时收起确认区并向上传播 CancellationException，
 * 由 AgentLoop 放弃当前工具执行。
 *
 * 会话级记忆（#93）：快照里的 [TerminalUiState.confirmPolicy] 为自动放行时不再挂起，
 * 直接放行，并经 [onAutoDecision] 通知装配层（日志 / 对话区提示）。
 */
class TerminalUiGate(
    private val host: TerminalToolConfirmHost,
    private val onAutoDecision: (description: String, policy: ToolConfirmPolicy) -> Unit = { _, _ -> },
) : ConfirmationGate {

    private val mutex = Mutex()

    override suspend fun awaitConfirmation(call: ToolCall): ConfirmationDecision {
        val description = summarizeToolCall(call)
        val policy = host.uiState.value.confirmPolicy
        if (policy.autoDecision() == true) {
            onAutoDecision(description, policy)
            return ConfirmationDecision.ALLOW
        }
        return mutex.withLock {
            host.requestToolConfirmation(description)
            val pending = host.uiState.value.toolConfirm as? ToolConfirmState.Pending
                ?: error("确认区未进入待确认状态：${call.tool}")
            try {
                val resolved = host.uiState
                    .map { it.toolConfirm }
                    .first { it is ToolConfirmState.Resolved && it.requestId == pending.requestId }
                    as ToolConfirmState.Resolved
                if (resolved.allowed) ConfirmationDecision.ALLOW else ConfirmationDecision.DENY
            } catch (cancelled: CancellationException) {
                host.dismissToolConfirmation()
                throw cancelled
            }
        }
    }
}
