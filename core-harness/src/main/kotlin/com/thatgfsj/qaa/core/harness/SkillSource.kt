package com.thatgfsj.qaa.core.harness

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * 技能来源端口（设计文档 8.1）：列出技能目录并读取 SKILL.md 全文。
 *
 * 端口只暴露目录名与文本，不暴露文件系统细节；JVM 测试与 App filesDir/skills
 * 可共用 [FileSkillSource]，assets 释放目录只需给出根目录即可。
 */
interface SkillSource {
    /**
     * 列出技能目录名（顺序不保证；[SkillRegistry] 会自行排序以获得稳定结果）。
     * 目录内是否含 SKILL.md 由 [readSkillFile] 判定。
     */
    fun listSkillDirectories(): List<String>

    /**
     * 读取指定技能目录下 SKILL.md 的全文。
     *
     * @return 文件内容；目录名非法、文件缺失或不可读时返回 null（不抛异常）。
     */
    fun readSkillFile(directory: String): String?
}

/**
 * 基于 [File] 目录的技能来源（一技能一目录，目录名即 skill id）。
 *
 * 只遍历根目录下一层：隐藏目录与普通文件不算技能目录。读取时拒绝含路径
 * 分隔符或为 "."/".." 的目录名，避免越出根目录。
 */
class FileSkillSource(private val rootDir: File) : SkillSource {

    override fun listSkillDirectories(): List<String> =
        rootDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    override fun readSkillFile(directory: String): String? {
        if (!isSafeDirectoryName(directory)) return null
        val file = File(File(rootDir, directory), SKILL_FILE_NAME)
        if (!file.isFile) return null
        return try {
            file.readText(StandardCharsets.UTF_8)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        /** 技能定义文件名（设计文档 8.1）。 */
        const val SKILL_FILE_NAME = "SKILL.md"

        private fun isSafeDirectoryName(name: String): Boolean =
            name.isNotBlank() &&
                name != "." &&
                name != ".." &&
                !name.contains('/') &&
                !name.contains('\\')
    }
}
