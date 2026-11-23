package com.thatgfsj.qaa.core.tools

/**
 * 画图引擎契约（docs/ARCHITECTURE.md 第 3 节）。
 *
 * draw_image 占位实现与 ModelMemoryCoordinator 互斥状态机（LLM / 画图模型
 * 严禁同时驻留内存）的联动属后续 Issue #21/#22。
 */
interface ImageGenEngine {
    suspend fun load(modelPath: String)
    suspend fun generate(prompt: String, outputPath: String): Result<String>
    suspend fun unload()
}
