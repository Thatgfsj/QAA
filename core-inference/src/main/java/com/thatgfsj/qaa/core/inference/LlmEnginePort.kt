package com.thatgfsj.qaa.core.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 端侧 LLM 引擎端口（:core-inference 对上层暴露的稳定契约）。
 *
 * 实现分两类：
 * - Android 侧 [GenieXLlmEngine]：封装 GenieX SDK，负责模型导入、create 级降级链、
 *   流式生成与指标上报；
 * - JVM 侧 [FakeLlmEngine]：脚本化 token 流与可配置失败，供 UI 联调与单测。
 *
 * 会话历史由调用方维护：每次 [generate] 传入完整多轮消息，引擎按模型 chat template
 * 重建提示词（GenieX 不提供会话存储，见 docs/research/geniex-api.md §7）。
 */
interface LlmEnginePort {

    /** 请求/锁定面：本次加载指定的后端；AUTO 表示走完整降级链。 */
    val lockedBackend: StateFlow<ComputeBackend>

    /**
     * 命中面：实际创建成功的后端（含链内 GPU 附加档）。
     *
     * 未加载或降级链全部失败时为 null；状态栏可回落 [lockedBackend] 展示锁定值。
     */
    val activeBackend: StateFlow<ActiveBackend?>

    /** 模型是否已加载可用。 */
    val isLoaded: StateFlow<Boolean>

    /**
     * 加载模型并完成 create 级降级链；同一时刻只保留一个已加载会话。
     *
     * @param modelSource 模型来源：释放后的 GGUF 文件路径（Issue #6 的
     *        releasedModelFile(name)），或模型目录
     * @param config 加载配置（锁定后端、上下文长度、线程数等）
     */
    suspend fun load(modelSource: String, config: LlmLoadConfig = LlmLoadConfig()): Result<Unit>

    /** 卸载当前模型并释放 native 句柄；未加载时为空操作，可重复调用。 */
    suspend fun unload()

    /**
     * 流式生成：若干 [LlmStreamEvent.Token] 后跟一个 [LlmStreamEvent.Completed]；
     * 生成期错误以 [LlmStreamEvent.Failed] 结束。
     *
     * 取消语义：下游取消采集（协程取消、take 提前结束）时，实现必须调用 native
     * 停止接口（GenieX 为 stopStream()），仅取消 Flow 不会停止 native 生成。
     */
    fun generate(request: LlmRequest): Flow<LlmStreamEvent>

    /** 主动停止当前生成（等价于下游取消）；当前无生成时为空操作。 */
    suspend fun cancel()
}
