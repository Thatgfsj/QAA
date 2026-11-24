package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.PromptAssembler
import com.thatgfsj.qaa.data.SystemPromptStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 系统提示词生效策略单测（#105）：同步、整体替换、恢复默认、失败回执与变更回调。 */
class SystemPromptSwitcherTest {

    private class FakeStore(
        var stored: String = PromptAssembler.DEFAULT_SYSTEM_PROMPT,
        private val failWrite: Boolean = false,
        private val failRead: Boolean = false,
    ) : SystemPromptStore {

        val writes = mutableListOf<String>()
        var resets = 0

        override val prompt: Flow<String> = flow {
            if (failRead) error("读取失败")
            emit(stored)
        }

        override suspend fun setPrompt(text: String) {
            if (failWrite) error("磁盘不可写")
            writes += text
            stored = text
        }

        override suspend fun reset() {
            if (failWrite) error("磁盘不可写")
            resets += 1
            stored = PromptAssembler.DEFAULT_SYSTEM_PROMPT
        }
    }

    @Test
    fun 默认状态_来源为默认且不触发变更回调() = runTest {
        val changed = mutableListOf<String>()
        val switcher = SystemPromptSwitcher(FakeStore()) { changed += it }

        switcher.sync()

        assertEquals(PromptAssembler.DEFAULT_SYSTEM_PROMPT, switcher.current)
        assertEquals("默认", switcher.sourceLabel)
        assertTrue(changed.isEmpty())
    }

    @Test
    fun sync读到自定义值_通知装配层并更新来源() = runTest {
        val changed = mutableListOf<String>()
        val switcher = SystemPromptSwitcher(FakeStore(stored = "自定义提示词")) { changed += it }

        switcher.sync()

        assertEquals("自定义提示词", switcher.current)
        assertEquals(listOf("自定义提示词"), changed)
        assertEquals("自定义", switcher.sourceLabel)
    }

    @Test
    fun 带文本整体替换_写存储并回显下一轮生效() = runTest {
        val store = FakeStore()
        val switcher = SystemPromptSwitcher(store)

        val receipt = switcher.applyChange("新的提示词")

        assertEquals(listOf("新的提示词"), store.writes)
        assertEquals("新的提示词", switcher.current)
        assertTrue(receipt.contains("已更新系统提示词"))
        assertTrue(receipt.contains("下一轮生效"))
        assertTrue(receipt.contains("工具调用失效"))
    }

    @Test
    fun 空白文本_等价恢复默认() = runTest {
        val store = FakeStore(stored = "旧提示词")
        val switcher = SystemPromptSwitcher(store)
        switcher.sync()

        val receipt = switcher.applyChange("   ")

        assertEquals(1, store.resets)
        assertEquals(PromptAssembler.DEFAULT_SYSTEM_PROMPT, switcher.current)
        assertTrue(receipt.contains("已恢复默认"))
    }

    @Test
    fun 显式reset_恢复默认并清空自定义来源() = runTest {
        val store = FakeStore(stored = "旧提示词")
        val switcher = SystemPromptSwitcher(store)
        switcher.sync()

        val receipt = switcher.applyChange(null)

        assertEquals(1, store.resets)
        assertEquals("默认", switcher.sourceLabel)
        assertEquals(PromptAssembler.DEFAULT_SYSTEM_PROMPT, switcher.current)
        assertTrue(receipt.contains("已恢复默认"))
    }

    @Test
    fun 存储写入失败_保持旧值并回显失败原因() = runTest {
        val switcher = SystemPromptSwitcher(FakeStore(failWrite = true))

        val receipt = switcher.applyChange("新提示词")

        assertEquals(PromptAssembler.DEFAULT_SYSTEM_PROMPT, switcher.current)
        assertTrue(receipt.contains("失败"))
        assertTrue(receipt.contains("设置存储不可写"))
    }

    @Test
    fun 读取失败_保持内存旧值() = runTest {
        val switcher = SystemPromptSwitcher(FakeStore(stored = "自定义", failRead = true))

        switcher.sync()

        assertEquals(PromptAssembler.DEFAULT_SYSTEM_PROMPT, switcher.current)
    }

    @Test
    fun 查询回显_含来源字数与提示词全文() = runTest {
        val switcher = SystemPromptSwitcher(FakeStore(stored = "自定义内容"))

        val text = switcher.describe()

        assertTrue(text.contains("来源：自定义"))
        assertTrue(text.contains("5 字"))
        assertTrue(text.contains("自定义内容"))
        assertTrue(text.contains("/system reset"))
    }
}
