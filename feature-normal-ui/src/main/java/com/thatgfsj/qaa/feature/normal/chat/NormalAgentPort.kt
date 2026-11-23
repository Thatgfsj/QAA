package com.thatgfsj.qaa.feature.normal.chat

/**
 * Agent 轮次端口（Normal 端最小接线）：由 :app 装配层用真实运行链实现并注入。
 *
 * Normal 端没有命令层，输入即一轮对话；工具确认由装配层注入的确认门在运行中自然挂起。
 * null 表示未接线（骨架期），[ChatViewModel] 退回模块内 fake 流式回复。
 */
fun interface NormalAgentPort {

    /** 运行一轮并返回助手可见文本（工具调用 JSON 已由适配层过滤）。 */
    suspend fun ask(text: String): String
}
