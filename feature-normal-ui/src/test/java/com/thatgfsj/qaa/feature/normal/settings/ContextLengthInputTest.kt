package com.thatgfsj.qaa.feature.normal.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 设置页自定义上下文长度输入单测（#112）：解析 k / m 后缀、范围与支持文案。 */
class ContextLengthInputTest {

    @Test
    fun 接受任意整数与后缀() {
        assertEquals(3072, parseContextLengthInput("3072"))
        assertEquals(8192, parseContextLengthInput("8192"))
        assertEquals(20480, parseContextLengthInput("20k"))
        assertEquals(65536, parseContextLengthInput("64K"))
        assertEquals(1048576, parseContextLengthInput("1m"))
        assertNull(contextLengthInputError("20k"))
    }

    @Test
    fun 拒绝非法输入并给出中文支持文案() {
        listOf("abc", "0", "-1", "1g", "1023", "1048577").forEach { raw ->
            assertNotNull("$raw 应被拒绝", contextLengthInputError(raw))
            val error = contextLengthInputError(raw)!!
            assertEquals(true, error.contains("1024、8192、20k、64k、1m"))
        }
    }

    @Test
    fun 空输入提示示例() {
        assertEquals(true, contextLengthInputError("  ")!!.contains("8192"))
    }
}
