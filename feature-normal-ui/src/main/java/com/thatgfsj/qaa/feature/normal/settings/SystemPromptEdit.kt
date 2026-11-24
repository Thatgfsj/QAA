package com.thatgfsj.qaa.feature.normal.settings

import com.thatgfsj.qaa.core.harness.PromptAssembler

/**
 * 设置页系统提示词编辑的纯逻辑（#105）：与 Compose 解耦，供渲染与 JVM 单测共用。
 *
 * 语义与终端 /system 对齐：空白草稿等价于默认（保存动作由调用方转成恢复默认），
 * 首尾空白在保存前归一，未保存改动只影响草稿、不影响下一轮组装。
 */

/** 来源标签：与默认提示词一致时显示「默认」，否则「自定义」。 */
internal fun systemPromptSourceLabel(saved: String): String =
    if (normalizeSystemPromptDraft(saved) == PromptAssembler.DEFAULT_SYSTEM_PROMPT) {
        "来源：默认"
    } else {
        "来源：自定义"
    }

/** 草稿是否含未保存改动：空白与默认等价，比较前先归一。 */
internal fun isSystemPromptDirty(draft: String, saved: String): Boolean =
    normalizeSystemPromptDraft(draft) != normalizeSystemPromptDraft(saved)

/** 保存前归一化：去首尾空白；空白视为默认提示词。 */
internal fun normalizeSystemPromptDraft(draft: String): String =
    draft.trim().ifEmpty { PromptAssembler.DEFAULT_SYSTEM_PROMPT }

/** 设置页状态行：来源 + 保存值字数 + 未保存改动标记。 */
internal fun systemPromptStatusText(saved: String, draft: String): String = buildString {
    append(systemPromptSourceLabel(saved))
    append("；").append(normalizeSystemPromptDraft(saved).length).append(" 字")
    if (isSystemPromptDirty(draft, saved)) append("；有未保存改动")
}

/** 工具协议提示：删除工具协议段落会让 agent 模式工具调用失效，保存前必须可见。 */
internal const val SYSTEM_PROMPT_PROTOCOL_WARNING =
    "提示词每轮组装，保存后下一轮生效（无需重载模型）。删除工具协议段落会导致 agent 模式工具调用失效，" +
        "建议保留模板或点「恢复默认」。"
