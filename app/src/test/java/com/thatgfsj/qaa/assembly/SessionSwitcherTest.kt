package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.SessionRole
import com.thatgfsj.qaa.core.harness.TreeSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** /new 会话轮换单测：id 自增、新文件落盘、旧文件不被触碰。 */
class SessionSwitcherTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun 会话id按最大序号自增() {
        assertEquals("main", SessionSwitcher.nextSessionId(emptyList()))
        assertEquals("main-2", SessionSwitcher.nextSessionId(listOf("main")))
        assertEquals("main-3", SessionSwitcher.nextSessionId(listOf("main", "main-2")))
        assertEquals("main-6", SessionSwitcher.nextSessionId(listOf("main", "main-5")))
        assertEquals("main-2", SessionSwitcher.nextSessionId(listOf("main", "other", "main-x")))
    }

    @Test
    fun 状态段回显当前会话id() {
        assertEquals(
            "会话：main-2，节点 3，活动分支 2 节点，摘要 1 段",
            sessionStatusText("main-2", 3, 2, 1),
        )
    }

    @Test
    fun 新会话落盘新文件且旧文件内容不变() {
        val dir = folder.newFolder("sessions")
        val switcher = SessionSwitcher(dir)

        val first = switcher.openNext()
        assertEquals("main", first.sessionId)
        first.append(role = SessionRole.USER, content = "旧会话第一句")
        val oldFile = TreeSessionStore.fileFor(dir, "main")
        val oldBytes = oldFile.readBytes().toList()

        val second = switcher.openNext()
        assertEquals("main-2", second.sessionId)
        assertNotEquals(second.sessionId, first.sessionId)
        val newFile = TreeSessionStore.fileFor(dir, "main-2")
        assertTrue(newFile.isFile)
        assertTrue(newFile.readText().contains("recordType"))
        assertEquals(oldBytes, oldFile.readBytes().toList())
        assertEquals(1, first.nodeCount())
        assertEquals(0, second.nodeCount())

        second.append(role = SessionRole.USER, content = "新会话第一句")
        assertEquals(1, second.nodeCount())
        assertEquals(oldBytes, oldFile.readBytes().toList())

        val third = switcher.openNext()
        assertEquals("main-3", third.sessionId)
        assertEquals(oldBytes, oldFile.readBytes().toList())
        first.close()
        second.close()
        third.close()
    }
}
