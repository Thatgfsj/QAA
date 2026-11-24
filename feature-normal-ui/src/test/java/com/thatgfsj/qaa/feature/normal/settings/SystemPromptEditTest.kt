package com.thatgfsj.qaa.feature.normal.settings

import com.thatgfsj.qaa.core.harness.PromptAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置页系统提示词纯逻辑单测（#105）：来源标签、草稿归一、未保存判定与状态行。 */
class SystemPromptEditTest {

    @Test
    fun 来源标签_默认与自定义() {
        assertEquals("来源：默认", systemPromptSourceLabel(PromptAssembler.DEFAULT_SYSTEM_PROMPT))
        assertEquals("来源：默认", systemPromptSourceLabel("  ${PromptAssembler.DEFAULT_SYSTEM_PROMPT} "))
        assertEquals("来源：自定义", systemPromptSourceLabel("自定义提示词"))
    }

    @Test
    fun 空白草稿_归一为默认提示词() {
        assertEquals(PromptAssembler.DEFAULT_SYSTEM_PROMPT, normalizeSystemPromptDraft("   "))
        assertEquals("自定义", normalizeSystemPromptDraft("  自定义  "))
    }

    @Test
    fun 未保存改动判定_忽略首尾空白() {
        assertFalse(
            isSystemPromptDirty(PromptAssembler.DEFAULT_SYSTEM_PROMPT, PromptAssembler.DEFAULT_SYSTEM_PROMPT),
        )
        assertTrue(isSystemPromptDirty("自定义", PromptAssembler.DEFAULT_SYSTEM_PROMPT))
        assertFalse(isSystemPromptDirty(" 自定义 ", "自定义"))
        // 从自定义状态清空草稿等价于恢复默认，属于未保存改动。
        assertTrue(isSystemPromptDirty("   ", "自定义"))
    }

    @Test
    fun 状态行_含来源字数与未保存标记() {
        val saved = "你是助手。"

        val dirty = systemPromptStatusText(saved, "你是新助手。")

        assertTrue(dirty.contains("来源：自定义"))
        assertTrue(dirty.contains("字"))
        assertTrue(dirty.contains("有未保存改动"))
        assertFalse(systemPromptStatusText(saved, saved).contains("有未保存改动"))
    }

    @Test
    fun 工具协议提示_明确失效风险与恢复入口() {
        assertTrue(SYSTEM_PROMPT_PROTOCOL_WARNING.contains("工具调用失效"))
        assertTrue(SYSTEM_PROMPT_PROTOCOL_WARNING.contains("恢复默认"))
        assertTrue(SYSTEM_PROMPT_PROTOCOL_WARNING.contains("下一轮生效"))
    }
}
