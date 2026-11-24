package com.thatgfsj.qaa.core.inference

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * 内置冒烟模型首启释放器（GitHub Issue #6）。
 *
 * 首次启动时把 assets/models/ 下全部 .gguf 释放到 App 私有目录
 * （约定为 filesDir/models/），路径与文件名保持一致，实现安装即离线运行：
 *
 * - 幂等：目标存在且大小与源资产一致则跳过；不一致则重新释放；资产清单里的重名条目
 *   （同一模型被多处声明）只释放一次，返回列表也按文件名去重；
 * - 进度：[progress] 按全部模型累计字节数加权（0.0-1.0，StateFlow 合并高频中间值）；
 * - 结果：[releaseAll] 返回释放后的模型 File 列表，供 Issue #5 的 LlmEngine
 *   按路径直接加载（本期只交付释放，不做任何引擎加载调用）。
 *
 * 实现说明：先顺序读一遍源资产取得精确字节长度（本地 APK 内顺序读，冒烟模型
 * 约 40MB），作为幂等基准与进度权重；再按字节拷贝到同目录临时文件，大小校验
 * 通过后原子改名，避免留下半成品目标文件。
 *
 * @param assets 内置模型资产源（Android 侧为 [AndroidAssetSource]）
 * @param targetDir 释放目标目录（App 私有目录，约定 filesDir/models/）
 * @param ioDispatcher 拷贝 IO 调度器，测试可注入确定性调度器
 */
class AssetModelReleaser(
    private val assets: AssetSource,
    private val targetDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _progress = MutableStateFlow(IDLE)

    /** 串行化并发释放调用，防止两个启动路径同时拷贝同一模型。 */
    private val releaseMutex = Mutex()

    /** 释放总进度（0.0-1.0）；StateFlow 合并高频中间值，结束时必为 1.0。 */
    val progress: StateFlow<Float> = _progress.asStateFlow()

    /**
     * 释放全部内置模型到 [targetDir]（幂等，可安全重复调用）。
     *
     * @return 释放后的模型文件列表（含已存在被跳过者），顺序与
     *         [AssetSource.listModels] 一致
     * @throws IOException 目标目录创建失败、拷贝中断或大小校验失败
     */
    suspend fun releaseAll(): List<File> = releaseMutex.withLock {
        withContext(ioDispatcher) { releaseLocked() }
    }

    /** 供 Issue #5 的 LlmEngine 定位释放后的模型路径。 */
    fun releasedModelFile(name: String): File = File(targetDir, name)

    private suspend fun releaseLocked(): List<File> {
        _progress.value = IDLE
        // #98：资产清单可能重复声明同一模型——去重后只释放 / 返回一次，避免清单出现重复文件条目。
        val names = assets.listModels().distinct()
        if (names.isEmpty()) {
            _progress.value = COMPLETE
            return emptyList()
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("无法创建模型释放目录：" + targetDir.absolutePath)
        }
        // 第一遍：取得各源资产精确长度，作为幂等基准与进度权重。
        val sourceSizes = names.map { name -> ModelSize(name, lengthOf(name)) }
        val totalBytes = maxOf(1L, sourceSizes.sumOf { it.size })
        var doneBytes = 0L
        val released = mutableListOf<File>()
        for (entry in sourceSizes) {
            val target = File(targetDir, entry.name)
            if (target.isFile && target.length() == entry.size) {
                doneBytes += entry.size
                _progress.value = fraction(doneBytes, totalBytes)
                released += target
                continue
            }
            copyAsset(entry.name, target, entry.size) { copied ->
                _progress.value = fraction(doneBytes + copied, totalBytes)
            }
            doneBytes += entry.size
            released += target
        }
        _progress.value = COMPLETE
        return released
    }

    /** 顺序读一遍源资产，返回其精确字节长度。 */
    private fun lengthOf(name: String): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        return assets.open(name).use { stream ->
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
    }

    /** 拷贝单个模型：写临时文件 -> 大小校验 -> 原子改名；失败清理临时文件。 */
    private suspend fun copyAsset(
        name: String,
        target: File,
        expectedSize: Long,
        onCopied: (Long) -> Unit,
    ) {
        val tmp = File(targetDir, name + TMP_SUFFIX)
        try {
            assets.open(name).use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onCopied(copied)
                        // 块间让出调度：长拷贝中释放协程保持可取消，
                        // 进度采集方也能逐块观测更新而非被合并。
                        yield()
                    }
                    output.flush()
                }
            }
            if (tmp.length() != expectedSize) {
                throw IOException(
                    "模型 " + name + " 释放大小校验失败：期望 " +
                        expectedSize + " 字节，实际 " + tmp.length() + " 字节",
                )
            }
            if (target.exists()) {
                target.delete()
            }
            if (!tmp.renameTo(target)) {
                throw IOException(
                    "模型 " + name + " 释放改名失败：" + tmp.absolutePath + " -> " + target.absolutePath,
                )
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun fraction(done: Long, total: Long): Float =
        (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, COMPLETE)

    /** 待释放模型条目：模型名 + 源资产精确字节数。 */
    private data class ModelSize(val name: String, val size: Long)

    private companion object {
        const val IDLE = 0f
        const val COMPLETE = 1f
        const val BUFFER_SIZE = 64 * 1024
        const val TMP_SUFFIX = ".tmp"
    }
}
