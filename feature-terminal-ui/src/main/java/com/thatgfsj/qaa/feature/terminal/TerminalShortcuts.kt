package com.thatgfsj.qaa.feature.terminal

/**
 * 底部快捷键（#79）：每个按键都有真实的输入行 / 确认区语义。
 *
 * - ↑ / ↓：翻阅输入历史（[TerminalInputHistory]），等效把历史行填回输入行；
 * - yes / no：确认区挂起时等效 Allow / Deny，无挂起时提示 [NO_PENDING_CONFIRM_NOTICE]。
 *
 * 纯 Kotlin：映射可在 JVM 单测中直接断言，不依赖 Compose / Android。
 */
enum class TerminalShortcut(val label: String) {
    HISTORY_PREV("↑"),
    HISTORY_NEXT("↓"),
    CONFIRM_ALLOW("yes"),
    CONFIRM_DENY("no");

    /** 按当前视图状态判定点击动作：只有 Pending 才等效放行 / 拒绝。 */
    fun actionFor(state: TerminalUiState): TerminalShortcutAction = when (this) {
        HISTORY_PREV -> TerminalShortcutAction.HistoryPrev
        HISTORY_NEXT -> TerminalShortcutAction.HistoryNext
        CONFIRM_ALLOW, CONFIRM_DENY -> {
            if (state.toolConfirm is ToolConfirmState.Pending) {
                if (this == CONFIRM_ALLOW) TerminalShortcutAction.AllowTool else TerminalShortcutAction.DenyTool
            } else {
                TerminalShortcutAction.Notice(NO_PENDING_CONFIRM_NOTICE)
            }
        }
    }

    companion object {
        /** 无待确认项时的中文提示：写入对话区，避免点击无反馈。 */
        const val NO_PENDING_CONFIRM_NOTICE = "当前无待确认操作。"

        /** 按钮展示顺序：历史两键在前，确认两键在后。 */
        val ordered: List<TerminalShortcut> = listOf(HISTORY_PREV, HISTORY_NEXT, CONFIRM_ALLOW, CONFIRM_DENY)
    }
}

/** 快捷键点击语义：由底部栏映射到输入行 / 确认区 / 提示行。 */
sealed interface TerminalShortcutAction {
    data object HistoryPrev : TerminalShortcutAction
    data object HistoryNext : TerminalShortcutAction
    data object AllowTool : TerminalShortcutAction
    data object DenyTool : TerminalShortcutAction
    data class Notice(val text: String) : TerminalShortcutAction
}

/**
 * 输入行历史：只记录提交成功的非空行，游标停在末尾「草稿位」。
 *
 * ↑ 从草稿位进入历史时保存当前输入，↓ 回到草稿位时恢复；
 * 连续重复的提交只保留一行，最多保存 [capacity] 行。
 */
class TerminalInputHistory(capacity: Int = 100) {

    private val capacity = capacity.coerceAtLeast(1)
    private val lines = mutableListOf<String>()
    private var cursor = 0
    private var draft = ""

    /** 已记录的历史行数（不含草稿）。 */
    val size: Int get() = lines.size

    /** 记录一行提交成功的输入；空行与连续重复行忽略。 */
    fun record(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        if (lines.lastOrNull() != text) {
            lines += text
            while (lines.size > capacity) lines.removeAt(0)
        }
        cursor = lines.size
        draft = ""
    }

    /** 上一条历史；[currentDraft] 为输入行当前内容（首次上翻时保存，供 ↓ 恢复）。 */
    fun previous(currentDraft: String): String? {
        if (lines.isEmpty()) return null
        if (cursor >= lines.size) draft = currentDraft
        if (cursor > 0) cursor -= 1
        return lines[cursor]
    }

    /** 下一条历史；已回到草稿位时返回 null，输入行保持用户原内容。 */
    fun next(): String? {
        if (lines.isEmpty() || cursor >= lines.size) return null
        cursor += 1
        return if (cursor == lines.size) draft else lines[cursor]
    }
}
