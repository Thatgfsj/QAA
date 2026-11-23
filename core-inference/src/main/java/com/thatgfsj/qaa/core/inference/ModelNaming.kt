package com.thatgfsj.qaa.core.inference

/** GGUF 量化标识：长形态优先（IQ4_XS / Q4_K_M / Q4_0），排除紧邻字母数字的误命中。 */
private val QUANTIZATION = Regex(
    "(?i)(?<![A-Za-z0-9])" +
        "(IQ\\d+_[A-Z0-9]+(?:_[A-Z0-9]+)?|Q\\d+_K_[SML]|Q\\d+_K|Q\\d+_\\d+|Q\\d+|BF16|F16|F32)" +
        "(?![A-Za-z0-9])",
)

/** 由 GGUF 文件名推断量化精度；无标记返回 null。 */
internal fun quantizationOf(fileName: String?): String? =
    baseNameOf(fileName)?.let { QUANTIZATION.find(it)?.value?.uppercase() }

/** 展示名：去掉扩展名与量化标记（`X-Q4_0.gguf` → `X`，`Qwen2.5-7B-Q4_K_M.gguf` → `Qwen2.5-7B`）。 */
internal fun displayNameOf(fileName: String): String {
    val base = baseNameOf(fileName) ?: return fileName
    val withoutExt = base.substringBeforeLast('.', base)
    val quant = QUANTIZATION.find(withoutExt)
    val trimmed = if (quant == null) withoutExt else withoutExt.removeRange(quant.range)
    return trimmed.trim('-', '_', '.', ' ').ifBlank { base }
}

private fun baseNameOf(fileName: String?): String? {
    if (fileName.isNullOrBlank()) return null
    return fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { null }
}
