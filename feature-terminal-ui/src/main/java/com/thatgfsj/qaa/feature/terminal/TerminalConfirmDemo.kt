package com.thatgfsj.qaa.feature.terminal

import com.thatgfsj.qaa.core.harness.ConfirmationDecision
import com.thatgfsj.qaa.core.harness.ConfirmationGate
import com.thatgfsj.qaa.core.harness.ToolCall

/**
 * 会话态确认演示（Agent Loop 装配前的人工验收入口）。
 *
 * 会话态输入 [TRIGGER] 即挂起一次模拟的 write 调用，用于验收 Allow / Deny 按钮与取消清理；
 * 真实工具调用由 AgentLoop 经 [TerminalUiGate] 触发（接线点见 :app 的 TerminalHost，
 * 完整链路归冒烟验收议题 #11）。
 */
object TerminalConfirmDemo {

    /** 会话态触发词：与普通 prompt 区分，避免误伤真实输入。 */
    const val TRIGGER = "确认演示"

    private val DEMO_CALL = ToolCall(
        "write",
        """{"path":"demo.txt","content":"QAA 工具确认流演示"}""",
    )

    /** 执行一次演示确认；挂起被取消时直接向上抛 CancellationException。 */
    suspend fun run(gate: ConfirmationGate, viewModel: TerminalViewModel) {
        viewModel.appendOutput("【演示】模拟 write 工具调用，请在确认区选择 Allow / Deny。")
        when (gate.awaitConfirmation(DEMO_CALL)) {
            ConfirmationDecision.ALLOW ->
                viewModel.appendOutput("【演示】已允许；工具执行体未接，本次不写文件。")
            ConfirmationDecision.DENY ->
                viewModel.appendOutput("【演示】已拒绝；真实链路会把拒绝结果回注模型。")
        }
    }
}
