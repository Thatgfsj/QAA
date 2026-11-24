package com.thatgfsj.qaa.core.inference

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** GGUF 头 KV 估算单测（#112）：解析成功 / 缺 key_length 推导 / 逐层 heads / 回退。 */
class GgufKvEstimatorTest {

    @Test
    fun 解析llama头并按f16公式估算KV(@TempDir dir: File) {
        val file = File(dir, "model.gguf")
        file.writeBytes(
            buildGguf(
                "general.architecture" to "llama",
                "llama.block_count" to 32,
                "llama.attention.head_count" to 32,
                "llama.attention.head_count_kv" to 8,
                "llama.attention.key_length" to 128,
                "llama.attention.value_length" to 128,
                "llama.embedding_length" to 4096,
                // 非目标键：词表大数组 / 浮点 / 布尔 / 字符串，验证跳过逻辑不破坏解析。
                "tokenizer.ggml.tokens" to listOf("a", "b", "c"),
                "llama.rope.freq_base" to 10000.0f,
                "general.quantization_version" to 2,
                "general.alignment" to 32,
                "general.name" to "synthetic",
            ),
        )

        val estimate = GgufKvEstimator.estimate(file)

        assertEquals(KvEstimateSource.GGUF_METADATA, estimate.source)
        // 32 层 × 8 KV heads × (128 + 128) 维 × 2B = 131072 B/token
        assertEquals(32L * 8L * 256L * 2L, estimate.bytesPerToken)
        assertEquals(32, estimate.layout?.blockCount)
        assertEquals("8", estimate.layout?.kvHeadsSummary)
        assertEquals("llama", estimate.layout?.architecture)
    }

    @Test
    fun 缺key_length时用embedding除以head_count推导(@TempDir dir: File) {
        val file = File(dir, "model.gguf")
        file.writeBytes(
            buildGguf(
                "general.architecture" to "llama",
                "llama.block_count" to 32,
                "llama.attention.head_count" to 32,
                "llama.attention.head_count_kv" to 8,
                "llama.embedding_length" to 4096,
            ),
        )

        val estimate = GgufKvEstimator.estimate(file)

        assertEquals(KvEstimateSource.GGUF_METADATA, estimate.source)
        assertEquals(32L * 8L * (128L + 128L) * 2L, estimate.bytesPerToken)
    }

    @Test
    fun 逐层kv_heads数组按层求和(@TempDir dir: File) {
        val file = File(dir, "model.gguf")
        file.writeBytes(
            buildGguf(
                "general.architecture" to "qwen2",
                "qwen2.block_count" to 4,
                "qwen2.attention.head_count" to 16,
                "qwen2.attention.head_count_kv" to listOf(8, 8, 0, 8),
                "qwen2.attention.key_length" to 128,
                "qwen2.attention.value_length" to 128,
            ),
        )

        val estimate = GgufKvEstimator.estimate(file)

        assertEquals(KvEstimateSource.GGUF_METADATA, estimate.source)
        assertEquals((8L + 8L + 0L + 8L) * 256L * 2L, estimate.bytesPerToken)
        assertEquals("8/0", estimate.layout?.kvHeadsSummary)
    }

    @Test
    fun 坏magic回退默认经验值(@TempDir dir: File) {
        val file = File(dir, "broken.gguf")
        file.writeBytes(ByteArray(64) { 0x41 })

        val estimate = GgufKvEstimator.estimate(file)

        assertEquals(KvEstimateSource.FALLBACK, estimate.source)
        assertEquals(GgufKvEstimator.FALLBACK_BYTES_PER_TOKEN, estimate.bytesPerToken)
        assertNull(estimate.layout)
    }

    @Test
    fun 缺关键字段回退(@TempDir dir: File) {
        val file = File(dir, "no-layers.gguf")
        file.writeBytes(
            buildGguf(
                "general.architecture" to "llama",
                "llama.attention.head_count_kv" to 8,
            ),
        )

        assertEquals(KvEstimateSource.FALLBACK, GgufKvEstimator.estimate(file).source)
    }

    @Test
    fun 无文件或空文件回退() {
        assertEquals(GgufKvEstimator.FALLBACK_BYTES_PER_TOKEN, GgufKvEstimator.estimate(null).bytesPerToken)
        assertEquals(KvEstimateSource.FALLBACK, GgufKvEstimator.estimate(null).source)
    }

    @Test
    fun 截断文件回退不抛异常(@TempDir dir: File) {
        val full = buildGguf(
            "general.architecture" to "llama",
            "llama.block_count" to 32,
            "llama.attention.head_count_kv" to 8,
        )
        val file = File(dir, "truncated.gguf")
        file.writeBytes(full.copyOfRange(0, full.size / 2))

        assertEquals(KvEstimateSource.FALLBACK, GgufKvEstimator.estimate(file).source)
    }

    @Test
    fun 经验值在32k约4点5GiB且1m约140GiB() {
        val bytes32k = GgufKvEstimator.bytesForTokens(GgufKvEstimator.FALLBACK_BYTES_PER_TOKEN, 32768)
        val gib32k = bytes32k / 1024.0 / 1024.0 / 1024.0
        assertTrue(gib32k in 4.4..4.6, "32k 回退估算应在 4.4~4.6 GiB：$gib32k")

        val bytes1m = GgufKvEstimator.bytesForTokens(GgufKvEstimator.FALLBACK_BYTES_PER_TOKEN, 1048576)
        val gib1m = bytes1m / 1024.0 / 1024.0 / 1024.0
        assertTrue(gib1m in 138.0..150.0, "1m 回退估算应在 138~150 GiB：$gib1m")
    }

    // ---- 合成 GGUF 头（小端序） ----

    private fun buildGguf(vararg entries: Pair<String, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        out.u32(0x46554747) // "GGUF"
        out.u32(3) // version
        out.u64(0) // tensor_count
        out.u64(entries.size.toLong())
        entries.forEach { (key, value) -> out.writeKeyValue(key, value) }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeKeyValue(key: String, value: Any) {
        writeString(key)
        when (value) {
            is Int -> { u32(4); u32(value) } // uint32
            is Long -> { u32(10); u64(value) } // uint64
            is Float -> { u32(6); u32(java.lang.Float.floatToRawIntBits(value)) } // float32
            is Boolean -> { u32(7); write(if (value) 1 else 0) }
            is String -> { u32(8); writeString(value) }
            is List<*> -> {
                u32(9) // array
                if (value.firstOrNull() is String) {
                    u32(8) // element type: string
                    u64(value.size.toLong())
                    value.forEach { item -> writeString(item as String) }
                } else {
                    u32(4) // element type: uint32
                    u64(value.size.toLong())
                    value.forEach { item -> u32((item as Number).toInt()) }
                }
            }
            else -> error("unsupported test value: " + value::class)
        }
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        u64(bytes.size.toLong())
        write(bytes)
    }

    private fun ByteArrayOutputStream.u32(value: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun ByteArrayOutputStream.u64(value: Long) {
        write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
    }
}
