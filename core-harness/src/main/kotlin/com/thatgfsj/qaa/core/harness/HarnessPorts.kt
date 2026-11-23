package com.thatgfsj.qaa.core.harness

/**
 * Agent Harness 占位端口（后续 Issue #12–#21 逐个实现）。
 *
 * 架构逻辑依赖为 HAR→INF / HAR→TOOL（docs/ARCHITECTURE.md 第 2 节）；因本模块
 * 必须保持纯 JVM 可单测，Gradle 依赖方向在装配层反转落地：本模块定义端口，
 * :core-inference / :core-tools 提供实现，:app 手动 DI 完成组装（无 Hilt/Dagger）。
 * 端口方法签名待 Pi-Style Harness 设计文档（task-2）定稿后补全。
 */
interface AgentHarnessPort
