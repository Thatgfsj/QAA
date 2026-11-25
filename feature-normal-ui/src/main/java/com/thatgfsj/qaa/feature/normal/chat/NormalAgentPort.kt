package com.thatgfsj.qaa.feature.normal.chat

import kotlinx.coroutines.flow.Flow

/**
 * Agent 轮次端口（Normal 端最小接线）：由 :app 装配层用真实运行链实现并注入。
 *
 * Normal 端没有命令层，输入即一轮对话；工具确认由装配层注入的确认门在运行中自然挂起。
 * null 表示未接线（骨架期），[ChatViewModel] 退回模块内 fake 流式回复。
 *
 * #127 新增能力（默认 null，旧实现不受影响）：
 * - [chatSegments]：结构化轮次事件流（工具卡片 / 确认条 / 错误段）。
 */
interface NormalAgentPort {

    /** 运行一轮并返回助手可见文本（工具调用 JSON 已由适配层过滤）。 */
    suspend fun ask(text: String): String

    /**
     * 生成中的 token 片段流（可选）：装配层接真实引擎后，会话页边生成边渲染；
     * null 表示无流式，仅在 [ask] 返回后整段上屏。
     */
    val assistantTokens: Flow<String>? get() = null

    /** 会话页指标流（可选）：订阅装配层真实 metrics；null 表示无指标来源（显示 -- 占位）。 */
    val chatMetrics: Flow<ChatMetrics>? get() = null

    /**
     * 结构化轮次事件流（可选）：每个元素都是一次「同 id 覆盖」的段状态更新，
     * 装配层负责把 harness 工具步 / 确认挂起 / 轮次错误映射成 [ChatSegment]。
     */
    val chatSegments: Flow<ChatSegment>? get() = null
}
