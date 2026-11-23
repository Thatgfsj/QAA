package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.tools.SandboxRoot

/**
 * storage grant 的中文回显文案（纯 Kotlin，便于单测）。
 *
 * 授权目录只展示树 Uri 与提供者返回的 displayName，不展示 /storage 真实路径
 * ——targetSdk 35 分区存储下该路径对 App 不可用。
 */
internal object StorageGrantText {

    fun mountMessage(
        displayName: String,
        treeUri: String,
        readOnly: Boolean,
        replaced: SandboxRoot?,
    ): String = buildString {
        appendLine("外部存储授权已生效：根 id 为 \"" + StorageGrant.ROOT_ID + "\"，授权目录「" + displayName + "」。")
        appendLine("授权树：" + treeUri)
        appendLine(
            "访问方式：read / write / edit 通过 \"" + StorageGrant.ROOT_ID +
                "/<相对路径>\" 经系统 SAF 访问该目录；不带首段的路径仍指向 App 私有目录。",
        )
        if (readOnly) {
            appendLine("当前授权为只读：write / edit 会返回 IO 失败，请重新授权并允许写入。")
        }
        if (replaced != null) {
            appendLine("已替换原有授权根（" + describe(replaced) + "），旧目录不再挂载。")
        }
        append("授权持久化：系统 SAF 授权，重启后自动恢复。")
    }

    private fun describe(root: SandboxRoot): String = when (root) {
        is SandboxRoot.Saf -> "原授权目录「" + root.displayName + "」，树 " + root.treeUri
        is SandboxRoot.File -> "原授权根（旧版本真实路径挂载，已解除）"
    }
}
