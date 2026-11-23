package com.thatgfsj.qaa.core.inference

import android.content.Context
import java.io.InputStream

/**
 * [AssetSource] 的 Android 实现：桥接 Context.assets（GitHub Issue #6）。
 *
 * 释放逻辑本体不感知本类；由 :app 装配层构造并注入给 [AssetModelReleaser]。
 */
class AndroidAssetSource(private val context: Context) : AssetSource {

    override fun listModels(): List<String> =
        context.assets.list(ASSET_DIR).orEmpty()
            .filter { it.endsWith(GGUF_EXTENSION) }
            .sorted()

    override fun open(name: String): InputStream =
        context.assets.open(ASSET_DIR + "/" + name)

    private companion object {
        /** APK 内置模型资产目录（相对 assets/）。 */
        const val ASSET_DIR = "models"

        /** 仅释放 GGUF 模型资产（冒烟链路契约，Issue #6）。 */
        const val GGUF_EXTENSION = ".gguf"
    }
}
