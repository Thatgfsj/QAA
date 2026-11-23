package com.thatgfsj.qaa.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Terminal 流式入口单测：begin / append / end 三接口时序、增量渲染与乱序防护。 */
class TerminalViewModelStreamTest {

    @Test
    fun 开始_增量_定稿后完整回显为一行() {
        val vm = TerminalViewModel()
        val streamId = vm.beginAssistantTurn()
        vm.appendToken("你", streamId)
        vm.appendToken("好", streamId)
        vm.endAssistantTurn(streamId, "你好")

        val entries = vm.uiState.value.entries
        assertEquals(1, entries.size)
        assertEquals(TerminalRole.AI, entries.single().role)
        assertEquals("你好", entries.single().text)
        assertTrue(vm.uiState.value.followOutput)
    }

    @Test
    fun 增量片段逐步更新同一行() {
        val vm = TerminalViewModel()
        vm.beginAssistantTurn(7)
        vm.appendToken("A", 7)
        assertEquals("A", vm.uiState.value.entries.single().text)
        vm.appendToken("B", 7)
        assertEquals("AB", vm.uiState.value.entries.single().text)
        assertEquals(1, vm.uiState.value.entries.size)
    }

    @Test
    fun 定稿后迟到片段被忽略() {
        val vm = TerminalViewModel()
        vm.beginAssistantTurn(1)
        vm.appendToken("定稿", 1)
        vm.endAssistantTurn(1, "定稿")
        vm.appendToken("迟到", 1)
        vm.endAssistantTurn(1, "迟到")

        assertEquals("定稿", vm.uiState.value.entries.single().text)
    }

    @Test
    fun 流标识不符的片段被忽略() {
        val vm = TerminalViewModel()
        vm.beginAssistantTurn(2)
        vm.appendToken("旧流", 1)
        vm.appendToken("新流", 2)

        assertEquals("新流", vm.uiState.value.entries.single().text)
    }

    @Test
    fun 未开始流时片段被忽略() {
        val vm = TerminalViewModel()
        vm.appendToken("孤立片段")
        assertTrue(vm.uiState.value.entries.isEmpty())
    }

    @Test
    fun 整步仅工具调用JSON时丢弃该行() {
        val vm = TerminalViewModel()
        vm.onUserInput("查文件")
        vm.beginAssistantTurn(3)
        vm.appendToken("工具调用JSON", 3)
        vm.endAssistantTurn(3, "")

        val entries = vm.uiState.value.entries
        assertEquals(1, entries.size)
        assertEquals(TerminalRole.USER, entries.single().role)
    }

    @Test
    fun 无内容占位行定稿时移除() {
        val vm = TerminalViewModel()
        vm.beginAssistantTurn(4)
        vm.endAssistantTurn(4)
        assertTrue(vm.uiState.value.entries.isEmpty())
    }

    @Test
    fun 新流开始前自动定稿上一行() {
        val vm = TerminalViewModel()
        vm.beginAssistantTurn(1)
        vm.appendToken("第一段", 1)
        vm.beginAssistantTurn(2)
        vm.appendToken("第二段", 2)

        val entries = vm.uiState.value.entries
        assertEquals(2, entries.size)
        assertEquals("第一段", entries[0].text)
        assertEquals("第二段", entries[1].text)
    }

    @Test
    fun 清屏后活动流被清理() {
        val vm = TerminalViewModel()
        vm.beginAssistantTurn(9)
        vm.appendToken("清屏前", 9)
        vm.resetSession()
        vm.appendToken("清屏后", 9)

        assertTrue(vm.uiState.value.entries.isEmpty())
    }
}
