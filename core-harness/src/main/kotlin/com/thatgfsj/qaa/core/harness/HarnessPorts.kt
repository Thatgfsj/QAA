package com.thatgfsj.qaa.core.harness

/**
 * Harness 端口集合（接口形状按 docs/design/harness-core-design.md 第 1/3 章）。
 *
 * 端口方向：接口定义在本模块（纯 JVM），实现由 :core-inference / :core-tools
 * 在 Android 侧提供，:app 装配层注入（依赖倒置，docs/ARCHITECTURE.md 第 2 节注记）。
 */

/** 组装后的提示消息单元（四段式组装产物，稳定前缀在前）。 */
enum class PromptRole { SYSTEM, USER, ASSISTANT, TOOL }

data class PromptMessage(val role: PromptRole, val content: String)

/** 单个工具调用（解析产物；argsJson 保持原始 JSON 字符串，参数校验归执行侧）。 */
data class ToolCall(val tool: String, val argsJson: String)

/** 引擎生成指标（backend 为实际命中后端字符串，引用 :core-inference 契约值）。 */
data class GenerationMetrics(
    val backend: String? = null,
    val ttftMs: Long? = null,
    val tokensPerSec: Float? = null,
)

data class GenerationOutput(
    val text: String,
    val metrics: GenerationMetrics? = null,
)

/** 推理引擎端口：实现侧接 GenieX LlmEngine 与后端降级链（Issue #5/#6）。 */
interface LlmEngineGateway {
    suspend fun streamGenerate(
        messages: List<PromptMessage>,
        onToken: (String) -> Unit = {},
    ): GenerationOutput
}

/** 工具执行结果（对齐设计文档 3.4 的 SUCCESS/FAILURE；DENIED 由 Loop 在 Deny 时生成）。 */
enum class ToolOutcomeStatus { SUCCESS, FAILURE, DENIED }

data class ToolOutcome(
    val status: ToolOutcomeStatus,
    val output: String,
    val errorCode: String? = null,
)

/** 工具执行端口：实现侧为 :core-tools 统一路由（Issue #12 工具层议题）。 */
interface ToolExecutorGateway {
    /** 是否需要用户确认（read=false；write/edit/draw_image=true，设计文档 3.3）。 */
    suspend fun requiresConfirmation(toolName: String): Boolean
    suspend fun execute(call: ToolCall): ToolOutcome
}

/** 确认决策。 */
enum class ConfirmationDecision { ALLOW, DENY }

/** 确认门端口：UI 层实现挂起交互（Normal 卡片 / Terminal 行内 y/n）。 */
interface ConfirmationGate {
    suspend fun awaitConfirmation(call: ToolCall): ConfirmationDecision
}

/** 会话节点内存视图（落盘 schema 由 TreeSessionStore 议题按设计文档 5.2 对齐）。 */
enum class TurnRole { USER, ASSISTANT, TOOL }

data class TurnNode(
    val role: TurnRole,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val tags: List<String> = emptyList(),
    val parseRetries: Int = 0,
)

/** 会话记录端口：实现侧为 TreeSessionStore（JSONL append-only，设计文档第 5 章）。 */
interface TurnRecorder {
    suspend fun appendUser(text: String, tags: List<String> = emptyList())
    suspend fun appendAssistant(
        text: String,
        toolCalls: List<ToolCall> = emptyList(),
        parseRetries: Int = 0,
    )
    suspend fun appendTool(call: ToolCall, outcome: ToolOutcome)
    /** 解析纠错等系统级回注（role=TOOL 消息，不属任何工具步）。 */
    suspend fun appendSystemNotice(text: String)
    suspend fun snapshot(): List<TurnNode>
}
