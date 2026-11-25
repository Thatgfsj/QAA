package com.thatgfsj.qaa.core.inference

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** GgufHeaderReader 单测：合法头 / 标量与数组形态 / 缺失键 / 坏 magic / 截断 / 版本 / 大数组跳过。 */
class GgufHeaderTest {

    private fun parsed(bytes: ByteArray): GgufHeader {
        val result = GgufHeaderReader.parse(bytes)
        assertTrue(result is GgufHeaderResult.Parsed, "期望解析成功，实际：" + result)
        return (result as GgufHeaderResult.Parsed).header
    }

    private fun reason(bytes: ByteArray): String {
        val result = GgufHeaderReader.parse(bytes)
        assertTrue(result is GgufHeaderResult.Unreadable, "期望解析失败，实际：" + result)
        return (result as GgufHeaderResult.Unreadable).reason
    }

    @Test
    fun 合法头_解析出架构_量化_层数与头维度() {
        val header = parsed(GgufFixture.build())
        assertEquals(3L, header.version)
        assertEquals("qwen2", header.architecture)
        assertEquals(GgufQuantization.Q4_0, header.quantization)
        assertEquals(32, header.blockCount)
        assertEquals(32, header.headCount)
        assertEquals(8, header.kvHeadCount)
        assertEquals(32, header.headsPerLayer.size)
        assertEquals(128, header.headDim)
        assertEquals(128, header.valueDim)
    }

    @Test
    fun head_count_kv_数组形态逐层保留() {
        val header = parsed(GgufFixture.build(headCountKv = listOf(8, 8, 4, 4)))
        assertEquals(listOf(8, 8, 4, 4), header.headCountKv)
        assertEquals(listOf(8, 8, 4, 4), header.headsPerLayer)
    }

    @Test
    fun 缺失_key_length_时用_embedding_整除_heads_推导() {
        val header = parsed(GgufFixture.build(keyLength = null, valueLength = null))
        assertEquals(64, header.headDim)
        assertEquals(64, header.valueDim)
    }

    @Test
    fun 版本_1_被明确拒绝() {
        assertTrue(reason(GgufFixture.build(version = 1)).contains("不支持的 GGUF 版本"))
    }

    @Test
    fun magic_错误_返回中文原因() {
        assertTrue(reason(GgufFixture.wrongMagic()).contains("不是 GGUF 文件"))
    }

    @Test
    fun 截断头_返回截断原因() {
        val full = GgufFixture.build()
        assertTrue(reason(full.copyOf(full.size / 2)).contains("截断"))
    }

    @Test
    fun 词表大数组可跳过_仍能解析() {
        val header = parsed(GgufFixture.build(tokenCount = 300, tailBytes = 256))
        assertEquals(GgufQuantization.Q4_0, header.quantization)
    }

    @Test
    fun file_type_缺失_量化未知但头可读() {
        val header = parsed(GgufFixture.build(fileType = null))
        assertEquals(GgufQuantization.UNKNOWN, header.quantization)
        assertEquals("qwen2", header.architecture)
    }

    @Test
    fun Q4_K_M_头解析为对应档位() {
        val header = parsed(GgufFixture.build(fileType = 15L))
        assertEquals(GgufQuantization.Q4_K_M, header.quantization)
    }

    @Test
    fun 不存在的文件_返回不可读() {
        val result = GgufHeaderReader.read(File("/tmp/qaa-no-such-model-" + System.nanoTime() + ".gguf"))
        assertTrue(result is GgufHeaderResult.Unreadable)
    }

    @Test
    fun 张量目录解析出名称_维度_类型并按角色聚合() {
        val result = GgufHeaderReader.parse(
            GgufFixture.build(
                tensorCount = 4,
                tensors = listOf(
                    GgufFixture.matmul("blk.0.attn_q.weight", GgufQuantization.Q4_0, rows = 256, cols = 2048),
                    GgufFixture.matmul("blk.0.ffn_down.weight", GgufQuantization.Q4_0, rows = 2048, cols = 256),
                    GgufFixture.FixtureTensor("token_embd.weight", listOf(2048L, 16384L), GgufQuantization.Q8_0),
                    GgufFixture.FixtureTensor("blk.0.attn_norm.weight", listOf(2048L), GgufQuantization.F32),
                ),
            ),
        )
        assertTrue(result is GgufHeaderResult.Parsed)
        val parsed = result as GgufHeaderResult.Parsed
        assertEquals(4, parsed.tensors.size)
        val attn = parsed.tensors.first { it.name == "blk.0.attn_q.weight" }
        assertEquals(GgufQuantization.Q4_0, attn.type)
        assertEquals(listOf(256L, 2048L), attn.dims)
        assertEquals(256L * 2048L, attn.elementCount)
        assertEquals(GgufTensorRole.MATMUL_WEIGHT, attn.role)
        assertEquals(GgufTensorRole.EMBEDDING, parsed.tensors.first { it.name == "token_embd.weight" }.role)
        assertEquals(GgufTensorRole.NORM, parsed.tensors.first { it.name == "blk.0.attn_norm.weight" }.role)
        val stats = parsed.header.tensorStats
        assertEquals(4, stats.tensorCount)
        assertEquals(2, stats.weightTensorCount)
        assertEquals(GgufQuantization.Q4_0, stats.dominantWeightType)
        assertEquals(GgufQuantization.Q4_0, parsed.header.weightQuantization)
    }

    @Test
    fun 张量目录声明条数多于实际数据时返回截断() {
        val bytes = GgufFixture.build(
            tensors = listOf(GgufFixture.matmul("blk.0.attn_q.weight", GgufQuantization.Q4_0)),
        )
        // 只保留 tensor info 的前半段（去掉最后 6 字节偏移字段）。
        assertTrue(reason(bytes.copyOf(bytes.size - 6)).contains("截断"))
    }

    @Test
    fun 声明张量数超上限时明确拒绝() {
        val bytes = GgufFixture.build()
        // tensor_count 位于 magic/version 之后（偏移 8..16 字节，小端 u64）。
        val patched = bytes.copyOf()
        val huge = GgufHeaderReader.MAX_TENSORS + 1L
        for (i in 0 until 8) patched[8 + i] = ((huge shr (8 * i)) and 0xFF).toByte()
        assertTrue(reason(patched).contains("张量条目数"))
    }

    @Test
    fun 从文件读取与内存解析结果一致() {
        val bytes = GgufFixture.build(fileType = 7L)
        val file = Files.createTempFile("qaa-gguf-", ".gguf").toFile()
        try {
            file.writeBytes(bytes)
            val fromFile = GgufHeaderReader.read(file)
            assertTrue(fromFile is GgufHeaderResult.Parsed)
            assertEquals(
                GgufQuantization.Q8_0,
                (fromFile as GgufHeaderResult.Parsed).header.quantization,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun 未提供路径时返回不可读而非抛异常() {
        val result = GgufHeaderReader.read(null)
        assertTrue(result is GgufHeaderResult.Unreadable)
        assertNull(
            (result as GgufHeaderResult.Unreadable).reason.takeIf { it.isBlank() },
        )
    }
}
