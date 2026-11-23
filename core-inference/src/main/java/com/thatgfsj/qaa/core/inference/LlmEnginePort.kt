package com.thatgfsj.qaa.core.inference

/**
 * LlmEngine 端口占位（后续 Issue #5：LlmEngine 封装 + 后端降级链 + InferenceMetrics）。
 *
 * 骨架期仅钉住模块归属与命名，不引入任何 GenieX 调用细节；
 * 断点续传下载器、内置模型释放器、ModelMemoryCoordinator 亦属 Issue #5/#6。
 */
interface LlmEnginePort {
    /** 实际命中的后端（降级链结果）。 */
    val activeBackend: ComputeBackend
}
