package com.thatgfsj.qaa.core.inference

/**
 * 实际命中的计算后端（docs/ARCHITECTURE.md 第 3 节、4.1）。
 *
 * 请求/命令面使用 [ComputeBackend]（AUTO / NPU / HYBRID / CPU）；命中面用本枚举
 * 如实回显，包含只在 AUTO 降级链内尝试的 [GPU]（Adreno，不暴露为锁定命令）。
 * 状态栏回显与 [InferenceMetrics.backend] 均以本枚举为准。
 */
enum class ActiveBackend(val computeUnit: String) {

    NPU("npu"),
    HYBRID("hybrid"),
    GPU("gpu"),
    CPU("cpu"),
    ;

    companion object {

        /** 某锁定值对应的 create 尝试序列；AUTO 为完整链，锁定档只有自身。 */
        fun chainFor(locked: ComputeBackend): List<ActiveBackend> = when (locked) {
            ComputeBackend.AUTO -> listOf(NPU, HYBRID, GPU, CPU)
            ComputeBackend.NPU -> listOf(NPU)
            ComputeBackend.HYBRID -> listOf(HYBRID)
            ComputeBackend.CPU -> listOf(CPU)
        }
    }
}
