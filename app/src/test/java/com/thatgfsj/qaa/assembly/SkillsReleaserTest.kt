package com.thatgfsj.qaa.assembly

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 内置技能释放单测（Issue #89）：首启写入、幂等跳过、内容更新与失败隔离。 */
class SkillsReleaserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun 首启释放_按技能目录写入文件() {
        val root = tempFolder.newFolder("skills")
        val source = FakeSkillsAssetSource(
            mapOf(
                "workspace-browse" to mapOf("SKILL.md" to "浏览正文"),
                "careful-edit" to mapOf("SKILL.md" to "修改正文"),
            ),
        )

        val report = SkillsReleaser(source, root).release()

        assertEquals(2, report.skillDirectories)
        assertEquals(2, report.released.size)
        assertEquals(0, report.skipped)
        assertTrue(report.ok)
        assertEquals("浏览正文", File(File(root, "workspace-browse"), "SKILL.md").readText())
        assertEquals("修改正文", File(File(root, "careful-edit"), "SKILL.md").readText())
    }

    @Test
    fun 重复释放_内容一致时全部跳过() {
        val root = tempFolder.newFolder("skills-idempotent")
        val source = FakeSkillsAssetSource(mapOf("workspace-browse" to mapOf("SKILL.md" to "正文")))

        SkillsReleaser(source, root).release()
        val second = SkillsReleaser(source, root).release()

        assertTrue(second.released.isEmpty())
        assertEquals(1, second.skipped)
        assertTrue(second.ok)
        assertEquals("正文", File(File(root, "workspace-browse"), "SKILL.md").readText())
    }

    @Test
    fun 内容变更_覆盖旧文件() {
        val root = tempFolder.newFolder("skills-update")
        SkillsReleaser(
            FakeSkillsAssetSource(mapOf("workspace-browse" to mapOf("SKILL.md" to "旧版"))),
            root,
        ).release()

        val report = SkillsReleaser(
            FakeSkillsAssetSource(mapOf("workspace-browse" to mapOf("SKILL.md" to "新版内容"))),
            root,
        ).release()

        assertEquals(1, report.released.size)
        assertEquals("新版内容", File(File(root, "workspace-browse"), "SKILL.md").readText())
    }

    @Test
    fun 同大小不同内容_按内容判定并更新() {
        val root = tempFolder.newFolder("skills-same-size")
        SkillsReleaser(
            FakeSkillsAssetSource(mapOf("workspace-browse" to mapOf("SKILL.md" to "aaaa"))),
            root,
        ).release()

        val report = SkillsReleaser(
            FakeSkillsAssetSource(mapOf("workspace-browse" to mapOf("SKILL.md" to "bbbb"))),
            root,
        ).release()

        assertEquals(1, report.released.size)
        assertEquals("bbbb", File(File(root, "workspace-browse"), "SKILL.md").readText())
    }

    @Test
    fun 单技能读取失败_记录原因且不影响其他技能() {
        val root = tempFolder.newFolder("skills-broken")
        val source = FakeSkillsAssetSource(
            mapOf(
                "broken" to mapOf("SKILL.md" to "损坏"),
                "healthy" to mapOf("SKILL.md" to "正常"),
            ),
            broken = setOf("broken"),
        )

        val report = SkillsReleaser(source, root).release()

        assertFalse(report.ok)
        assertEquals(1, report.failed.size)
        assertTrue(report.failed.single().contains("broken"))
        assertEquals(1, report.released.size)
        assertEquals("正常", File(File(root, "healthy"), "SKILL.md").readText())
    }

    @Test
    fun 空资产目录_不创建目标目录() {
        val root = File(tempFolder.root, "empty-skills")

        val report = SkillsReleaser(FakeSkillsAssetSource(emptyMap()), root).release()

        assertTrue(report.released.isEmpty())
        assertFalse(root.exists())
    }

    /** 内存资产源：目录 -> 文件名 -> 文本；[broken] 目录在列目录时抛错。 */
    private class FakeSkillsAssetSource(
        private val skills: Map<String, Map<String, String>>,
        private val broken: Set<String> = emptySet(),
    ) : SkillsAssetSource {

        override fun listSkillDirectories(): List<String> = skills.keys.sorted()

        override fun listSkillFiles(directory: String): List<String> =
            if (directory in broken) {
                throw IOException("资产损坏")
            } else {
                skills[directory]?.keys?.sorted().orEmpty()
            }

        override fun open(directory: String, name: String): InputStream {
            val text = skills[directory]?.get(name) ?: throw IOException("缺少资产")
            return ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        }
    }
}
