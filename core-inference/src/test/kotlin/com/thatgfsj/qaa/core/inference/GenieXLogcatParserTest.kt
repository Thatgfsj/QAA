package com.thatgfsj.qaa.core.inference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** logcat 后端旁证解析（可选源，不得影响 create 档位）。 */
class GenieXLogcatParserTest {

    @Test
    fun parsesComputeUnitAliasLine() {
        val line = "[JNI] [extract] compute_unit = hybrid, n_gpu_layers = -1 (from raw='hybrid')"
        val signal = GenieXLogcatParser.parseLine(line)
        assertEquals(ActiveBackend.HYBRID, signal?.tier)
        assertEquals(BackendSignalSource.LOGCAT, signal?.source)
    }

    @Test
    fun parsesEmptyDeviceFallbackAsHybridHint() {
        val line = "E GenieXSdk: geniex_resolve_device failed (rc=-1), falling back to empty device"
        val signal = GenieXLogcatParser.parseLine(line)
        assertEquals(ActiveBackend.HYBRID, signal?.tier)
    }

    @Test
    fun lastMatchingLineWins() {
        val lines = sequenceOf(
            "D GenieXSdk: [JNI] [extract] compute_unit = npu, n_gpu_layers = -1 (from raw='npu')",
            "D GenieXSdk: [JNI] [extract] compute_unit = cpu, n_gpu_layers = 0 (from raw='cpu')",
        )
        assertEquals(ActiveBackend.CPU, GenieXLogcatParser.parse(lines)?.tier)
    }

    @Test
    fun ignoresUnrelatedLines() {
        assertNull(GenieXLogcatParser.parseLine("D GenieXSdk: llm result:null"))
    }
}
