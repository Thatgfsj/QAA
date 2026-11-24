package com.thatgfsj.qaa.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #99 /tree 终端兜底单测：空态不静默、非空保留原文本并追加图例。 */
class TerminalTreeTextTest {

    @Test
    fun 空树_给出明确空态而非静默() {
        val text = sessionTreeOutput("")
        assertEquals(EMPTY_TREE_TEXT, text)
        assertTrue(text.contains("会话树为空"))
        assertTrue(text.contains("/fork"))
    }

    @Test
    fun 纯空白树_同样命中空态() {
        assertEquals(EMPTY_TREE_TEXT, sessionTreeOutput("  \n\n "))
    }

    @Test
    fun 非空树_保留原文本并追加图例提示() {
        val tree = "● n-000001 [user] 你好\n└─○ n-000002 [assistant] 回答"
        val text = sessionTreeOutput(tree + "\n")
        assertTrue(text.startsWith(tree))
        assertTrue(text.contains(TREE_FORK_HINT))
    }
}
