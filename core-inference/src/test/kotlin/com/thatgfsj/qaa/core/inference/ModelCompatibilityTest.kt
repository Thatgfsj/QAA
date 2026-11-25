package com.thatgfsj.qaa.core.inference

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** ModelCompatibilityGate 单测：支持矩阵、拦截文案、未知档位放行、文件名预检与清单展示后缀。 */
class ModelCompatibilityTest {

    private val gate = ModelCompatibilityGate

    @Test
    fun Q4_0_受支持_无提示_不拦截() {
        val compat = gate.assess(GgufQuantization.Q4_0)
        assertTrue(compat.supported)
        assertTrue(compat.status.allowed)
        assertNull(compat.displayNote)
        assertNull(
            gate.rejectionMessage(
                ModelCompatibilityGate.ModelAction.IMPORT,
                "Supra2.5-Pro-Instruct",
                compat,
            ),
        )
    }

    @Test
    fun Q4_K_M_不受支持_中文文案含档位与_Q4_0_建议() {
        val compat = gate.assess(GgufQuantization.Q4_K_M)
        assertFalse(compat.status.allowed)
        val message = gate.rejectionMessage(
            ModelCompatibilityGate.ModelAction.IMPORT,
            "Smoke",
            compat,
        )
        assertNotNull(message)
        assertTrue(message!!.contains("Q4_K_M"))
        assertTrue(message.contains("Q4_0"))
        assertTrue(message.contains("乱码"))
        assertTrue(message.contains("已删除"))
    }

    @Test
    fun 其余已知非_Q4_0_档位全部拦截_K_系列单列() {
        val kSeries = listOf(
            GgufQuantization.Q4_K_S,
            GgufQuantization.Q5_K_M,
            GgufQuantization.Q6_K,
        )
        kSeries.forEach { quantization ->
            assertEquals(
                SupportStatus.BLOCK_KQUANT,
                gate.assess(quantization).status,
                "档位 " + quantization.label + " 应判 BLOCK_KQUANT",
            )
        }
        val quarantined = listOf(
            GgufQuantization.F16,
            GgufQuantization.Q8_0,
            GgufQuantization.IQ4_XS,
            GgufQuantization.BF16,
        )
        quarantined.forEach { quantization ->
            assertEquals(
                SupportStatus.QUARANTINE_Q8,
                gate.assess(quantization).status,
                "档位 " + quantization.label + " 应判 QUARANTINE_Q8",
            )
        }
    }

    @Test
    fun 未知档位导入放行但载入被拒() {
        val compat = gate.assess(GgufQuantization.UNKNOWN)
        assertTrue(compat.status.allowed)
        assertFalse(compat.status.loadAllowed)
        assertNull(gate.rejectionMessage(ModelCompatibilityGate.ModelAction.IMPORT, "Unknown", compat))
        val load = gate.rejectionMessage(ModelCompatibilityGate.ModelAction.LOAD, "Unknown", compat)
        assertNotNull(load)
        assertTrue(load!!.contains("无法确认兼容性"))
        assertTrue(load.contains("已拒绝载入"))
        assertNotNull(gate.hintText("Unknown", compat))
        assertNotNull(compat.displayNote)
    }

    @Test
    fun 文件名预检_Q8_0_被拒_无标记放行() {
        val blocked = gate.assessFileName("Foo-Q8_0.gguf")
        assertEquals(SupportStatus.QUARANTINE_Q8, blocked.status)
        assertNotNull(gate.rejectionMessageForName("Foo-Q8_0.gguf", "Foo"))
        val allowed = gate.assessFileName("Qwen3-4B-Instruct.gguf")
        assertEquals(SupportStatus.UNKNOWN, allowed.status)
        assertNull(gate.rejectionMessageForName("Qwen3-4B-Instruct.gguf", "Qwen3-4B-Instruct"))
    }

    @Test
    fun 载入拦截文案提示重新选择() {
        val message = gate.rejectionMessage(
            ModelCompatibilityGate.ModelAction.LOAD,
            "Smoke",
            gate.assess(GgufQuantization.Q8_0),
        )
        assertNotNull(message)
        assertTrue(message!!.contains("重新选择 Q4_0"))
    }

    @Test
    fun 清单展示后缀_仅非受支持档位追加() {
        assertNotNull(gate.displayNote(gate.assess(GgufQuantization.Q4_K_M)))
        assertTrue(gate.displayNote(gate.assess(GgufQuantization.Q4_K_M))!!.contains("不受支持"))
        assertNull(gate.displayNote(gate.assess(GgufQuantization.Q4_0)))
    }

    @Test
    fun 文件头接入_K_系列权重张量被判定不支持() {
        val file = Files.createTempFile("qaa-compat-", ".gguf").toFile()
        try {
            file.writeBytes(
                GgufFixture.build(
                    fileType = 15L,
                    tensors = listOf(
                        GgufFixture.matmul("blk.0.attn_q.weight", GgufQuantization.Q4_K),
                        GgufFixture.matmul("blk.0.ffn_down.weight", GgufQuantization.Q6_K),
                    ),
                ),
            )
            val compat = gate.assessFile(file)
            assertEquals(SupportStatus.BLOCK_KQUANT, compat.status)
            // Q6_K 每元素比特数更高，按权重字节占比成为主导类型（K 系列均属不可用档位）。
            assertEquals(GgufQuantization.Q6_K, compat.quantization)
            assertTrue(compat.displayNote!!.contains("不受支持"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun Q4_0_权重加_Q8_0_词嵌入_仍判定受支持() {
        // #97 实测：可用的 Spark-X2.6-4B-Q4_0 里 token_embd.weight 是 Q6_K。
        val file = Files.createTempFile("qaa-compat-", ".gguf").toFile()
        try {
            file.writeBytes(
                GgufFixture.build(
                    fileType = 2L,
                    tensors = listOf(
                        GgufFixture.matmul("blk.0.attn_q.weight", GgufQuantization.Q4_0),
                        GgufFixture.matmul("blk.0.ffn_down.weight", GgufQuantization.Q4_0),
                        GgufFixture.FixtureTensor(
                            name = "token_embd.weight",
                            dims = listOf(2048L, 4096L),
                            type = GgufQuantization.Q8_0,
                        ),
                    ),
                ),
            )
            val compat = gate.assessFile(file)
            assertEquals(SupportStatus.SUPPORTED, compat.status)
            assertEquals(GgufQuantization.Q4_0, compat.quantization)
            assertNull(compat.displayNote)
            assertEquals(2, compat.weightTensorCount)
        } finally {
            file.delete()
        }
    }

    @Test
    fun 声明_Q4_K_M_但张量是_Q5_0_时以张量为准并标记不一致() {
        // Supra2.5-Pro-Instruct-Q4_K_M：file_type=15，实际张量 Q5_0/Q8_0/F32 混合。
        val file = Files.createTempFile("qaa-compat-", ".gguf").toFile()
        try {
            file.writeBytes(
                GgufFixture.build(
                    fileType = 15L,
                    tensors = listOf(
                        GgufFixture.matmul("blk.0.attn_q.weight", GgufQuantization.Q5_0),
                        GgufFixture.matmul("blk.0.attn_k.weight", GgufQuantization.Q5_0),
                        GgufFixture.matmul("blk.0.ffn_up.weight", GgufQuantization.Q8_0),
                    ),
                ),
            )
            val compat = gate.assessFile(file)
            assertEquals(SupportStatus.QUARANTINE_Q8, compat.status)
            assertEquals(GgufQuantization.Q5_0, compat.quantization)
            assertEquals(GgufQuantization.Q4_K_M, compat.declaredQuantization)
            assertTrue(compat.fileTypeMismatch)
            val note = compat.displayNote!!
            assertTrue(note.contains("不受支持"))
            assertTrue(note.contains("Q4_K_M"))
            assertTrue(note.contains("不符"))
            val message = gate.rejectionMessage(
                ModelCompatibilityGate.ModelAction.IMPORT,
                "Supra2.5-Pro-Instruct-Q4_K_M",
                compat,
            )
            assertNotNull(message)
            assertTrue(message!!.contains("Q5_0"))
            assertTrue(message.contains("声明值不可信"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun 头可读但无张量目录时按未知放行并提示() {
        val compat = gate.assess(GgufHeader(version = 3L, architecture = "qwen2", fileType = 15L))
        assertEquals(SupportStatus.UNKNOWN, compat.status)
        assertTrue(compat.status.allowed)
        assertFalse(compat.status.loadAllowed)
        assertEquals(GgufQuantization.Q4_K_M, compat.quantization)
        assertTrue(compat.displayNote!!.contains("未识别"))
        assertNotNull(gate.hintText("Truncated", compat))
        assertNull(gate.rejectionMessage(ModelCompatibilityGate.ModelAction.IMPORT, "Truncated", compat))
        assertNotNull(gate.rejectionMessage(ModelCompatibilityGate.ModelAction.LOAD, "Truncated", compat))
    }

    @Test
    fun 张量类型未收录时按未知放行并提示() {
        val file = Files.createTempFile("qaa-compat-", ".gguf").toFile()
        try {
            file.writeBytes(
                GgufFixture.build(
                    tensors = listOf(
                        GgufFixture.FixtureTensor(
                            name = "blk.0.attn_q.weight",
                            dims = listOf(256L, 1024L),
                            type = GgufQuantization.UNKNOWN,
                            typeId = 500L, // 本版本未收录的 ggml 类型
                        ),
                    ),
                ),
            )
            val compat = gate.assessFile(file)
            assertEquals(SupportStatus.UNKNOWN, compat.status)
            assertTrue(compat.status.allowed, "未知档位不得静默拦截，也不得当作已支持")
            assertEquals(GgufQuantization.UNKNOWN, compat.quantization)
            assertNotNull(gate.rejectionMessage(ModelCompatibilityGate.ModelAction.LOAD, "UnknownType", compat))
            val note = compat.displayNote!!
            assertTrue(note.contains("未识别"))
            assertTrue(note.contains("乱码"))
            assertNotNull(gate.hintText("UnknownType", compat))
        } finally {
            file.delete()
        }
    }

    @Test
    fun 无法解析的文件头按未知放行() {
        val file = Files.createTempFile("qaa-compat-bad-", ".gguf").toFile()
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val compat = gate.assessFile(file)
            assertEquals(SupportStatus.UNKNOWN, compat.status)
            assertTrue(compat.status.allowed)
        } finally {
            file.delete()
        }
    }

    @Test
    fun 头里缺_file_type_但文件名带_Q4_0_时按受支持展示() {
        val compat = gate.assessFile(null)
        assertEquals(SupportStatus.UNKNOWN, compat.status)
        assertEquals(GgufQuantization.UNKNOWN, compat.quantization)
        assertEquals(SupportStatus.SUPPORTED, gate.assessFileName("Supra2.5-Pro-Instruct-Q4_0.gguf").status)
    }
}
