package com.thatgfsj.qaa.core.tools

import com.thatgfsj.qaa.core.inference.LlmHandle
import com.thatgfsj.qaa.core.inference.MemoryProbe
import com.thatgfsj.qaa.core.inference.RetrySleeper
import java.io.File
import kotlinx.coroutines.delay

/** 可编程内存水位替身（附录 A.0）。 */
class FakeMemoryProbe(var level: Long = 4096L) : MemoryProbe {
    override fun availableBytes(): Long = level
}

/** 可脚本化 LLM 句柄替身（附录 A.0）。 */
class FakeLlmHandle : LlmHandle {

    var loaded: Boolean = true
    var refs: Int = 1
    var failUnload: Boolean = false
    var failReload: Boolean = false
    var failInject: Boolean = false
    var failRebuild: Boolean = false

    var unloadCount: Int = 0
    var reloadCount: Int = 0
    var injectCount: Int = 0
    var destroyCount: Int = 0
    var rebuildCount: Int = 0
    var lastInjectedPath: String? = null
    var lastInjectedSummary: String? = null

    override val isLoaded: Boolean get() = loaded

    override val referenceCount: Int get() = refs

    override fun contextPersisted(): Boolean = true

    override fun contextRebuilt(): Boolean = reloadCount > 0 || rebuildCount > 0

    override fun imageResultInjected(): Boolean = injectCount > 0

    override suspend fun unload(): Result<Unit> {
        unloadCount++
        if (failUnload) return Result.failure(IllegalStateException("fake unload failure"))
        loaded = false
        refs = 0
        return Result.success(Unit)
    }

    override suspend fun reload(): Result<Unit> {
        reloadCount++
        if (failReload) return Result.failure(IllegalStateException("fake reload failure"))
        loaded = true
        refs = 1
        return Result.success(Unit)
    }

    override suspend fun injectImageResult(imagePath: String, summary: String): Result<Unit> {
        injectCount++
        if (failInject) return Result.failure(IllegalStateException("fake inject failure"))
        lastInjectedPath = imagePath
        lastInjectedSummary = summary
        return Result.success(Unit)
    }

    override suspend fun destroy() {
        destroyCount++
        loaded = false
        refs = 0
        unloadCount = 0
        reloadCount = 0
        injectCount = 0
    }

    override suspend fun rebuild(): Result<Unit> {
        rebuildCount++
        if (failRebuild) return Result.failure(IllegalStateException("fake rebuild failure"))
        loaded = true
        refs = 1
        return Result.success(Unit)
    }
}

/** 可脚本化画图引擎替身：成功时按引擎路径真实落盘，供沙盒落盘校验。 */
class FakeImageGenEngine : ImageGenEngine {

    var failLoad: Boolean = false
    var failGenerate: Boolean = false
    var failUnload: Boolean = false
    var writeOutput: Boolean = true
    var generateDelayMillis: Long = 0L
    var summary: String = "一张测试图"

    var loadCount: Int = 0
    var generateCount: Int = 0
    var unloadCount: Int = 0
    var maxConcurrentGenerates: Int = 0
    var lastPrompt: String? = null
    var lastOutputPath: String? = null

    private var activeGenerates: Int = 0

    override suspend fun load(modelPath: String) {
        loadCount++
        if (failLoad) throw IllegalStateException("fake load failure")
    }

    override suspend fun generate(prompt: String, outputPath: String): Result<String> {
        generateCount++
        activeGenerates++
        maxConcurrentGenerates = maxOf(maxConcurrentGenerates, activeGenerates)
        try {
            lastPrompt = prompt
            lastOutputPath = outputPath
            if (generateDelayMillis > 0L) delay(generateDelayMillis)
            if (failGenerate) return Result.failure(IllegalStateException("fake generate failure"))
            if (writeOutput) {
                val file = File(outputPath)
                file.parentFile?.mkdirs()
                file.writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            return Result.success(summary)
        } finally {
            activeGenerates--
        }
    }

    override suspend fun unload() {
        unloadCount++
        if (failUnload) throw IllegalStateException("fake unload failure")
    }
}

/** 记录退避时长，避免真实等待。 */
class RecordingSleeper : RetrySleeper {

    val delays = mutableListOf<Long>()

    override suspend fun sleep(millis: Long) {
        delays += millis
    }
}
