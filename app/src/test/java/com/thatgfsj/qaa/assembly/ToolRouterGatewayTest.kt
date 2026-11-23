package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.ToolCall
import com.thatgfsj.qaa.core.harness.ToolOutcomeStatus
import com.thatgfsj.qaa.core.tools.DrawImageTool
import com.thatgfsj.qaa.core.tools.LocalSandboxFs
import com.thatgfsj.qaa.core.tools.SandboxRoot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 工具执行端口适配单测：确认需求映射、四工具经沙盒执行、未知工具兜底。 */
class ToolRouterGatewayTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun gateway(): ToolRouterGateway {
        val root = SandboxRoot.File("files", folder.newFolder("files").toPath())
        return ToolRouterGateway(
            sandbox = LocalSandboxFs(listOf(root)),
            sessionId = "test-session",
            executors = ToolRouterGateway.defaultExecutors(DrawImageTool()),
        )
    }

    @Test
    fun 确认需求按工具声明映射() = runTest {
        val gateway = gateway()
        assertFalse(gateway.requiresConfirmation("read"))
        assertTrue(gateway.requiresConfirmation("write"))
        assertTrue(gateway.requiresConfirmation("edit"))
        assertTrue(gateway.requiresConfirmation("draw_image"))
        assertFalse(gateway.requiresConfirmation("bash"))
    }

    @Test
    fun 写入后可读回且状态为成功() = runTest {
        val gateway = gateway()
        val write = gateway.execute(
            ToolCall("write", """{"path":"notes.txt","content":"骁龙真机"}"""),
            stepIndex = 0,
        )
        assertEquals(ToolOutcomeStatus.SUCCESS, write.status)

        val read = gateway.execute(ToolCall("read", """{"path":"notes.txt"}"""), stepIndex = 1)
        assertEquals(ToolOutcomeStatus.SUCCESS, read.status)
        assertTrue(read.output.contains("骁龙真机"))
    }

    @Test
    fun 读取不存在的文件返回失败与稳定错误码() = runTest {
        val outcome = gateway().execute(ToolCall("read", """{"path":"missing.txt"}"""), stepIndex = 0)
        assertEquals(ToolOutcomeStatus.FAILURE, outcome.status)
        assertTrue(outcome.errorCode?.isNotBlank() == true)
    }

    @Test
    fun 路径越界被沙盒拒绝() = runTest {
        val outcome = gateway().execute(ToolCall("read", """{"path":"../escape.txt"}"""), stepIndex = 0)
        assertEquals(ToolOutcomeStatus.FAILURE, outcome.status)
        assertTrue(outcome.output.contains("越界") || outcome.errorCode == "E_PATH_ESCAPE")
    }

    @Test
    fun 画图引擎缺席时快速失败() = runTest {
        val outcome = gateway().execute(
            ToolCall("draw_image", """{"prompt":"一只猫"}"""),
            stepIndex = 2,
        )
        assertEquals(ToolOutcomeStatus.FAILURE, outcome.status)
        assertEquals("E_ENGINE_NOT_AVAILABLE", outcome.errorCode)
    }

    @Test
    fun 未知工具报白名单失败() = runTest {
        val outcome = gateway().execute(ToolCall("bash", """{"cmd":"ls"}"""), stepIndex = 0)
        assertEquals(ToolOutcomeStatus.FAILURE, outcome.status)
        assertEquals(ToolRouterGateway.ERROR_UNKNOWN_TOOL, outcome.errorCode)
    }

    @Test
    fun 工具上下文携带会话与步序号() = runTest {
        val context = gateway().contextOf(stepIndex = 7)
        assertEquals("test-session", context.sessionId)
        assertEquals(7, context.stepIndex)
    }
}
