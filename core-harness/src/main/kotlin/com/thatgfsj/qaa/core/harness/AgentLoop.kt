package com.thatgfsj.qaa.core.harness

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
) {

    /**
     * 运行一个用户轮次。终止条件四类（设计文档 1.3）：
     * 无工具调用 / 步数预算耗尽收尾 / 解析重试耗尽降级 / 工具步边界用户打断。
     *
     * 打断路径：当前工具步完成即停止剩余步，转向文本以 tags=[steer] 入树，
     * 由外层作为新 user 轮次驱动（SteerQueue 议题接线时替换 interruptCheck 来源）。
     */
    suspend fun runTurn(userText: String) {
        recorder.appendUser(userText)
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
     * [stepIndex] 为当前工具步序号；执行耗时在 Loop 侧计时后写入 [ToolOutcome.durationMs]。
     */
    private suspend fun executeWithConfirmation(call: ToolCall, stepIndex: Int): ToolOutcome {
        val needConfirm = !config.yoloMode && tools.requiresConfirmation(call.tool)
        if (!needConfirm) return executeMeasured(call, stepIndex)
        return when (gate.awaitConfirmation(call)) {
            ConfirmationDecision.ALLOW -> executeMeasured(call, stepIndex)
            ConfirmationDecision.DENY -> ToolOutcome(
                status = ToolOutcomeStatus.DENIED,
                output = DENIED_REPLY,
                durationMs = 0,
            )
        }
    }

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
