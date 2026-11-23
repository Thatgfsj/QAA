package com.thatgfsj.qaa.core.tools

import java.io.InputStream
import java.io.OutputStream

/**
 * SAF 文档元信息（queryDocument / listChildren 的产物）。
 *
 * [name] 为提供者返回的显示名（与文档 ID 解耦）；[sizeBytes] 未知或目录为 -1。
 */
data class SafDocumentInfo(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

/**
 * SAF 文档网关：唯一接触 Android DocumentsContract / ContentResolver 的薄壳。
 *
 * 调用方只提供「授权树 Uri + 树内相对路径」，实现负责把路径逐段解析为 DocumentId，
 * 全程不构造 /storage 真实路径。JVM 单测注入内存假实现，Android 实现见 app 层。
 *
 * 路径约定：以 '/' 分隔、不得以 '/' 开头、不得含 "." / ".." 段；空串表示授权树根。
 */
interface SafDocumentGateway {

    /**
     * 查询文档；不存在返回 null。其他失败（含授权失效）抛 [SafGatewayException]。
     */
    fun queryDocument(treeUri: String, relativePath: String): SafDocumentInfo?

    /**
     * 打开只读输入流。文档不存在抛 [SafNotFoundException]，
     * 其他失败（含授权失效）抛 [SafGatewayException]。调用方负责关闭流。
     */
    fun openRead(treeUri: String, relativePath: String): InputStream

    /**
     * 打开覆盖写输出流：文档已存在则截断重写，不存在则创建。
     * 缺失的父目录由实现按需创建；目标为目录或父路径冲突时抛 [SafGatewayException]。
     * 调用方负责关闭流。
     */
    fun openWrite(treeUri: String, relativePath: String): OutputStream

    /**
     * 列出目录的直接子项。目录不存在抛 [SafNotFoundException]，
     * 目标不是目录或查询失败抛 [SafGatewayException]。
     */
    fun listChildren(treeUri: String, relativePath: String): List<SafDocumentInfo>
}

/** SAF 访问失败基类：由 [LocalSandboxFs] 翻译为稳定的沙盒错误码（E_IO / E_NOT_FOUND）。 */
open class SafGatewayException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** SAF 文档/目录不存在。 */
class SafNotFoundException(message: String, cause: Throwable? = null) : SafGatewayException(message, cause)
