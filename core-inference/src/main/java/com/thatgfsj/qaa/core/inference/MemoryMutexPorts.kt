package com.thatgfsj.qaa.core.inference

/**
 * 可用内存水位读数（字节），供互斥状态机的回收校验读取。
 *
 * 生产实现由 App 层用 ActivityManager.MemoryInfo 或等价来源注入；单测注入可编程替身。
 */
fun interface MemoryProbe {
    fun availableBytes(): Long
}

/**
 * LLM 句柄：状态机只通过该端口观察引用计数、执行卸载/重载与结果回注。
 *
 * 实现必须保证：卸载/销毁后 [referenceCount] 归零且 [isLoaded] 为 false；
 * [reload] 与 [rebuild] 从会话树恢复上下文后才返回成功。
 */
interface LlmHandle {

    /** 引擎是否驻留内存。 */
    val isLoaded: Boolean

    /** 引擎引用计数；0 表示 native 引用已全部释放。 */
    val referenceCount: Int

    /** 当前上下文是否已全部持久化到会话树（可安全卸载）。 */
    fun contextPersisted(): Boolean

    /** 重载后上下文是否已从会话树重建完成。 */
    fun contextRebuilt(): Boolean

    /** 图片路径与摘要是否已回注 Agent Loop。 */
    fun imageResultInjected(): Boolean

    /** 卸载 LLM 并释放 native 引用；幂等。 */
    suspend fun unload(): Result<Unit>

    /** 按持久化上下文重载 LLM；幂等。 */
    suspend fun reload(): Result<Unit>

    /** 把图片路径与摘要回注会话上下文；幂等。 */
    suspend fun injectImageResult(imagePath: String, summary: String): Result<Unit>

    /** 强制释放全部资源（图外恢复路径的第一步）；幂等。 */
    suspend fun destroy()

    /** 图外恢复：销毁后重新加载至可用状态（不新增图内迁移边）。 */
    suspend fun rebuild(): Result<Unit>
}

/**
 * 画图引擎句柄：状态机通过该端口加载、出图与释放。
 *
 * 与 :core-tools 的 ImageGenEngine 解耦，避免模块循环依赖；由装配层适配。
 */
interface ImageGenHandle {

    /** 画图引擎是否驻留内存。 */
    val isLoaded: Boolean

    /** 引擎引用计数；0 表示 native 引用已全部释放。 */
    val referenceCount: Int

    suspend fun load(modelPath: String): Result<Unit>

    /**
     * 出图并落盘。
     *
     * @param outputPath 引擎可直接使用的落盘路径（生产装配传沙盒根内绝对路径）
     * @return 成功时携带图片摘要（可为空串），失败携带原因
     */
    suspend fun generate(prompt: String, outputPath: String): Result<String>

    /** 卸载画图引擎并释放 native 引用；幂等。 */
    suspend fun unload(): Result<Unit>

    /** 强制释放全部资源；幂等。 */
    suspend fun destroy()
}

/** 落盘校验结果（设计 §9.2 第 4 条：存在、非空、在沙盒授权根内）。 */
sealed interface OutputFileCheck {

    /** 文件有效；sizeBytes 为字节数（>0）。 */
    data class Valid(val sizeBytes: Long) : OutputFileCheck

    /** 文件无效；reason 为可读原因。 */
    data class Invalid(val reason: String) : OutputFileCheck
}

/**
 * 落盘校验端口。
 *
 * 实现负责把沙盒相对路径解析到授权根内并返回文件大小；越界、缺失、
 * 目录或空文件均返回 [OutputFileCheck.Invalid]。
 */
fun interface OutputFileProbe {
    suspend fun inspect(sandboxPath: String): OutputFileCheck
}

/** 确认记录（设计 §9.2 第 1 条：draw_image 已获用户 Allow）。 */
data class CycleConfirmation(val recordedAtMillis: Long = 0L)

/**
 * 一次画图互斥周期的请求参数。
 *
 * @param prompt LLM 生成的画图提示词
 * @param outputPath 沙盒内相对落盘路径（落盘校验与结果回注使用）
 * @param enginePath 引擎实际写入的路径（生产装配为 outputPath 解析后的绝对路径）
 * @param confirmation 确认记录；null 表示未经用户确认，首个 guard 拒绝
 * @param summary 引擎未返回摘要时的兜底摘要
 */
data class ImageGenCycleRequest(
    val prompt: String,
    val outputPath: String,
    val enginePath: String = outputPath,
    val confirmation: CycleConfirmation? = CycleConfirmation(),
    val summary: String = "",
) {
    init {
        require(prompt.isNotBlank()) { "prompt 不能为空" }
        require(outputPath.isNotBlank()) { "outputPath 不能为空" }
    }
}
