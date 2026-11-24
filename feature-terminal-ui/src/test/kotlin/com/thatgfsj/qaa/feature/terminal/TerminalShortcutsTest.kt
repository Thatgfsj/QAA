package com.thatgfsj.qaa.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 快捷键动作映射与输入历史单测（#79）：纯 JVM 运行，不依赖 Compose / Android。 */
class TerminalShortcutsTest {

    private val idle = TerminalUiState()
    private val pending = idle.copy(toolConfirm = ToolConfirmState.Pending(1, "read(\".\")"))
    private val resolved = idle.copy(toolConfirm = ToolConfirmState.Resolved(1, "read(\".\")", true))

    @Test
    fun historyKeysMapToPrevAndNext() {
        assertEquals(TerminalShortcutAction.HistoryPrev, TerminalShortcut.HISTORY_PREV.actionFor(idle))
        assertEquals(TerminalShortcutAction.HistoryNext, TerminalShortcut.HISTORY_NEXT.actionFor(idle))
        // 确认区状态不影响历史键的语义。
        assertEquals(TerminalShortcutAction.HistoryPrev, TerminalShortcut.HISTORY_PREV.actionFor(pending))
    }

    @Test
    fun confirmKeysMapToAllowAndDenyWhenPending() {
        assertEquals(TerminalShortcutAction.AllowTool, TerminalShortcut.CONFIRM_ALLOW.actionFor(pending))
        assertEquals(TerminalShortcutAction.DenyTool, TerminalShortcut.CONFIRM_DENY.actionFor(pending))
    }

    @Test
    fun confirmKeysShowChineseNoticeWhenNothingPending() {
        listOf(idle, resolved).forEach { state ->
            assertEquals(
                TerminalShortcutAction.Notice(TerminalShortcut.NO_PENDING_CONFIRM_NOTICE),
                TerminalShortcut.CONFIRM_ALLOW.actionFor(state),
            )
            assertEquals(
                TerminalShortcutAction.Notice(TerminalShortcut.NO_PENDING_CONFIRM_NOTICE),
                TerminalShortcut.CONFIRM_DENY.actionFor(state),
            )
        }
        assertTrue(TerminalShortcut.NO_PENDING_CONFIRM_NOTICE.contains("无待确认"))
    }

    @Test
    fun orderedKeepsFourRealKeys() {
        assertEquals(
            listOf(
                TerminalShortcut.HISTORY_PREV,
                TerminalShortcut.HISTORY_NEXT,
                TerminalShortcut.CONFIRM_ALLOW,
                TerminalShortcut.CONFIRM_DENY,
            ),
            TerminalShortcut.ordered,
        )
    }

    @Test
    fun inputHistoryWalksBackAndRestoresDraft() {
        val history = TerminalInputHistory()
        assertNull(history.previous("草稿"))

        history.record("help")
        history.record("model")
        assertEquals("model", history.previous("未提交"))
        assertEquals("help", history.previous("未提交"))
        // 已在最早一条：继续上翻停住，不回绕。
        assertEquals("help", history.previous("未提交"))
        assertEquals("model", history.next())
        // 回到草稿位：恢复上翻时保存的输入行内容。
        assertEquals("未提交", history.next())
        assertNull(history.next())
    }

    @Test
    fun inputHistoryIgnoresBlankAndConsecutiveDuplicates() {
        val history = TerminalInputHistory()
        history.record("   ")
        history.record("help")
        history.record("help")
        history.record("  model  ")
        assertEquals(2, history.size)
        assertEquals("model", history.previous(""))
        assertEquals("help", history.previous(""))
    }

    @Test
    fun inputHistoryRespectsCapacityAndResetsCursorOnRecord() {
        val history = TerminalInputHistory(capacity = 2)
        history.record("a")
        history.record("b")
        history.record("c")
        assertEquals(2, history.size)
        assertEquals("c", history.previous(""))
        assertEquals("b", history.previous(""))

        history.record("d")
        // 提交后游标回到末尾：上翻从最新一条开始。
        assertEquals("d", history.previous(""))
    }
}
