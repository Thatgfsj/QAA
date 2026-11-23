package com.thatgfsj.qaa.core.inference

/**
 * 计算后端契约（docs/ARCHITECTURE.md 第 3 节）。
 *
 * 后端降级链固定为 Hexagon NPU → Hybrid（Adreno GPU）→ CPU，
 * 由后续 Issue #5 的 BackendFallbackManager 实现；实际命中后端作为状态流
 * 对双前端状态栏暴露（同 Issue #5）。
 */
enum class ComputeBackend { AUTO, NPU, HYBRID, CPU }
