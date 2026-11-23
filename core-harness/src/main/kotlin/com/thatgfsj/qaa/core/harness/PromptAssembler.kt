package com.thatgfsj.qaa.core.harness

/**
 * 提示组装器（设计文档 1.4 四段式，稳定前缀在前）：
 * 系统段 → skills 描述段 → 摘要段 → 会话历史；收尾/降级提示作为末尾动态段。
 *
 * 系统段与 skills 段合称稳定前缀，一次会话内字节级不变（保护 NPU Prompt Cache 命中）。
 * skills 段由 SkillRegistry 议题注入；摘要段由 ContextCompactor 议题注入；
 * 保护窗口裁剪由压缩议题替换全量历史时生效，本类只固定段序与稳定前缀纪律。
 */
class PromptAssembler(
    private val systemSection: String = DEFAULT_SYSTEM_PROMPT,
    private val skillsSection: String = "",
) {

    fun assemble(
        history: List<TurnNode>,
        summaries: List<String> = emptyList(),
        notice: String? = null,
    ): List<PromptMessage> {
        val messages = mutableListOf(PromptMessage(PromptRole.SYSTEM, systemSection))
        if (skillsSection.isNotBlank()) {
            messages += PromptMessage(PromptRole.SYSTEM, skillsSection)
        }
        summaries.forEach { messages += PromptMessage(PromptRole.SYSTEM, it) }
        history.forEach { node ->
            messages += PromptMessage(node.role.toPromptRole(), node.content)
        }
        if (notice != null) {
            messages += PromptMessage(PromptRole.SYSTEM, notice)
        }
        return messages
    }

    private fun TurnRole.toPromptRole(): PromptRole = when (this) {
        TurnRole.USER -> PromptRole.USER
        TurnRole.ASSISTANT -> PromptRole.ASSISTANT
        TurnRole.TOOL -> PromptRole.TOOL
    }

    companion object {
        /** 极简系统段（设计文档 1.4/4.1：身份 + 工具模板 + 确认机制 + 中文作答约定）。 */
        val DEFAULT_SYSTEM_PROMPT = """
            你是 QAA 手机端 AI 助手。
            你可直接用中文回答用户。
            若需要使用工具，只输出一个 JSON 对象，不要附加其他文字：
              {"tool": "工具名", "args": { ...参数... }}
            可用工具：read / write / edit / draw_image。
            涉及文件或图片变更的工具会先请求用户确认。
        """.trimIndent()

        /** 步数预算耗尽的一次性收尾提示（设计文档 1.3）。 */
        const val STEP_BUDGET_NOTICE =
            "工具步数预算已用尽。请基于已有结果直接作答，不要再调用工具。"

        /** 解析重试耗尽后的降级提示（设计文档 4.3 原文）。 */
        const val DEMOTION_NOTICE =
            "工具调用解析多次失败，请不要再尝试调用工具，直接用文字回答用户。"

        /** 解析失败后的纠错提示（附原输出片段与模板，设计文档 4.3）。 */
        fun correctionNotice(rawOutput: String): String =
            "上一条回复不是合法的工具调用 JSON（原文开头片段：" + rawOutput.take(60) +
                "）。若需要使用工具，只输出一个 JSON 对象：" +
                "{\"tool\": \"工具名\", \"args\": { ... }}" +
                "；否则直接用中文回答。"
    }
}
