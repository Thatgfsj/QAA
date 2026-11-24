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
    fun orderedKeepsFiveRealKeys() {
        assertEquals(
            listOf(
                TerminalShortcut.HISTORY_PREV,
                TerminalShortcut.HISTORY_NEXT,
                TerminalShortcut.CONFIRM_ALLOW,
                TerminalShortcut.CONFIRM_DENY,
                TerminalShortcut.KEYBOARD,
            ),
            TerminalShortcut.ordered,
        )
    }

    // ---- #93 长按次选 / 会话级记忆 ----

    @Test
    fun keyboardKeyTogglesSoftKeyboardAndHasNoAlternate() {
        assertEquals(TerminalShortcutAction.ToggleKeyboard, TerminalShortcut.KEYBOARD.actionFor(idle))
        assertTrue(TerminalShortcut.KEYBOARD.alternates.isEmpty())
    }

    @Test
    fun longPressAlternatesCarryChineseLabels() {
        assertEquals(
            listOf(ShortcutAlternate(TerminalShortcutAction.ClearScreen, "clear（清屏）")),
            TerminalShortcut.HISTORY_PREV.alternates,
        )
        assertEquals(
            listOf(ShortcutAlternate(TerminalShortcutAction.HistoryOldest, "跳到最早历史")),
            TerminalShortcut.HISTORY_NEXT.alternates,
        )
        assertEquals(
            listOf(ShortcutAlternate(TerminalShortcutAction.RememberAllow, "本会话自动放行")),
            TerminalShortcut.CONFIRM_ALLOW.alternates,
        )
        assertEquals(
            listOf(ShortcutAlternate(TerminalShortcutAction.RememberDeny, "本会话自动拒绝")),
            TerminalShortcut.CONFIRM_DENY.alternates,
        )
    }

    @Test
    fun longPressAndShortPressStayDistinct() {
        // 同一按键的短按与长按落在不同动作上：UI 只负责分流，语义由映射层锁定。
        val pairs = listOf(
            TerminalShortcut.HISTORY_PREV to TerminalShortcutAction.HistoryPrev,
            TerminalShortcut.HISTORY_NEXT to TerminalShortcutAction.HistoryNext,
            TerminalShortcut.CONFIRM_ALLOW to TerminalShortcutAction.AllowTool,
            TerminalShortcut.CONFIRM_DENY to TerminalShortcutAction.DenyTool,
        )
        pairs.forEach { (key, shortAction) ->
            assertEquals(shortAction, key.actionFor(pending))
            assertTrue(key.alternates.none { it.action == shortAction })
        }
    }

    @Test
    fun confirmScopeLabelShowsSessionMemory() {
        assertEquals("确认", confirmScopeLabel(ToolConfirmPolicy.ASK))
        assertEquals("确认·自动放行", confirmScopeLabel(ToolConfirmPolicy.AUTO_ALLOW))
        assertEquals("确认·自动拒绝", confirmScopeLabel(ToolConfirmPolicy.AUTO_DENY))
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

    @Test
    fun inputHistoryOldestJumpsToFirstEntryAndBackToDraft() {
        val history = TerminalInputHistory()
        assertNull(history.oldest(""))
        history.record("help")
        history.record("model")
        history.record("chat 0")
        // ↓ 长按：直接定位最早一条，再逐条往回走，最后回到草稿位。
        assertEquals("help", history.oldest("草稿"))
        assertEquals("model", history.next())
        assertEquals("chat 0", history.next())
        assertEquals("草稿", history.next())
    }
}
