package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.ToolCall
import com.thatgfsj.qaa.core.harness.ToolExecutorGateway
import com.thatgfsj.qaa.core.harness.ToolOutcome
import com.thatgfsj.qaa.core.harness.ToolOutcomeStatus
import com.thatgfsj.qaa.core.tools.DrawImageTool
import com.thatgfsj.qaa.core.tools.EditTool
import com.thatgfsj.qaa.core.tools.ReadTool
import com.thatgfsj.qaa.core.tools.SandboxFs
import com.thatgfsj.qaa.core.tools.ToolContext
import com.thatgfsj.qaa.core.tools.ToolExecutor
import com.thatgfsj.qaa.core.tools.ToolLimits
import com.thatgfsj.qaa.core.tools.ToolResult
import com.thatgfsj.qaa.core.tools.ToolResultStatus
import com.thatgfsj.qaa.core.tools.WriteTool

/**
 * 工具执行端口适配（HarnessPorts.ToolExecutorGateway ⇄ core-tools 四原语工具）。
 *
 * 每次执行按 Agent Loop 传入的 [ToolCall] 与工具步序号构造 [ToolContext]：
 * sessionId 固定当前会话、sandbox 为 App 私有目录默认授权根（外部目录可动态挂载）、
 * limits 取默认截断预算。
 * 确认需求由工具自身声明（read=false；write/edit/draw_image=true），Loop 据此走确认门。
 */
class ToolRouterGateway(
    private val sandbox: SandboxFs,
    private val sessionId: String,
    private val limits: ToolLimits = ToolLimits(),
    private val executors: Map<String, ToolExecutor> = emptyMap(),
) : ToolExecutorGateway {

    override suspend fun requiresConfirmation(toolName: String): Boolean =
        executors[toolName]?.requiresConfirmation ?: false

    override suspend fun execute(call: ToolCall, stepIndex: Int): ToolOutcome {
        val executor = executors[call.tool]
            ?: return ToolOutcome(
                status = ToolOutcomeStatus.FAILURE,
                output = "未知工具：${call.tool}。可用工具：read / write / edit / draw_image。",
                errorCode = ERROR_UNKNOWN_TOOL,
            )
        return executor.execute(call.argsJson, contextOf(stepIndex)).toOutcome()
    }

    /** 按步序号构造工具上下文（sessionId / sandbox / limits 由装配层固定）。 */
    internal fun contextOf(stepIndex: Int): ToolContext = object : ToolContext {
        override val sessionId: String = this@ToolRouterGateway.sessionId
        override val stepIndex: Int = stepIndex
        override val sandbox: SandboxFs = this@ToolRouterGateway.sandbox
        override val limits: ToolLimits = this@ToolRouterGateway.limits
    }

    companion object {

        /** 工具白名单外的调用（解析器已过滤，兜底用）。 */
        const val ERROR_UNKNOWN_TOOL = "E_UNKNOWN_TOOL"

        /** 四原语工具默认装配；draw_image 的画图引擎本期为 null（Phase 3 门禁后接入）。 */
        fun defaultExecutors(drawImage: DrawImageTool): Map<String, ToolExecutor> = linkedMapOf(
            ReadTool.NAME to ReadTool(),
            WriteTool.NAME to WriteTool(),
            EditTool.NAME to EditTool(),
            drawImage.name to drawImage,
        )
    }
}

/** 工具结果 → Loop 侧结果（PENDING 为 UI 中间态，不应抵达执行端口）。 */
internal fun ToolResult.toOutcome(): ToolOutcome = ToolOutcome(
    status = when (status) {
        ToolResultStatus.SUCCESS -> ToolOutcomeStatus.SUCCESS
        ToolResultStatus.FAILURE -> ToolOutcomeStatus.FAILURE
        ToolResultStatus.DENIED -> ToolOutcomeStatus.DENIED
        ToolResultStatus.PENDING -> ToolOutcomeStatus.FAILURE
    },
    output = output,
    errorCode = errorCode,
    durationMs = durationMs,
)
