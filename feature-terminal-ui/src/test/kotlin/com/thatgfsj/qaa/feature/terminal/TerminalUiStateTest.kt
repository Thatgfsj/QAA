package com.thatgfsj.qaa.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/** 纯状态层格式化契约单测（Issue #9）：按规格文案严格断言，防文案漂移。 */
class TerminalUiStateTest {

    @Test
    fun rolePrefixesFollowTerminalContract() {
        assertEquals("你 < ", TerminalRole.USER.displayPrefix)
        assertEquals("AI < ", TerminalRole.AI.displayPrefix)
    }

    @Test
    fun topBarSegmentsShowModelModeBackend() {
        val state = TerminalUiState()
        assertEquals(
            listOf("[MODEL] Supra2.5-Pro-Instruct(Q4_0)", "[CHAT]", "[NPU]"),
            state.topBarSegments(),
        )
        val agentCpu = state.copy(mode = TerminalMode.AGENT, backend = TerminalBackend.CPU)
        assertEquals("[AGENT]", agentCpu.topBarSegments()[1])
        assertEquals("[CPU]", agentCpu.topBarSegments()[2])
    }

    @Test
    fun statsBarTextMatchesIssueExample() {
        val snapshot = TerminalMetricsSnapshot(
            ttftMs = 142L,
            tokensPerSecond = 38.4,
            tokensUsed = 412,
            contextTokens = 4096,
        )
        assertEquals("TTFT: 142ms | 速度: 38.4 tok/s | Token: 412 / 4096", snapshot.statsBarText())
    }

    @Test
    fun statsBarTextShowsPlaceholdersBeforeFirstToken() {
        val snapshot = TerminalMetricsSnapshot()
        // #106：统计栏默认总量与 ContextWindow.DEFAULT（32768）对齐。
        assertEquals("TTFT: -- | 速度: -- | Token: 0 / 32768", snapshot.statsBarText())
    }

    @Test
    fun toolConfirmTextsFollowIssueFormat() {
        val pending = ToolConfirmState.Pending(1L, "read(\".\")")
        assertEquals("[TOOL] read(\".\") -> Allow / Deny?", pending.confirmText())
        val allowed = ToolConfirmState.Resolved(1L, "read(\".\")", allowed = true)
        assertEquals("[TOOL] read(\".\") -> Allow", allowed.resultText())
        val denied = ToolConfirmState.Resolved(1L, "read(\".\")", allowed = false)
        assertEquals("[TOOL] read(\".\") -> Deny", denied.resultText())
    }

    @Test
    fun modelInfoDisplayJoinsQuantization() {
        val info = TerminalModelInfo(name = "Supra2.5-Pro-Instruct", quantization = "Q4_0")
        assertEquals("Supra2.5-Pro-Instruct(Q4_0)", info.displayText)
    }
}
