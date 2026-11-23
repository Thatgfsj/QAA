package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/**
 * Agent Loop 内核（设计文档第 1 章）：用户输入 → 会话记录 → 四段式组装 →
 * 生成 → 工具调用解析 → 确认门 → 执行 → 结果回注，直至满足终止条件。
 *
 * 无死循环双重封顶（设计文档 1.2）：
 * - 工具步硬顶 [AgentConfig.maxToolSteps]（Deny 同样计步）；
 * - 连续解析重试上限 [PARSE_RETRY_LIMIT]；
 * 任一路径触发后禁用工具解析、文本收尾一次后结束。
 */
class AgentLoop(
    private val config: AgentConfig = AgentConfig(),
    private val engine: LlmEngineGateway,
    private val tools: ToolExecutorGateway,
    private val gate: ConfirmationGate,
    private val recorder: TurnRecorder,
    private val parser: ToolCallParser = ToolCallParser(),
    private val assembler: PromptAssembler = PromptAssembler(),
    private val interruptCheck: (suspend () -> String?)? = null,
    private val confirmationInterrupt: ConfirmationInterrupt? = null,
) {

    /** 运行一个用户轮次（user 节点不带标签）。 */
    suspend fun runTurn(userText: String) = runTurn(userText, emptyList())

    /**
     * 运行一个用户轮次，user 节点携带 [tags]（转向注入用 tags=[AgentLoop.TAG_STEER]）。
     * 终止条件四类（设计文档 1.3）：无工具调用 / 步数预算耗尽收尾 / 解析重试耗尽降级 /
     * 工具步边界用户打断；打断后由装配层据 [TurnAction.ResumeAfterSteer] 调用 [resumeTurn]。
     */
    suspend fun runTurn(userText: String, tags: List<String>) = runLoop(userText, tags)

    /**
     * 续跑生成而不追加 user 节点：工具步边界转向时转向文本已由本类以 tags=[steer]
     * 入树，装配层据 [TurnAction.ResumeAfterSteer] 调用本方法自当前上下文重新生成
     * （设计文档 7.1）。
     */
    suspend fun resumeTurn() = runLoop(null, emptyList())

    private suspend fun runLoop(userText: String?, tags: List<String>) {
        if (userText != null) recorder.appendUser(userText, tags)
        var step = 0
        var parseFailures = 0
        var toolsDisabled = false
        var notice: String? = null

        while (true) {
            val messages = assembler.assemble(recorder.snapshot(), notice = notice)
            val output = engine.streamGenerate(messages)

            if (toolsDisabled) {
                // 收尾/降级路径：末次生成不做工具解析，文本落树后结束。
                recorder.appendAssistant(
                    output.text,
                    parseRetries = parseFailures,
                    metrics = output.metrics,
                    stepIndex = step,
                )
                return
            }

            when (val parsed = parser.parse(output.text)) {
                ParseOutcome.NoToolCall -> {
                    recorder.appendAssistant(
                        output.text,
                        metrics = output.metrics,
                        stepIndex = step,
                    )
                    return
                }

                is ParseOutcome.Invalid -> {
                    parseFailures++
                    recorder.appendAssistant(
                        output.text,
                        parseRetries = parseFailures,
                        metrics = output.metrics,
                        stepIndex = step,
                    )
                    if (parseFailures > PARSE_RETRY_LIMIT) {
                        // 设计文档 4.3：注入一次性降级提示并禁用工具。
                        notice = PromptAssembler.DEMOTION_NOTICE
                        toolsDisabled = true
                    } else {
                        // 设计文档 4.3/4.4：回注原因、白名单、模板与原文片段，供模型自纠。
                        recorder.appendSystemNotice(
                            PromptAssembler.correctionNotice(output.text, parsed.reason)
                        )
                    }
                }

                is ParseOutcome.Calls -> {
                    // 设计文档 4.3：解析重试按“连续”计数，合法调用落地即清零。
                    parseFailures = 0
                    recorder.appendAssistant(
                        output.text,
                        toolCalls = parsed.calls,
                        metrics = output.metrics,
                        stepIndex = step,
                    )
                    for (call in parsed.calls) {
                        // 设计文档 1.2/4.2：数组展开后逐调用检查硬顶，一次响应携带多个
                        // 调用也不得突破 maxToolSteps；超出预算的调用不执行、不落 tool 节点。
                        if (step >= config.maxToolSteps) break
                        val stepIndex = step
                        val outcome = executeWithConfirmation(call, stepIndex)
                        recorder.appendTool(call, outcome, stepIndex)
                        step++
                        val steerText = interruptCheck?.invoke()
                        if (steerText != null) {
                            recorder.appendUser(steerText, tags = listOf(TAG_STEER))
                            return
                        }
                    }
                    if (step >= config.maxToolSteps) {
                        // 设计文档 1.3：注入一次性收尾提示，末次生成禁用解析。
                        notice = PromptAssembler.STEP_BUDGET_NOTICE
                        toolsDisabled = true
                    }
                }
            }
        }
    }

    /**
     * 确认门（设计文档 3.6）：requiresConfirmation 且非 YOLO 时挂起等待 Allow/Deny；
     * Deny 生成 DENIED 固定话术回注。yoloMode 默认 false（AgentConfig 契约）。
     *
     * 确认挂起中 /steer（设计文档 7.1 与 L7）：转向意图优先于确认交互，等价 Deny
     * 当前挂起——工具不执行，转向文本留给工具步边界统一入树并终止本轮。
     *
     * [stepIndex] 为当前工具步序号；执行耗时在 Loop 侧计时后写入 [ToolOutcome.durationMs]。
     */
    private suspend fun executeWithConfirmation(call: ToolCall, stepIndex: Int): ToolOutcome {
        val needConfirm = !config.yoloMode && tools.requiresConfirmation(call.tool)
        if (!needConfirm) return executeMeasured(call, stepIndex)
        val interrupt = confirmationInterrupt
        // 挂起前 steer 已到：不进入确认门，直接等价 Deny。
        if (interrupt != null && interrupt.hasPendingSteer()) return deniedOutcome()
        val decision = if (interrupt == null) {
            gate.awaitConfirmation(call)
        } else {
            awaitConfirmationOrSteer(call, interrupt)
        }
        // 竞速中确认门先返回 Allow、steer 同时到达时，转向意图仍优先：不执行。
        if (decision == ConfirmationDecision.ALLOW && interrupt?.hasPendingSteer() == true) {
            return deniedOutcome()
        }
        return when (decision) {
            ConfirmationDecision.ALLOW -> executeMeasured(call, stepIndex)
            ConfirmationDecision.DENY -> deniedOutcome()
        }
    }

    /**
     * L7 确认挂起竞速：确认决策与 steer 到达谁先谁生效；steer 先到即按 Deny 语义返回。
     * 落败的一侧立即取消。真实确认门（NormalGate / TerminalUiGate）均基于可取消挂起，
     * 故取消后无悬挂协程；自定义 [ConfirmationGate] 实现需保持同样的可取消性。
     */
    private suspend fun awaitConfirmationOrSteer(
        call: ToolCall,
        interrupt: ConfirmationInterrupt,
    ): ConfirmationDecision = coroutineScope {
        val steer = async { interrupt.awaitSteer() }
        val decision = async { gate.awaitConfirmation(call) }
        val winner = select<ConfirmationDecision> {
            steer.onAwait { ConfirmationDecision.DENY }
            decision.onAwait { it }
        }
        steer.cancel()
        decision.cancel()
        winner
    }

    /** Deny 终态（用户拒绝或转向取代确认）。 */
    private fun deniedOutcome() = ToolOutcome(
        status = ToolOutcomeStatus.DENIED,
        output = DENIED_REPLY,
        durationMs = 0,
    )

    /** 执行工具并计时（设计文档 3.4/5.3：tool 节点携带 durationMs）。 */
    private suspend fun executeMeasured(call: ToolCall, stepIndex: Int): ToolOutcome {
        val startedNanos = System.nanoTime()
        val outcome = tools.execute(call, stepIndex)
        return outcome.copy(
            durationMs = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI,
        )
    }

    companion object {
        /** 连续解析失败重试上限（设计文档 4.3，内部常量，非 AgentConfig 字段）。 */
        const val PARSE_RETRY_LIMIT = 2
        const val TAG_STEER = "steer"

        /** Deny 固定话术（设计文档 3.4）。 */
        const val DENIED_REPLY = "已拒绝执行该工具，建议改用纯文字回答或调整方案。"

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
