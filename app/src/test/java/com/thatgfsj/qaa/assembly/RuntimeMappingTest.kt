package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.CompactionResult
import com.thatgfsj.qaa.core.harness.CompactionTrigger
import com.thatgfsj.qaa.core.harness.CompactedRange
import com.thatgfsj.qaa.core.harness.SkipReason
import com.thatgfsj.qaa.core.inference.CatalogEntry
import com.thatgfsj.qaa.core.inference.ComputeBackend
import com.thatgfsj.qaa.core.inference.ModelPullEvent
import com.thatgfsj.qaa.core.inference.ModelSource
import com.thatgfsj.qaa.feature.terminal.BackendRequest
import com.thatgfsj.qaa.feature.terminal.LocalModelEntry
import com.thatgfsj.qaa.feature.terminal.TerminalPullEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 装配层纯映射单测：模型清单、拉取事件、后端与压缩文案。 */
class RuntimeMappingTest {

    @Test
    fun 目录构造按filesDir约定() {
        val sessionsDir = java.io.File("/data/user/0/com.thatgfsj.qaa/files/sessions")
        assertEquals(
            "/data/user/0/com.thatgfsj.qaa/files/sessions/main.jsonl",
            com.thatgfsj.qaa.core.harness.TreeSessionStore.fileFor(sessionsDir, AgentRuntime.SESSION_ID).path,
        )
        assertEquals("models", AgentRuntime.MODELS_DIR)
        assertEquals("skills", AgentRuntime.SKILLS_DIR)
        assertEquals(3072, AgentRuntime.CONTEXT_BUDGET)
    }

    @Test
    fun 清单条目映射为终端条目_量化缺省为未知() {
        val entries = listOf(
            CatalogEntry(1, "Supra2.5-Pro-Instruct", "Q4_0", 40L, ModelSource.BUILT_IN, "/m/a.gguf"),
            CatalogEntry(2, "Qwen3-4B", null, null, ModelSource.IMPORT, null),
        )
        assertEquals(
            listOf(
                LocalModelEntry(1, "Supra2.5-Pro-Instruct", "Q4_0"),
                LocalModelEntry(2, "Qwen3-4B", "未知"),
            ),
            catalogToLocalEntries(entries),
        )
    }

    @Test
    fun 拉取事件三类均正确投影() {
        assertEquals(
            TerminalPullEvent.Progress(10, 100, 0.1f),
            ModelPullEvent.Progress(10, 100, 0.1f).toTerminalEvent(),
        )
        assertEquals(
            TerminalPullEvent.Completed("org/repo", "/p/repo.gguf"),
            ModelPullEvent.Completed("org/repo", "/p/repo.gguf").toTerminalEvent(),
        )
        assertEquals(
            TerminalPullEvent.Failed(-100005, "网络中断", true),
            ModelPullEvent.Failed(-100005, "网络中断", true).toTerminalEvent(),
        )
    }

    @Test
    fun 后端请求映射为引擎后端() {
        assertEquals(ComputeBackend.AUTO, BackendRequest.AUTO.toComputeBackend())
        assertEquals(ComputeBackend.NPU, BackendRequest.NPU.toComputeBackend())
        assertEquals(ComputeBackend.HYBRID, BackendRequest.HYBRID.toComputeBackend())
        assertEquals(ComputeBackend.CPU, BackendRequest.CPU.toComputeBackend())
    }

    @Test
    fun 压缩结果文案覆盖四类() {
        val compacted = CompactionResult.Compacted(
            ranges = listOf(CompactedRange("s-1", "n-1", "n-4", 4, 120)),
            trigger = CompactionTrigger.MANUAL,
            estimatedTokensBefore = 1000,
            estimatedTokensAfter = 400,
            postWatermarkRatio = 0.13f,
            watermarkSatisfied = true,
        )
        val text = describeCompaction(compacted)
        assertTrue(text.contains("1 段"))
        assertTrue(text.contains("1000"))
        assertTrue(text.contains("400"))

        assertTrue(describeCompaction(CompactionResult.Skipped(SkipReason.BELOW_THRESHOLD)).contains("未达压缩阈值"))
        assertEquals(
            "轮次进行中：压缩已排队到最近安全点。",
            describeCompaction(CompactionResult.DeferredUntilSafePoint),
        )
        assertTrue(describeCompaction(CompactionResult.Failed("摘要超时")).contains("摘要超时"))
    }
}
