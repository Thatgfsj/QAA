package com.thatgfsj.qaa.core.inference

import kotlinx.coroutines.delay

/** 可编程内存水位替身（附录 A.0）。 */
class FakeMemoryProbe(var level: Long = 4096L) : MemoryProbe {

    val readings = mutableListOf<Long>()

    override fun availableBytes(): Long {
        readings += level
        return level
    }
}

/** 可脚本化 LLM 句柄替身：记录卸载/重载/回注/销毁重建次数与最后一次回注内容。 */
class FakeLlmHandle : LlmHandle {

    var loaded: Boolean = true
    var refs: Int = 1
    var persisted: Boolean = true
    var rebuilt: Boolean = false
    var injected: Boolean = false

    var failUnload: Boolean = false
    var failReload: Boolean = false
    var failInject: Boolean = false
    var failRebuild: Boolean = false
    var unloadDelayMillis: Long = 0L

    var unloadCount: Int = 0
    var reloadCount: Int = 0
    var injectCount: Int = 0
    var destroyCount: Int = 0
    var rebuildCount: Int = 0
    var lastInjectedPath: String? = null
    var lastInjectedSummary: String? = null

    override val isLoaded: Boolean get() = loaded

    override val referenceCount: Int get() = refs

    override fun contextPersisted(): Boolean = persisted

    override fun contextRebuilt(): Boolean = rebuilt

    override fun imageResultInjected(): Boolean = injected

    override suspend fun unload(): Result<Unit> {
        unloadCount++
        if (unloadDelayMillis > 0L) delay(unloadDelayMillis)
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
        rebuilt = true
        return Result.success(Unit)
    }

    override suspend fun injectImageResult(imagePath: String, summary: String): Result<Unit> {
        injectCount++
        if (failInject) return Result.failure(IllegalStateException("fake inject failure"))
        injected = true
        lastInjectedPath = imagePath
        lastInjectedSummary = summary
        return Result.success(Unit)
    }

    override suspend fun destroy() {
        destroyCount++
        loaded = false
        refs = 0
        rebuilt = false
        injected = false
    }

    override suspend fun rebuild(): Result<Unit> {
        rebuildCount++
        if (failRebuild) return Result.failure(IllegalStateException("fake rebuild failure"))
        loaded = true
        refs = 1
        rebuilt = true
        injected = false
        return Result.success(Unit)
    }
}

/** 可脚本化画图引擎句柄替身。 */
class FakeImageGenHandle : ImageGenHandle {

    var loaded: Boolean = false
    var refs: Int = 0
    var failLoad: Boolean = false
    var failGenerate: Boolean = false
    var failUnload: Boolean = false
    var summary: String = "一张测试图"

    var loadCount: Int = 0
    var generateCount: Int = 0
    var unloadCount: Int = 0
    var destroyCount: Int = 0
    var lastPrompt: String? = null
    var lastOutputPath: String? = null

    override val isLoaded: Boolean get() = loaded

    override val referenceCount: Int get() = refs

    override suspend fun load(modelPath: String): Result<Unit> {
        loadCount++
        if (failLoad) return Result.failure(IllegalStateException("fake load failure"))
        loaded = true
        refs = 1
        return Result.success(Unit)
    }

    override suspend fun generate(prompt: String, outputPath: String): Result<String> {
        generateCount++
        lastPrompt = prompt
        lastOutputPath = outputPath
        if (failGenerate) return Result.failure(IllegalStateException("fake generate failure"))
        return Result.success(summary)
    }

    override suspend fun unload(): Result<Unit> {
        unloadCount++
        if (failUnload) return Result.failure(IllegalStateException("fake unload failure"))
        loaded = false
        refs = 0
        return Result.success(Unit)
    }

    override suspend fun destroy() {
        destroyCount++
        loaded = false
        refs = 0
    }
}

/** 可编程落盘校验替身。 */
class FakeOutputFileProbe : OutputFileProbe {

    var result: OutputFileCheck = OutputFileCheck.Valid(2048L)
    val inspected = mutableListOf<String>()

    override suspend fun inspect(sandboxPath: String): OutputFileCheck {
        inspected += sandboxPath
        return result
    }
}

/** 记录退避时长的替身，避免单测真实等待。 */
class RecordingSleeper : RetrySleeper {

    val delays = mutableListOf<Long>()

    override suspend fun sleep(millis: Long) {
        delays += millis
    }
}

/** 状态机测试夹具：全成功替身 + 六边驱动助手。 */
class CoordinatorFixture(
    val llm: FakeLlmHandle = FakeLlmHandle(),
    val imageGen: FakeImageGenHandle = FakeImageGenHandle(),
    val probe: FakeMemoryProbe = FakeMemoryProbe(),
    val output: FakeOutputFileProbe = FakeOutputFileProbe(),
    val sleeper: RecordingSleeper = RecordingSleeper(),
) {

    val coordinator: ModelMemoryCoordinator = ModelMemoryCoordinator(
        llm = llm,
        imageGen = imageGen,
        memoryProbe = probe,
        outputProbe = output,
        config = CoordinatorConfig(memorySafeWatermarkBytes = 100L),
        sleeper = sleeper,
    )

    /** 沿合法迁移边把状态机推到 target（要求替身全部成功）。 */
    suspend fun driveTo(target: CoordinatorState) {
        repeat(6) {
            if (coordinator.state.value == target) return
            val next = when (coordinator.state.value) {
                CoordinatorState.LLM_LOADED -> CoordinatorState.LLM_UNLOADING
                CoordinatorState.LLM_UNLOADING -> CoordinatorState.IMAGEGEN_LOADING
                CoordinatorState.IMAGEGEN_LOADING -> CoordinatorState.IMAGEGEN_RUNNING
                CoordinatorState.IMAGEGEN_RUNNING -> CoordinatorState.IMAGEGEN_UNLOADING
                CoordinatorState.IMAGEGEN_UNLOADING -> CoordinatorState.LLM_RELOADING
                CoordinatorState.LLM_RELOADING -> CoordinatorState.LLM_LOADED
            }
            val outcome = coordinator.requestTransition(next, REQUEST)
            check(outcome is TransitionOutcome.Committed) { "驱动至 " + target + " 失败: " + outcome }
        }
        check(coordinator.state.value == target) {
            "无法到达 " + target + "，当前 " + coordinator.state.value
        }
    }

    companion object {
        val REQUEST = ImageGenCycleRequest(prompt = "一只打字的猫", outputPath = "images/cat.png")
    }
}
