package com.thatgfsj.qaa.core.inference

import java.io.InputStream

/**
 * 内置模型资产源抽象（GitHub Issue #6）。
 *
 * [AssetModelReleaser] 仅依赖本接口完成释放，不触碰 android.content.Context，
 * 因此释放逻辑可在 JVM 单测中以 fake 实现直接验证；
 * Android 侧桥接见 [AndroidAssetSource]。
 */
interface AssetSource {

    /**
     * 列出 assets/models/ 下待释放的内置模型文件名（仅文件名，不含目录）。
     * 实现方应保证确定性顺序（如按名称排序）。
     */
    fun listModels(): List<String>

    /**
     * 打开指定模型的只读输入流，调用方负责关闭。
     *
     * @param name [listModels] 返回的文件名
     */
    fun open(name: String): InputStream
}
