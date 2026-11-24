package com.thatgfsj.qaa.core.inference

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * AssetModelReleaser JVM 单测（GitHub Issue #6）。
 *
 * fake AssetSource + @TempDir 隔离文件系统，全程不依赖 android.content.Context，
 * 覆盖：空资产、首启释放、幂等跳过、大小不符重释放、多模型释放、
 * 进度上报与中途断流清理。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetModelReleaserTest {

    @Test
    fun 空资产列表_返回空且进度归一(@TempDir dir: File) = runTest {
        val releaser = AssetModelReleaser(FakeAssetSource(linkedMapOf()), dir, Dispatchers.Unconfined)
        assertTrue(releaser.releaseAll().isEmpty())
        assertEquals(1f, releaser.progress.value)
    }

    @Test
    fun 首启释放_文件名与内容一致(@TempDir dir: File) = runTest {
        val models = linkedMapOf("smoke.gguf" to bytes(4096))
        val releaser = AssetModelReleaser(FakeAssetSource(models), dir, Dispatchers.Unconfined)
        val released = releaser.releaseAll()
        assertEquals(listOf(File(dir, "smoke.gguf")), released)
        assertArrayEquals(models.getValue("smoke.gguf"), File(dir, "smoke.gguf").readBytes())
        assertEquals(1f, releaser.progress.value)
    }

    @Test
    fun 幂等跳过_目标大小一致时不重写(@TempDir dir: File) = runTest {
        val original = bytes(4096)
        val models = linkedMapOf("smoke.gguf" to original)
        val releaser = AssetModelReleaser(FakeAssetSource(models), dir, Dispatchers.Unconfined)
        releaser.releaseAll()
        // 模拟资产内容变化但长度不变：按契约（大小一致即跳过）不得重写目标。
        models["smoke.gguf"] = swappedSameLength(original)
        val releasedAgain = releaser.releaseAll()
        assertEquals(listOf(File(dir, "smoke.gguf")), releasedAgain)
        assertArrayEquals(original, File(dir, "smoke.gguf").readBytes())
    }

    @Test
    fun 大小不符_重新释放覆盖目标(@TempDir dir: File) = runTest {
        val data = bytes(4096)
        val models = linkedMapOf("smoke.gguf" to data)
        val stale = File(dir, "smoke.gguf")
        stale.writeBytes(byteArrayOf(1, 2, 3)) // 残留半成品：大小与源资产不符
        val releaser = AssetModelReleaser(FakeAssetSource(models), dir, Dispatchers.Unconfined)
        val released = releaser.releaseAll()
        assertEquals(listOf(stale), released)
        assertArrayEquals(data, stale.readBytes())
        assertEquals(1f, releaser.progress.value)
    }

    @Test
    fun 多模型释放_全部落位且顺序保持(@TempDir dir: File) = runTest {
        val models = linkedMapOf(
            "m-a.gguf" to bytes(3072),
            "m-b.gguf" to bytes(5120),
            "m-c.gguf" to bytes(7168),
        )
        val releaser = AssetModelReleaser(FakeAssetSource(models), dir, Dispatchers.Unconfined)
        val released = releaser.releaseAll()
        assertEquals(listOf("m-a.gguf", "m-b.gguf", "m-c.gguf"), released.map { it.name })
        models.forEach { (name, data) ->
            assertArrayEquals(data, File(dir, name).readBytes())
        }
        assertEquals(1f, releaser.progress.value)
    }

    @Test
    fun 进度上报_单调不减且收敛至1(@TempDir dir: File) = runTest {
        val models = linkedMapOf(
            "big.gguf" to bytes(196 * 1024),
            "small.gguf" to bytes(96 * 1024),
        )
        // 释放协程走测试调度器：copyAsset 块间 yield 让出调度，
        // 与 UNDISPATCHED 启动的采集协程在同一调度器上确定性地逐块交错。
        val releaser = AssetModelReleaser(
            FakeAssetSource(models),
            dir,
            StandardTestDispatcher(testScheduler),
        )
        val seen = mutableListOf<Float>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            releaser.progress.collect { seen.add(it) }
        }
        releaser.releaseAll()
        collector.cancel()
        assertEquals(0f, seen.first())
        assertEquals(1f, seen.last())
        assertTrue(seen.size >= 3, "应存在中间进度值，实际采样数：" + seen.size)
        assertTrue(seen.all { it in 0f..1f })
        assertTrue(seen.zipWithNext().all { (previous, next) -> next >= previous })
    }

    @Test
    fun 中途断流_清理半成品且异常上抛(@TempDir dir: File) = runTest {
        val models = linkedMapOf("broken.gguf" to bytes(4096))
        val fake = FakeAssetSource(models).apply {
            midStreamFailAfterBytes["broken.gguf"] = 1024
        }
        val releaser = AssetModelReleaser(fake, dir, Dispatchers.Unconfined)
        val error = runCatching { releaser.releaseAll() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(File(dir, "broken.gguf").exists())
        assertTrue(dir.listFiles().isNullOrEmpty(), "不应残留 .tmp 半成品")
        assertTrue(releaser.progress.value < 1f)
    }

    @Test
    fun 重名资产_只释放一份且返回列表去重(@TempDir dir: File) = runTest {
        val data = bytes(2048)
        // #98：资产源（或打包清单）出现重名时释放结果不得重复登记同一路径，
        // 否则上层清单会为同一文件凭空多出一条影子模型。
        val duplicated = object : AssetSource {
            override fun listModels(): List<String> = listOf("dup.gguf", "dup.gguf")
            override fun open(name: String): InputStream = ByteArrayInputStream(data)
        }
        val releaser = AssetModelReleaser(duplicated, dir, Dispatchers.Unconfined)
        val released = releaser.releaseAll()
        assertEquals(listOf(File(dir, "dup.gguf")), released)
        assertArrayEquals(data, File(dir, "dup.gguf").readBytes())
        assertEquals(1f, releaser.progress.value)
    }
}

/** 确定性伪字节内容。 */
private fun bytes(size: Int): ByteArray = ByteArray(size) { i -> ((i * 17 + 3) and 0xFF).toByte() }

/** 生成与源等长但内容不同的字节（验证幂等跳过只依据大小，不比对内容）。 */
private fun swappedSameLength(source: ByteArray): ByteArray =
    ByteArray(source.size) { i -> ((i * 29 + 11) and 0xFF).toByte() }

/** 内存版 AssetSource：内容可热替换，可注入"第 2 次 open 起读 N 字节后断流"。 */
private class FakeAssetSource(
    private val models: LinkedHashMap<String, ByteArray>,
) : AssetSource {

    /** 模型名 -> 第 2 次及以后的 open 读取该字节数后抛 IOException。 */
    val midStreamFailAfterBytes = mutableMapOf<String, Int>()

    private val openCounts = mutableMapOf<String, Int>()

    override fun listModels(): List<String> = models.keys.toList()

    override fun open(name: String): InputStream {
        val openIndex = (openCounts[name] ?: 0) + 1
        openCounts[name] = openIndex
        val data = models.getValue(name)
        val failAfter = midStreamFailAfterBytes[name]
        return if (openIndex >= 2 && failAfter != null) {
            FailingAfterStream(data, failAfter)
        } else {
            ByteArrayInputStream(data)
        }
    }
}

/** 读取 failAfter 字节后抛 IOException 的流，用于模拟拷贝中途断流。 */
private class FailingAfterStream(
    private val data: ByteArray,
    private val failAfter: Int,
) : InputStream() {
    private var pos = 0

    override fun read(): Int {
        if (pos >= failAfter) throw IOException("fake 中途断流")
        val value = data[pos].toInt() and 0xFF
        pos += 1
        return value
    }
}
