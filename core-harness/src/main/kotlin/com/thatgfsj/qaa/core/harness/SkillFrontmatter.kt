package com.thatgfsj.qaa.core.harness

/**
 * SKILL.md frontmatter 极简行解析（设计文档 8.2：--- 分隔 + key: value，
 * 不引入 YAML 库）。
 *
 * 只认识 name 与 description 两个必填键：首行必须为 `---`，随后每个非空行
 * 必须是 `key: value`，遇到下一个 `---` 结束。任何异常形态都返回结构化
 * [Result.Invalid]，不抛异常。description 超长时截断为
 * [MAX_DESCRIPTION_LENGTH] 个字符并附带告警。
 */
internal object SkillFrontmatterParser {

    /** description 建议上限（设计文档 8.2 的「≤ 80 汉字」，按 Unicode 码点计）。 */
    const val MAX_DESCRIPTION_LENGTH = 80

    private const val DELIMITER = "---"
    private const val KEY_NAME = "name"
    private const val KEY_DESCRIPTION = "description"
    private const val ELLIPSIS = '…'

    /** 解析结果：成功携带元数据与非致命告警，失败携带跳过原因。 */
    sealed interface Result {
        data class Valid(
            val name: String,
            val description: String,
            val warnings: List<String>,
        ) : Result

        data class Invalid(
            val reason: SkillSkipReason,
            val detail: String,
        ) : Result
    }

    fun parse(markdown: String): Result {
        val lines = normalize(markdown).lines()
        if (lines.firstOrNull()?.trim() != DELIMITER) {
            return invalid(SkillSkipReason.MISSING_FRONTMATTER, "首行不是 --- 分隔符")
        }
        val closingIndex = (1 until lines.size).firstOrNull { lines[it].trim() == DELIMITER }
            ?: return invalid(SkillSkipReason.UNCLOSED_FRONTMATTER, "缺少结束分隔符 ---")

        var name: String? = null
        var description: String? = null
        for (index in 1 until closingIndex) {
            val line = lines[index].trim()
            if (line.isEmpty()) continue
            val colonIndex = line.indexOf(':')
            if (colonIndex <= 0) {
                return invalid(
                    SkillSkipReason.MALFORMED_LINE,
                    "第 ${index + 1} 行不是 key: value 格式",
                )
            }
            val key = line.substring(0, colonIndex).trim()
            val value = line.substring(colonIndex + 1).trim()
            when (key) {
                KEY_NAME -> name = value
                KEY_DESCRIPTION -> description = value
                else -> return invalid(SkillSkipReason.UNEXPECTED_FIELD, "不支持的字段：$key")
            }
        }

        val validName = name?.takeIf { it.isNotBlank() }
            ?: return invalid(SkillSkipReason.MISSING_NAME, "缺少 name 字段")
        val validDescription = description?.takeIf { it.isNotBlank() }
            ?: return invalid(SkillSkipReason.MISSING_DESCRIPTION, "缺少 description 字段")

        val warnings = mutableListOf<String>()
        val normalizedDescription = if (codePointLength(validDescription) > MAX_DESCRIPTION_LENGTH) {
            warnings += "description 超过 $MAX_DESCRIPTION_LENGTH 字符，已截断"
            truncate(validDescription, MAX_DESCRIPTION_LENGTH)
        } else {
            validDescription
        }
        return Result.Valid(
            name = validName,
            description = normalizedDescription,
            warnings = warnings,
        )
    }

    /** 去掉 UTF-8 BOM 并统一行尾，便于逐行解析。 */
    private fun normalize(markdown: String): String =
        markdown.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

    private fun codePointLength(text: String): Int = text.codePointCount(0, text.length)

    /** 截断到 [maxCodePoints] 个码点，末位用省略号占位，避免拆开代理对。 */
    private fun truncate(text: String, maxCodePoints: Int): String {
        val end = text.offsetByCodePoints(0, maxCodePoints - 1)
        return text.substring(0, end) + ELLIPSIS
    }

    private fun invalid(reason: SkillSkipReason, detail: String): Result =
        Result.Invalid(reason = reason, detail = detail)
}
