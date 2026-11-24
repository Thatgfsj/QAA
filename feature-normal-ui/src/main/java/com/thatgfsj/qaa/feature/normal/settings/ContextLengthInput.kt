package com.thatgfsj.qaa.feature.normal.settings

import com.thatgfsj.qaa.core.harness.ContextWindow

/**
 * 自定义上下文长度输入（#112）：设置页允许输入任意 1024~1048576 的整数，
 * 并支持 k / m 后缀（20k=20480、1m=1048576）。校验与解析复用 core-harness 的
 * [ContextWindow.parseInput]，保证与终端 `/ctx` 同一套规则。
 */
internal fun parseContextLengthInput(raw: String): Int? = ContextWindow.parseInput(raw)

/** 输入是否合法；非法时返回中文支持文案（直接作为 supportingText 展示）。 */
internal fun contextLengthInputError(raw: String): String? = when {
    raw.isBlank() -> "请输入上下文长度，如 8192、20k、64k"
    parseContextLengthInput(raw) == null ->
        "无效长度：支持 ${ContextWindow.INPUT_EXAMPLES}" +
            "（k=1024、m=1024²），范围 ${ContextWindow.MIN_N_CTX}~${ContextWindow.MAX_N_CTX}"
    else -> null
}
