package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.inference.ImageGenHandle
import com.thatgfsj.qaa.core.inference.LlmEnginePort
import com.thatgfsj.qaa.core.inference.OutputFileCheck
import com.thatgfsj.qaa.core.inference.OutputFileProbe
import com.thatgfsj.qaa.core.tools.SandboxFs
import java.io.File

/**
 * LLM 侧内存互斥句柄（ModelMemoryCoordinator 的 [com.thatgfsj.qaa.core.inference.LlmHandle] 实现）。
 *
 * 驻留状态直接映射引擎的 [LlmEnginePort.isLoaded]；重载用装配层记录的模型路径，
 * 上下文由会话树重建（Agent Loop 每步重新组装提示），因此会话历史不丢。
 */
class LlmEngineMemoryHandle(
    private val engine: LlmEnginePort,
    private val modelSource: () -> String?,
) : com.thatgfsj.qaa.core.inference.LlmHandle {

    override val isLoaded: Boolean get() = engine.isLoaded.value

    override val referenceCount: Int get() = if (engine.isLoaded.value) 1 else 0

    override fun contextPersisted(): Boolean = true

    override fun contextRebuilt(): Boolean = true

    /** 图片结果由 Agent Loop 以 tool 节点回注，不依赖引擎内注入。 */
    override fun imageResultInjected(): Boolean = false

    override suspend fun unload(): Result<Unit> {
        engine.unload()
        return Result.success(Unit)
    }

    override suspend fun reload(): Result<Unit> {
        val source = modelSource() ?: return Result.failure(IllegalStateException("尚未选择模型"))
        return engine.load(source)
    }

    override suspend fun injectImageResult(imagePath: String, summary: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun destroy() {
        engine.unload()
    }

    override suspend fun rebuild(): Result<Unit> = reload()
}

/**
 * 画图引擎占位句柄：本期 ImageGenEngine 为 null（Phase 3 人工门禁后接入），
 * 所有动作以失败上报，状态机会走图外恢复，draw_image 在预检阶段即快速失败。
 */
class AbsentImageGenHandle : ImageGenHandle {

    override val isLoaded: Boolean get() = false

    override val referenceCount: Int get() = 0

    override suspend fun load(modelPath: String): Result<Unit> = Result.failure(unavailable())

    override suspend fun generate(prompt: String, outputPath: String): Result<String> =
        Result.failure(unavailable())

    override suspend fun unload(): Result<Unit> = Result.success(Unit)

    override suspend fun destroy() = Unit

    private fun unavailable(): Throwable =
        IllegalStateException("画图引擎尚未接入（等待人工门禁后的底座选型）")
}

/** 沙盒输出文件校验：路径经授权根解析后再判断存在与大小。 */
class SandboxOutputFileProbe(private val sandbox: SandboxFs) : OutputFileProbe {

    override suspend fun inspect(sandboxPath: String): OutputFileCheck = try {
        val resolved = sandbox.resolve(sandboxPath).absolutePath
        val file = File(resolved.toString())
        when {
            !file.isFile -> OutputFileCheck.Invalid("输出文件不存在：" + sandboxPath)
            file.length() <= 0L -> OutputFileCheck.Invalid("输出文件为空：" + sandboxPath)
            else -> OutputFileCheck.Valid(file.length())
        }
    } catch (error: Exception) {
        OutputFileCheck.Invalid("输出路径不可用：" + (error.message ?: error::class.simpleName))
    }
}
