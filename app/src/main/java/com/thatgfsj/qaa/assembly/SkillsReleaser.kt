package com.thatgfsj.qaa.assembly

import android.content.res.AssetManager
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 内置技能资产源：释放器只依赖本接口，JVM 单测可用内存实现。
 *
 * assets 与文件系统语义一致：技能一目录、目录下若干文件；实现方负责过滤非法名称。
 */
interface SkillsAssetSource {

    /** 列出技能目录名（实现方保证确定性顺序）。 */
    fun listSkillDirectories(): List<String>

    /** 列出技能目录下的文件名（仅一层）。 */
    fun listSkillFiles(directory: String): List<String>

    /** 打开技能目录下指定文件；调用方负责关闭。 */
    fun open(directory: String, name: String): InputStream
}

/** [SkillsAssetSource] 的 Android 实现：桥接 assets/skills（Issue #89）。 */
class AndroidSkillsAssetSource(
    private val assets: AssetManager,
    private val root: String = ASSET_ROOT,
) : SkillsAssetSource {

    override fun listSkillDirectories(): List<String> =
        assets.list(root).orEmpty().filter(::isSafeName).sorted()

    override fun listSkillFiles(directory: String): List<String> =
        if (isSafeName(directory)) {
            assets.list(root + "/" + directory).orEmpty().filter(::isSafeName).sorted()
        } else {
            emptyList()
        }

    override fun open(directory: String, name: String): InputStream {
        require(isSafeName(directory) && isSafeName(name)) { "非法技能资产路径" }
        return assets.open(root + "/" + directory + "/" + name)
    }

    companion object {
        /** APK 内置技能目录（相对 assets/）。 */
        const val ASSET_ROOT = "skills"

        private fun isSafeName(name: String): Boolean =
            name.isNotBlank() &&
                name != "." &&
                name != ".." &&
                !name.contains('/') &&
                !name.contains('\\') &&
                !name.startsWith(".")
    }
}

/** 释放结果：技能目录数、写入文件、内容一致跳过数与失败明细（失败不阻断启动）。 */
data class SkillsReleaseReport(
    val skillDirectories: Int = 0,
    val released: List<File> = emptyList(),
    val skipped: Int = 0,
    val failed: List<String> = emptyList(),
) {
    val ok: Boolean get() = failed.isEmpty()
}

/**
 * 内置技能首启释放器（Issue #89）：assets/skills → filesDir/skills，幂等。
 *
 * 与 AssetModelReleaser 同一模式：目标内容一致时跳过，不一致则写临时文件再原子
 * 改名，不留半成品；单文件失败只记录，不阻断启动（注册表会跳过空目录）。
 * 在 SkillRegistry 构造前调用，保证首启 /skills 与 AGENT 描述段无需联网。
 */
class SkillsReleaser(
    private val source: SkillsAssetSource,
    private val targetDir: File,
) {

    /** 同步释放（技能仅数 KB，不引入额外调度）。 */
    fun release(): SkillsReleaseReport {
        val directories = source.listSkillDirectories()
        if (directories.isEmpty()) return SkillsReleaseReport()
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            return SkillsReleaseReport(failed = listOf("无法创建技能目录：" + targetDir.absolutePath))
        }
        val released = mutableListOf<File>()
        val failed = mutableListOf<String>()
        var skipped = 0
        directories.forEach { directory ->
            val files = try {
                source.listSkillFiles(directory)
            } catch (error: Exception) {
                failed += directory + "：读取目录失败（" + (error.message ?: "IO 错误") + "）"
                return@forEach
            }
            files.forEach { name ->
                when (val outcome = copy(directory, name)) {
                    is CopyOutcome.Written -> released += outcome.file
                    CopyOutcome.Skipped -> skipped += 1
                    is CopyOutcome.Failed -> failed += outcome.detail
                }
            }
        }
        return SkillsReleaseReport(directories.size, released, skipped, failed)
    }

    /** 单文件释放：内容一致跳过；否则临时文件写入 + 原子改名。 */
    private fun copy(directory: String, name: String): CopyOutcome {
        val target = File(File(targetDir, directory), name)
        val tmp = File(target.path + TMP_SUFFIX)
        return try {
            val bytes = source.open(directory, name).use { it.readBytes() }
            if (target.isFile && target.readBytes().contentEquals(bytes)) {
                CopyOutcome.Skipped
            } else {
                target.parentFile?.mkdirs()
                tmp.writeBytes(bytes)
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) throw IOException("改名失败：" + tmp.absolutePath)
                CopyOutcome.Written(target)
            }
        } catch (error: Exception) {
            tmp.delete()
            CopyOutcome.Failed(directory + "/" + name + "：" + (error.message ?: "IO 错误"))
        }
    }

    private sealed interface CopyOutcome {
        data class Written(val file: File) : CopyOutcome

        data object Skipped : CopyOutcome

        data class Failed(val detail: String) : CopyOutcome
    }

    private companion object {
        const val TMP_SUFFIX = ".tmp"
    }
}
