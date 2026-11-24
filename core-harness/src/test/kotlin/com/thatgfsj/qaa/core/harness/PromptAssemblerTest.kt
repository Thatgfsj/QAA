package com.thatgfsj.qaa.core.harness

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PromptAssembler 系统段单测（#105）：默认段、运行期整体替换、段序与稳定前缀纪律。
 * 自定义系统提示词的持久化在 :app 的 SystemPromptStore；本类只锁定组装器契约。
 */
class PromptAssemblerTest {

    @Test
    fun 默认系统段位于首条且内容为DEFAULT() {
        val messages = PromptAssembler().assemble(emptyList())

        assertEquals(PromptRole.SYSTEM, messages.first().role)
        assertEquals(PromptAssembler.DEFAULT_SYSTEM_PROMPT, messages.first().content)
    }

    @Test
    fun 更新系统段后组装使用新内容_技能段顺序不变() {
        val assembler = PromptAssembler(skillsSection = "技能：demo")
        assembler.updateSystemSection("自定义系统提示词")

        val messages = assembler.assemble(emptyList())

        assertEquals("自定义系统提示词", messages[0].content)
        assertEquals("技能：demo", messages[1].content)
        assertEquals(2, messages.size)
    }

    @Test
    fun 更新实例的系统段不影响其他实例() {
        val changed = PromptAssembler()
        val untouched = PromptAssembler()

        changed.updateSystemSection("另一份提示词")

        assertEquals("另一份提示词", changed.systemSection)
        assertEquals(PromptAssembler.DEFAULT_SYSTEM_PROMPT, untouched.systemSection)
    }

    @Test
    fun 未更新时多次组装字节级一致_稳定前缀不漂移() {
        val assembler = PromptAssembler(skillsSection = "技能：demo")

        val first = assembler.assemble(emptyList()).map { it.content }
        val second = assembler.assemble(emptyList()).map { it.content }

        assertEquals(first, second)
    }
}
