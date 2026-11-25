package com.thatgfsj.qaa.feature.normal.model

/**
 * Normal 前端 UI 层数据模型（Issue #8 骨架期）。
 * 字段随真实接线议题（#5/#6/#12）逐步对齐，暂保持最小形状。
 */

/** 会话消息角色。 */
enum class ChatRole { USER, ASSISTANT }

/** 一条会话消息；流式回复期间 [content] 逐 token 增长。 */
data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val content: String,
)

/** 模型目录条目；[ordinal] 为真实清单序号（0 表示骨架占位数据）。 */
data class ModelInfo(
    val id: String,
    val name: String,
    val quantization: String,
    val sizeBytes: Long?,
    val source: String,
    val loaded: Boolean,
    val ordinal: Int = 0,
    /** 兼容性提示（#102）：不受支持 / 未知档位的中文说明；null 无需提示。 */
    val compatibilityNote: String? = null,
)

/**
 * 推理状态：接口形状为 Issue #5（后端降级链）接线预留，
 * 届时由真实数据源替换 fake 实现（见 status/InferenceStatusSource）。
 */
data class InferenceStatus(
    val modelName: String,
    val quantization: String,
    /** CHAT / AGENT */
    val agentMode: String,
    /** NPU / HYBRID / CPU */
    val backend: String,
)
