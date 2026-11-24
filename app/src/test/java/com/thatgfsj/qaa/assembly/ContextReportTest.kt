package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.inference.GgufKvEstimator
import com.thatgfsj.qaa.core.inference.GgufKvLayout
import com.thatgfsj.qaa.core.inference.KvEstimate
import com.thatgfsj.qaa.core.inference.KvEstimateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** /ctx 回执与字节格式化单测（#112）：KV 估算、设备内存、警告与措辞。 */
class ContextReportTest {

    private val gib = 1024L * 1024L * 1024L

    private val metadataKv = KvEstimate(
        bytesPerToken = 131_072L,
        source = KvEstimateSource.GGUF_METADATA,
        layout = GgufKvLayout(
            architecture = "llama",
            blockCount = 32,
            headDim = 128,
            valueDim = 128,
            headsPerLayer = List(32) { 8 },
        ),
    )

    private val fallbackKv = KvEstimate(
        bytesPerToken = GgufKvEstimator.FALLBACK_BYTES_PER_TOKEN,
        source = KvEstimateSource.FALLBACK,
        layout = null,
    )

    private val memory = DeviceMemoryInfo(availBytes = (3.2 * gib).toLong(), totalBytes = (15.4 * gib).toLong())

    @Test
    fun 回执首行给出设置引擎与预算() {
        val lines = describeContextReceipt(
            setting = 32768,
            engine = 4096,
            budget = 3456,
            kv = metadataKv,
            kvNctx = 4096,
            memory = memory,
            modelLoaded = true,
        ).lines()

        assertEquals("上下文：设置 32768（引擎 4096）→ 预算 3456 token", lines[0])
    }

    @Test
    fun 回执按GGUF头估算KV并展示依据() {
        val text = describeContextReceipt(32768, 4096, 3456, metadataKv, 4096, memory, true)

        // 131072 B/token × 4096 token = 512 MiB
        assertTrue(text, text.contains("≈ 512.0 MiB"))
        assertTrue(text, text.contains("32 层 × 8 KV heads × 128 维"))
        assertTrue(text, text.contains("f16 估算"))
    }

    @Test
    fun 回执展示设备可用与总内存() {
        val text = describeContextReceipt(32768, 32768, 31600, metadataKv, 32768, memory, true)
        assertTrue(text, text.contains("设备内存：可用 3.2 GiB / 总 15.4 GiB"))
    }

    @Test
    fun 回执不出现档位与abort字样() {
        val text = describeContextReceipt(32768, 32768, 31600, metadataKv, 32768, memory, true)
        assertFalse(text, text.contains("档位"))
        assertFalse(text, text.contains("abort"))
        assertTrue(text, text.contains("可能因内存溢出闪退"))
    }

    @Test
    fun KV远超可用内存时追加回退警告() {
        val text = describeContextReceipt(
            setting = 1048576,
            engine = 4096,
            budget = 3456,
            kv = fallbackKv,
            kvNctx = 1048576,
            memory = DeviceMemoryInfo(availBytes = 4 * gib, totalBytes = 16 * gib),
            modelLoaded = true,
        )

        assertTrue(text, text.contains("144.0 GiB"))
        assertTrue(text, text.contains("警告：预计 KV ≈ 144.0 GiB > 可用内存 4.0 GiB"))
        assertTrue(text, text.contains("会回退 4096"))
    }

    @Test
    fun KV未超内存时不给警告() {
        val roomy = DeviceMemoryInfo(availBytes = 8 * gib, totalBytes = 16 * gib)
        val text = describeContextReceipt(32768, 32768, 31600, metadataKv, 32768, roomy, true)
        assertFalse(text, text.contains("警告："))
    }

    @Test
    fun 未载入模型时使用默认估算口径() {
        val text = describeContextReceipt(32768, 32768, 31600, fallbackKv, 32768, memory, false)
        assertTrue(text, text.contains("默认估算 144 KiB/token，尚未载入模型"))
    }

    @Test
    fun 已载入但解析失败时提示GGUF头解析失败() {
        val text = describeContextReceipt(32768, 32768, 31600, fallbackKv, 32768, memory, true)
        assertTrue(text, text.contains("GGUF 头解析失败"))
    }

    @Test
    fun 内存读取失败时给出不可用文案() {
        val text = describeContextReceipt(32768, 32768, 31600, metadataKv, 32768, null, true)
        assertTrue(text, text.contains("设备内存：不可用"))
        assertFalse(text, text.contains("警告："))
    }

    @Test
    fun 回退提示追加在回执末尾() {
        val lines = describeContextReceipt(
            32768, 32768, 31600, metadataKv, 32768, memory, true,
            fallbackNotice = "回退提示：已自动回退 4096。",
        ).lines()
        assertEquals("回退提示：已自动回退 4096。", lines.last())
    }

    @Test
    fun 字节格式化覆盖四个量级() {
        assertEquals("4.5 GiB", formatByteSize((4.5 * gib).toLong()))
        assertEquals("512.0 MiB", formatByteSize(512L * 1024 * 1024))
        assertEquals("144.0 KiB", formatByteSize(144L * 1024))
        assertEquals("512 B", formatByteSize(512L))
    }
}
