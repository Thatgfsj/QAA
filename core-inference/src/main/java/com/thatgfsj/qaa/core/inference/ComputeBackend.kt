package com.thatgfsj.qaa.core.inference

/**
 * 计算后端契约（docs/ARCHITECTURE.md 第 3 节）。
 *
 * 后端降级链：Hexagon NPU → HYBRID（HTP+CPU 官方快速路径）→ GPU（Adreno，链内附加档，
 * 不暴露命令）→ CPU，由 Issue #5 的 BackendFallbackManager 在 create 级实现；
 * 实际命中后端以 ActiveBackend（NPU/HYBRID/GPU/CPU）状态流回显（ARCHITECTURE.md 4.1）。
 */
enum class ComputeBackend { AUTO, NPU, HYBRID, CPU }
