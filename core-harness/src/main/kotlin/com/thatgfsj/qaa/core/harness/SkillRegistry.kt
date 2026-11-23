package com.thatgfsj.qaa.core.harness

import java.io.File

/** 单个技能的元数据（描述段与 /skills 列表的同源数据，设计文档 8.3/8.4）。 */
data class SkillDescriptor(
    val name: String,
    val description: String,
)

/** 单次扫描中技能被跳过的结构化原因。 */
enum class SkillSkipReason {
    MISSING_FRONTMATTER,
    UNCLOSED_FRONTMATTER,
    MALFORMED_LINE,
    MISSING_NAME,
    MISSING_DESCRIPTION,
    UNEXPECTED_FIELD,
    DUPLICATE_NAME,
    UNREADABLE,
}

/** 被跳过的技能目录及原因（供告警与 /skills 展示）。 */
data class SkillSkip(
    val directory: String,
    val reason: SkillSkipReason,
    val detail: String,
)

/** 非致命告警（如 description 截断）；对应技能仍会注册。 */
data class SkillWarning(
    val directory: String,
    val message: String,
)

/** 一次扫描的结构化结果：扫描数、注册数、跳过原因与非致命告警。 */
data class SkillScanReport(
    val scannedDirectories: Int,
    val registeredCount: Int,
    val skipped: List<SkillSkip>,
    val warnings: List<SkillWarning>,
) {
    val skippedCount: Int get() = skipped.size

    companion object {
        val EMPTY = SkillScanReport(
            scannedDirectories = 0,
            registeredCount = 0,
            skipped = emptyList(),
            warnings = emptyList(),
        )
    }
}

/** /skills 展示项：与描述段同源，并标记是否已按需加载过全文。 */
data class SkillStatusEntry(
    val name: String,
    val description: String,
    val loaded: Boolean,
)

/** /skills 状态报告：扫描统计 + 注册项 + 跳过原因与非致命告警。 */
data class SkillRegistryStatus(
    val scannedDirectories: Int,
    val registeredCount: Int,
    val skippedCount: Int,
    val skills: List<SkillStatusEntry>,
    val skipped: List<SkillSkip>,
    val warnings: List<SkillWarning>,
)

/**
 * 技能注册表（设计文档第 8 章，SKILL.md 渐进式加载）。
 *
 * 启动时扫描一次 [SkillSource]；每个技能只缓存 frontmatter 的 name 与一句话
 * description。会话内 [describeAll] 输出按 name 字典序的稳定描述段，作为 System
 * Prompt 的 skills 段注入；命中判定交给 LLM，命中后由调用方经 [loadSkill] 按需
 * 读取全文回注，不在启动期加载正文。
 *
 * 单个技能解析失败只记录到 [status] 并跳过，不抛出异常；[rescan] 用于手动重扫。
 * 描述段在两次 [rescan] 之间字节级不变（保护 Prompt Cache 前缀），因此
 * [describeAll] 只使用缓存的元数据，不读取文件内容。
 *
 * 所有公开方法串行化，可从 UI 线程（/skills）与 Agent 循环并发调用。
 */
class SkillRegistry(private val source: SkillSource) {

    private val lock = Any()
    private var descriptors: List<SkillDescriptor> = emptyList()
    private var directoryByName: Map<String, String> = emptyMap()
    private var lastReport: SkillScanReport = SkillScanReport.EMPTY
    private val loadedNames = LinkedHashSet<String>()

    init {
        // 启动扫描一次；损坏项只进报告，不影响构造。
        rescan()
    }

    /**
     * 生成注入 System Prompt 的稳定描述段：标题 + 按 name 字典序的
     * `- name: description` 行，无尾部空行；无技能时返回空串（装配层据此跳过该段）。
     *
     * 段内不含动态数据，[rescan] 前两次调用字节级一致（Prompt Cache 前缀前提）。
     */
    fun describeAll(): String = synchronized(lock) {
        if (descriptors.isEmpty()) {
            ""
        } else {
            buildString {
                append(SKILLS_SECTION_HEADER)
                descriptors.forEach { descriptor ->
                    append('\n')
                    append("- ")
                    append(descriptor.name)
                    append(": ")
                    append(descriptor.description)
                }
            }
        }
    }

    /**
     * 按需读取技能全文（含 frontmatter 与正文），供命中后经 ToolResult 回注。
     *
     * 命中判定不在此处（由 LLM 依据描述段决定）；本方法只负责取回并记录加载
     * 状态。未知 name 或读取失败返回 null，不抛出异常。
     */
    fun loadSkill(name: String): String? = synchronized(lock) {
        val directory = directoryByName[name]
        if (directory == null) {
            null
        } else {
            val text = source.readSkillFile(directory)
            if (text == null) {
                null
            } else {
                loadedNames += name
                text
            }
        }
    }

    /**
     * 手动重扫（/skills refresh）：重建注册表并清空按需加载标记。
     *
     * 扫描数、注册数、跳过原因与非致命告警以 [SkillScanReport] 返回，同时更新
     * [status]。单技能损坏只记录跳过，不抛出异常。
     */
    fun rescan(): SkillScanReport = synchronized(lock) {
        val directories = source.listSkillDirectories()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        val registered = mutableListOf<SkillDescriptor>()
        val directoriesBySkillName = mutableMapOf<String, String>()
        val skipped = mutableListOf<SkillSkip>()
        val warnings = mutableListOf<SkillWarning>()

        directories.forEach { directory ->
            val markdown = source.readSkillFile(directory)
            if (markdown == null) {
                skipped += SkillSkip(
                    directory = directory,
                    reason = SkillSkipReason.UNREADABLE,
                    detail = "SKILL.md 不存在或不可读",
                )
                return@forEach
            }
            when (val parsed = SkillFrontmatterParser.parse(markdown)) {
                is SkillFrontmatterParser.Result.Invalid -> {
                    skipped += SkillSkip(directory, parsed.reason, parsed.detail)
                }

                is SkillFrontmatterParser.Result.Valid -> {
                    val existingDirectory = directoriesBySkillName[parsed.name]
                    if (existingDirectory != null) {
                        skipped += SkillSkip(
                            directory = directory,
                            reason = SkillSkipReason.DUPLICATE_NAME,
                            detail = "name 与目录 $existingDirectory 重复，保留先扫描者",
                        )
                    } else {
                        directoriesBySkillName[parsed.name] = directory
                        registered += SkillDescriptor(parsed.name, parsed.description)
                        parsed.warnings.forEach { warnings += SkillWarning(directory, it) }
                    }
                }
            }
        }

        val ordered = registered.sortedBy { it.name }
        descriptors = ordered
        directoryByName = directoriesBySkillName
        loadedNames.clear()
        lastReport = SkillScanReport(
            scannedDirectories = directories.size,
            registeredCount = ordered.size,
            skipped = skipped.toList(),
            warnings = warnings.toList(),
        )
        lastReport
    }

    /** 最近一次扫描结果与按需加载标记的快照，供 /skills 命令展示。 */
    fun status(): SkillRegistryStatus = synchronized(lock) {
        SkillRegistryStatus(
            scannedDirectories = lastReport.scannedDirectories,
            registeredCount = descriptors.size,
            skippedCount = lastReport.skippedCount,
            skills = descriptors.map { descriptor ->
                SkillStatusEntry(
                    name = descriptor.name,
                    description = descriptor.description,
                    loaded = descriptor.name in loadedNames,
                )
            },
            skipped = lastReport.skipped,
            warnings = lastReport.warnings,
        )
    }

    companion object {
        /** 描述段标题（设计文档 8.3 原文）：引导语即命中判定规则。 */
        const val SKILLS_SECTION_HEADER =
            "## 可用技能（任务与某技能描述匹配时，先用 read 工具读取其 SKILL.md 全文，再按全文执行）"

        /** 以技能根目录构造（App filesDir/skills、测试目录或 assets 释放目录）。 */
        fun fromDirectory(rootDir: File): SkillRegistry = SkillRegistry(FileSkillSource(rootDir))
    }
}
