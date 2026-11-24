package com.thatgfsj.qaa.feature.terminal

/**
 * 底部快捷键（#79 基础 / #93 增强）：每个按键都有真实的输入行 / 确认区语义。
 *
 * - ↑ / ↓：翻阅输入历史（[TerminalInputHistory]），等效把历史行填回输入行；
 * - yes / no：确认区挂起时等效 Allow / Deny，无挂起时提示 [NO_PENDING_CONFIRM_NOTICE]；
 * - 键盘：收起 / 展开软键盘（Termux KEYBOARD 同款语义）。
 *
 * 长按弹次选（#93，Termux popup 风格）：↑ = clear 清屏、↓ = 跳到最早历史、
 * yes = 本会话自动放行、no = 关闭自动放行（恢复逐次询问）；键盘键无次选。
 *
 * 纯 Kotlin：映射可在 JVM 单测中直接断言，不依赖 Compose / Android。
 */
enum class TerminalShortcut(val label: String) {
    HISTORY_PREV("↑"),
    HISTORY_NEXT("↓"),
    CONFIRM_ALLOW("yes"),
    CONFIRM_DENY("no"),
    KEYBOARD("键盘");

    /** 长按次选动作（弹层逐条列出，点击即执行）；键盘键无次选。 */
    val alternates: List<ShortcutAlternate>
        get() = when (this) {
            HISTORY_PREV -> listOf(ShortcutAlternate(TerminalShortcutAction.ClearScreen, "clear（清屏）"))
            HISTORY_NEXT -> listOf(ShortcutAlternate(TerminalShortcutAction.HistoryOldest, "跳到最早历史"))
            CONFIRM_ALLOW ->
                listOf(ShortcutAlternate(TerminalShortcutAction.RememberAllow, "本会话自动放行"))
            CONFIRM_DENY ->
                listOf(ShortcutAlternate(TerminalShortcutAction.DisableAutoAllow, "关闭自动放行"))
            KEYBOARD -> emptyList()
        }

    /** 按当前视图状态判定点击动作：只有 Pending 才等效放行 / 拒绝。 */
    fun actionFor(state: TerminalUiState): TerminalShortcutAction = when (this) {
        HISTORY_PREV -> TerminalShortcutAction.HistoryPrev
        HISTORY_NEXT -> TerminalShortcutAction.HistoryNext
        KEYBOARD -> TerminalShortcutAction.ToggleKeyboard
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

        /** yes 长按后的中文提示（会话级记忆生效）。 */
        const val AUTO_ALLOW_NOTICE = "本会话后续确认将自动放行；清屏或退出会话后恢复逐次询问。"

        /** no 长按后的中文提示（关闭会话级自动放行）。 */
        const val AUTO_ALLOW_OFF_NOTICE = "已关闭本会话自动放行，后续确认恢复逐次询问。"

        /** 按钮展示顺序：历史两键、确认两键，最后是软键盘开关。 */
        val ordered: List<TerminalShortcut> = listOf(
            HISTORY_PREV,
            HISTORY_NEXT,
            CONFIRM_ALLOW,
            CONFIRM_DENY,
            KEYBOARD,
        )
    }
}

/** 快捷键点击语义：由底部栏映射到输入行 / 确认区 / 提示行 / 键盘。 */
sealed interface TerminalShortcutAction {
    data object HistoryPrev : TerminalShortcutAction
    data object HistoryNext : TerminalShortcutAction

    /** ↓ 长按：跳到最早一条历史。 */
    data object HistoryOldest : TerminalShortcutAction

    /** ↑ 长按：清屏（等效 clear，驱动 [TerminalViewModel.resetSession]）。 */
    data object ClearScreen : TerminalShortcutAction
    data object AllowTool : TerminalShortcutAction
    data object DenyTool : TerminalShortcutAction

    /** yes 长按：记住本会话自动放行，并结算当前挂起项（若有）。 */
    data object RememberAllow : TerminalShortcutAction

    /** no 长按：关闭本会话自动放行（恢复逐次询问；挂起项保持待人工决策）。 */
    data object DisableAutoAllow : TerminalShortcutAction

    /** 键盘键：收起 / 展开软键盘。 */
    data object ToggleKeyboard : TerminalShortcutAction
    data class Notice(val text: String) : TerminalShortcutAction
}

/** 长按次选条目：动作 + 弹层中文说明。 */
data class ShortcutAlternate(val action: TerminalShortcutAction, val label: String)

/**
 * 确认分组说明（底部栏 yes / no 旁的标签）：会话级自动放行生效时就地显示当前策略，
 * 避免自动放行在没有可见提示的情况下运行（#93）。
 */
fun confirmScopeLabel(policy: ToolConfirmPolicy): String = when (policy) {
    ToolConfirmPolicy.ASK -> "确认"
    ToolConfirmPolicy.AUTO_ALLOW -> "确认·自动放行"
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

    /**
     * 最早一条历史（↓ 长按）：游标直接定位到首条，后续 ↓ 逐条往回走。
     * 从草稿位跳转时同样保存当前输入，保证 ↓ 回到草稿位能恢复。
     */
    fun oldest(currentDraft: String): String? {
        if (lines.isEmpty()) return null
        if (cursor >= lines.size) draft = currentDraft
        cursor = 0
        return lines[0]
    }
}
