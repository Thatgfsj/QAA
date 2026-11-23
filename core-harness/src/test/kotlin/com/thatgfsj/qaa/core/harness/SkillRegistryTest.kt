package com.thatgfsj.qaa.core.harness

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SkillRegistry 单测（设计文档第 8 章：扫描注册、稳定描述段、frontmatter
 * 容错、超长截断、按需加载、重扫与 /skills 状态报告）。
 */
class SkillRegistryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ---------- 扫描与稳定描述段 ----------

    @Test
    fun 扫描注册_内置示例技能全部注册() {
        val registry = SkillRegistry.fromDirectory(resourceSkillsDir())
        val status = registry.status()

        assertEquals(2, status.scannedDirectories)
        assertEquals(2, status.registeredCount)
        assertEquals(0, status.skippedCount)
        assertEquals(listOf("daily-report", "workspace-browse"), status.skills.map { it.name })
        assertEquals(
            "根据当日会话与文件变更生成结构化工作简报",
            status.skills.first { it.name == "daily-report" }.description,
        )
        assertTrue(
            registry.describeAll().contains("- workspace-browse: 浏览工作区文件并汇总目录结构与关键内容"),
        )
    }

    @Test
    fun 描述段_两次调用字节级一致且按name字典序() {
        val root = tempFolder.newFolder("stable-section")
        writeSkill(root, "z-last", skillMarkdown("z-last", "最后"))
        writeSkill(root, "a-first", skillMarkdown("a-first", "最先"))
        writeSkill(root, "m-mid", skillMarkdown("m-mid", "中间"))

        val registry = SkillRegistry.fromDirectory(root)
        val first = registry.describeAll()
        val second = registry.describeAll()

        assertEquals(first, second)
        assertArrayEquals(
            first.toByteArray(StandardCharsets.UTF_8),
            second.toByteArray(StandardCharsets.UTF_8),
        )
        assertEquals(
            listOf(
                SkillRegistry.SKILLS_SECTION_HEADER,
                "- a-first: 最先",
                "- m-mid: 中间",
                "- z-last: 最后",
            ),
            first.lines(),
        )
    }

    @Test
    fun 描述段_空注册表返回空串() {
        val registry = SkillRegistry.fromDirectory(tempFolder.newFolder("empty-skills"))

        assertEquals("", registry.describeAll())
        assertEquals(0, registry.status().registeredCount)
        assertTrue(registry.status().skills.isEmpty())
    }

    // ---------- frontmatter 容错 ----------

    @Test
    fun frontmatter_缺少name告警跳过() {
        val registry = registryFor(
            "no-name" to "---\ndescription: 只有描述\n---\n正文",
            "healthy" to skillMarkdown("healthy", "合规技能"),
        )

        val status = registry.status()
        assertEquals(1, status.registeredCount)
        val skip = status.skipped.single()
        assertEquals("no-name", skip.directory)
        assertEquals(SkillSkipReason.MISSING_NAME, skip.reason)
        assertTrue(registry.describeAll().contains("- healthy: 合规技能"))
        assertFalse(registry.describeAll().contains("no-name"))
    }

    @Test
    fun frontmatter_缺少description告警跳过() {
        val registry = registryFor("no-desc" to "---\nname: no-desc\n---\n正文")

        val skip = registry.status().skipped.single()
        assertEquals(SkillSkipReason.MISSING_DESCRIPTION, skip.reason)
        assertEquals(0, registry.status().registeredCount)
    }

    @Test
    fun frontmatter_无结束分隔符告警跳过() {
        val registry = registryFor(
            "unclosed" to "---\nname: unclosed\ndescription: 缺少结束符\n",
        )

        val skip = registry.status().skipped.single()
        assertEquals(SkillSkipReason.UNCLOSED_FRONTMATTER, skip.reason)
        assertEquals(0, registry.status().registeredCount)
    }

    @Test
    fun frontmatter_多余字段告警跳过() {
        val registry = registryFor(
            "extra-field" to "---\nname: extra-field\ndescription: 多余字段\nallowed-tools: read\n---\n",
        )

        val skip = registry.status().skipped.single()
        assertEquals(SkillSkipReason.UNEXPECTED_FIELD, skip.reason)
        assertTrue(skip.detail.contains("allowed-tools"))
        assertEquals(0, registry.status().registeredCount)
    }

    @Test
    fun frontmatter_无frontmatter告警跳过() {
        val registry = registryFor("plain" to "# 只有正文\n没有 frontmatter\n")

        val skip = registry.status().skipped.single()
        assertEquals(SkillSkipReason.MISSING_FRONTMATTER, skip.reason)
    }

    @Test
    fun frontmatter_非法行告警跳过() {
        val registry = registryFor(
            "bad-line" to "---\nname: bad-line\ndescription: 行格式错误\n这一行没有冒号\n---\n",
        )

        val skip = registry.status().skipped.single()
        assertEquals(SkillSkipReason.MALFORMED_LINE, skip.reason)
    }

    @Test
    fun 超长description_截断到上限并告警() {
        val description = "描".repeat(SkillFrontmatterParser.MAX_DESCRIPTION_LENGTH + 20)
        val registry = registryFor("long-desc" to skillMarkdown("long-desc", description))

        val status = registry.status()
        assertEquals(1, status.registeredCount)
        val registered = status.skills.single()
        assertEquals(
            SkillFrontmatterParser.MAX_DESCRIPTION_LENGTH,
            registered.description.length,
        )
        assertTrue(registered.description.endsWith("…"))

        val warning = status.warnings.single()
        assertEquals("long-desc", warning.directory)
        assertTrue(warning.message.contains("截断"))
        assertTrue(registry.describeAll().contains(registered.description))
    }

    @Test
    fun 重名技能_后者跳过并保留先扫描者() {
        val root = tempFolder.newFolder("duplicates")
        writeSkill(root, "a-one", skillMarkdown("same", "先声明的技能"))
        writeSkill(root, "b-two", skillMarkdown("same", "后声明的技能"))

        val registry = SkillRegistry.fromDirectory(root)
        val status = registry.status()

        assertEquals(1, status.registeredCount)
        assertEquals("先声明的技能", status.skills.single().description)
        val skip = status.skipped.single()
        assertEquals("b-two", skip.directory)
        assertEquals(SkillSkipReason.DUPLICATE_NAME, skip.reason)
        assertTrue(skip.detail.contains("a-one"))
    }

    @Test
    fun frontmatter_兼容BOM与CRLF行尾() {
        val markdown = "\uFEFF---\r\nname: crlf\r\ndescription: 兼容行尾\r\n---\r\n正文\r\n"
        val registry = registryFor("crlf" to markdown)

        val status = registry.status()
        assertEquals(listOf("crlf"), status.skills.map { it.name })
        assertEquals("兼容行尾", status.skills.single().description)
        assertEquals(markdown, registry.loadSkill("crlf"))
    }

    // ---------- 按需加载与重扫 ----------

    @Test
    fun 按需加载_返回全文并记录加载状态() {
        val markdown = skillMarkdown("reader", "读取技能")
        val registry = registryFor("reader" to markdown)

        assertFalse(registry.status().skills.single().loaded)
        val text = registry.loadSkill("reader")
        assertNotNull(text)
        assertEquals(markdown, text)
        assertTrue(registry.status().skills.single().loaded)
    }

    @Test
    fun 按需加载_未知技能返回null() {
        val registry = registryFor("reader" to skillMarkdown("reader", "读取技能"))

        assertNull(registry.loadSkill("missing"))
        assertFalse(registry.status().skills.single().loaded)
    }

    @Test
    fun rescan_感知增删并重置加载标记() {
        val root = tempFolder.newFolder("rescan")
        writeSkill(root, "alpha", skillMarkdown("alpha", "甲"))
        val registry = SkillRegistry.fromDirectory(root)
        assertNotNull(registry.loadSkill("alpha"))
        assertTrue(registry.status().skills.single().loaded)

        File(root, "alpha").deleteRecursively()
        writeSkill(root, "beta", skillMarkdown("beta", "乙"))
        val report = registry.rescan()

        assertEquals(1, report.scannedDirectories)
        assertEquals(1, report.registeredCount)
        assertEquals(listOf("beta"), registry.status().skills.map { it.name })
        assertNull(registry.loadSkill("alpha"))
        assertFalse(registry.status().skills.single().loaded)
    }

    @Test
    fun rescan_内容未变时描述段保持一致() {
        val root = tempFolder.newFolder("rescan-stable")
        writeSkill(root, "alpha", skillMarkdown("alpha", "甲"))
        val registry = SkillRegistry.fromDirectory(root)
        val before = registry.describeAll()

        registry.rescan()

        assertEquals(before, registry.describeAll())
    }

    @Test
    fun 状态报告_扫描数跳过数与原因齐全() {
        val root = tempFolder.newFolder("status")
        writeSkill(root, "ok", skillMarkdown("ok", "正常技能"))
        writeSkill(root, "no-desc", "---\nname: no-desc\n---\n正文")
        File(root, "empty").mkdirs()

        val registry = SkillRegistry.fromDirectory(root)
        val status = registry.status()

        assertEquals(3, status.scannedDirectories)
        assertEquals(1, status.registeredCount)
        assertEquals(2, status.skippedCount)
        assertEquals(
            setOf(SkillSkipReason.MISSING_DESCRIPTION, SkillSkipReason.UNREADABLE),
            status.skipped.map { it.reason }.toSet(),
        )
        assertTrue(status.skipped.all { it.detail.isNotBlank() })
        assertEquals("- ok: 正常技能", registry.describeAll().lines().last())
    }

    // ---------- FileSkillSource ----------

    @Test
    fun FileSkillSource_只列技能目录并读UTF8文本() {
        val root = tempFolder.newFolder("source")
        val markdown = "---\nname: skill-a\ndescription: 甲\n---\n正文"
        writeSkill(root, "skill-a", markdown)
        File(root, "notes.txt").writeText("不是技能目录", StandardCharsets.UTF_8)
        val hidden = File(root, ".hidden")
        hidden.mkdirs()
        File(hidden, FileSkillSource.SKILL_FILE_NAME).writeText(
            "---\nname: hidden\ndescription: 隐藏\n---\n",
            StandardCharsets.UTF_8,
        )

        val source = FileSkillSource(root)
        assertEquals(listOf("skill-a"), source.listSkillDirectories())
        assertEquals(markdown, source.readSkillFile("skill-a"))
        assertNull(source.readSkillFile("missing"))
        assertNull(source.readSkillFile("../skill-a"))
        assertNull(source.readSkillFile(""))
    }

    @Test
    fun FileSkillSource_根目录不存在时为空() {
        val source = FileSkillSource(File(tempFolder.root, "absent"))

        assertTrue(source.listSkillDirectories().isEmpty())
        assertNull(source.readSkillFile("anything"))
    }

    // ---------- 辅助 ----------

    private fun resourceSkillsDir(): File {
        val url = requireNotNull(javaClass.getResource("/skills")) { "测试资源 /skills 缺失" }
        return File(url.toURI())
    }

    private fun registryFor(vararg skills: Pair<String, String>): SkillRegistry {
        val root = tempFolder.newFolder()
        skills.forEach { (directory, markdown) -> writeSkill(root, directory, markdown) }
        return SkillRegistry.fromDirectory(root)
    }

    private fun writeSkill(root: File, directory: String, markdown: String) {
        val skillDir = File(root, directory)
        check(skillDir.mkdirs() || skillDir.isDirectory) { "无法创建技能目录：$skillDir" }
        File(skillDir, FileSkillSource.SKILL_FILE_NAME)
            .writeText(markdown, StandardCharsets.UTF_8)
    }

    private fun skillMarkdown(name: String, description: String): String =
        "---\nname: $name\ndescription: $description\n---\n\n# 正文：$name\n"
}
