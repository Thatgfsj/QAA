package com.thatgfsj.qaa.core.harness

import kotlin.math.ceil

/**
 * 上下文 token 计数端口（设计文档 6.1）。
 *
 * 主计数取引擎返回的 promptTokens（GenieX 实测 prefill）；引擎未返回时由
 * [HeuristicTokenCounter] 按字符近似兜底。接口保持纯 JVM，便于在无模型环境下
 * 对阈值与保留策略做单测。
 */
interface TokenCounter {

    /** 单段文本的 token 数。 */
    fun count(text: String): Int

    /** 一组提示消息的 token 数，含逐条消息固定开销。 */
    fun countMessages(messages: List<PromptMessage>): Int
}

/**
 * 字符密度 token 估算器（设计文档 6.1 的估算兜底）。
 *
 * 标定依据：Qwen / GPT-4o 一类分词器在中文文本上的实测密度约为 1.5–1.8 字/token，
 * 取 1.6 字/token 居中；英文与代码约为 4 字符/token；emoji、控制符等按 1 字符/token。
 * 估算是实测值缺失时的下限保护：[ContextUsage] 取实测与估算的较大者，宁可略高估
 * 5%–15%，也不低估导致压缩触发过晚而撞上下文上限。
 */
class HeuristicTokenCounter(
    private val cjkCharsPerToken: Double = DEFAULT_CJK_CHARS_PER_TOKEN,
    private val asciiCharsPerToken: Double = DEFAULT_ASCII_CHARS_PER_TOKEN,
    private val otherCharsPerToken: Double = DEFAULT_OTHER_CHARS_PER_TOKEN,
    private val messageOverheadTokens: Int = DEFAULT_MESSAGE_OVERHEAD_TOKENS,
) : TokenCounter {

    init {
        require(cjkCharsPerToken > 0.0) { "cjkCharsPerToken 必须为正数" }
        require(asciiCharsPerToken > 0.0) { "asciiCharsPerToken 必须为正数" }
        require(otherCharsPerToken > 0.0) { "otherCharsPerToken 必须为正数" }
        require(messageOverheadTokens >= 0) { "messageOverheadTokens 不能为负" }
    }

    override fun count(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var ascii = 0
        var other = 0
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            when {
                isCjk(codePoint) -> cjk++
                isAscii(codePoint) -> ascii++
                else -> other++
            }
            index += Character.charCount(codePoint)
        }
        val estimate = cjk / cjkCharsPerToken +
            ascii / asciiCharsPerToken +
            other / otherCharsPerToken
        return ceil(estimate).toInt().coerceAtLeast(1)
    }

    override fun countMessages(messages: List<PromptMessage>): Int =
        messages.sumOf { message -> count(message.content) + messageOverheadTokens }

    private fun isCjk(codePoint: Int): Boolean = CJK_RANGES.any { codePoint in it }

    private fun isAscii(codePoint: Int): Boolean =
        codePoint in 0x20..0x7E || codePoint == 0x0A || codePoint == 0x09

    companion object {
        /** 中文实测密度中值（字/token）。 */
        const val DEFAULT_CJK_CHARS_PER_TOKEN: Double = 1.6

        /** 英文与代码约 4 字符/token。 */
        const val DEFAULT_ASCII_CHARS_PER_TOKEN: Double = 4.0

        /** emoji 与生僻符号按 1 字符/token 保守计。 */
        const val DEFAULT_OTHER_CHARS_PER_TOKEN: Double = 1.0

        /** 每条消息的角色标记与分隔开销（约 3–4 token）。 */
        const val DEFAULT_MESSAGE_OVERHEAD_TOKENS: Int = 4

        private val CJK_RANGES = listOf(
            0x3000..0x303F, // CJK 标点
            0x3040..0x30FF, // 日文假名
            0x3400..0x4DBF, // CJK 扩展 A
            0x4E00..0x9FFF, // CJK 基本区
            0xAC00..0xD7AF, // 韩文音节
            0xF900..0xFAFF, // CJK 兼容表意文字
            0xFF00..0xFFEF, // 全角字符
        )
    }
}

/**
 * 当前上下文占用：引擎实测 promptTokens 与字符估算取 max（设计文档 6.1）。
 *
 * 实测优先不等于实测独断：部分引擎的 prefill 计数不含系统段或工具模板，与估算
 * 取大值可避免低估。
 */
data class ContextUsage(
    val measuredPromptTokens: Int?,
    val estimatedTokens: Int,
) {
    /** 压缩判定使用的有效占用。 */
    val effectiveTokens: Int = maxOf(measuredPromptTokens ?: 0, estimatedTokens)

    /** 是否存在引擎实测值（false 表示完全依赖估算兜底）。 */
    val isMeasured: Boolean = measuredPromptTokens != null

    /** 有效占用率；[contextBudget] 必须为正。 */
    fun ratio(contextBudget: Int): Float {
        require(contextBudget > 0) { "contextBudget 必须为正数" }
        return effectiveTokens.toFloat() / contextBudget
    }
}

/** 汇总一组提示消息的占用；[measuredPromptTokens] 为引擎实测值，可为空。 */
fun TokenCounter.usage(
    messages: List<PromptMessage>,
    measuredPromptTokens: Int? = null,
): ContextUsage = ContextUsage(
    measuredPromptTokens = measuredPromptTokens?.coerceAtLeast(0),
    estimatedTokens = countMessages(messages),
)
