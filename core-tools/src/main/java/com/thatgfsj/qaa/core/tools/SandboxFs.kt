package com.thatgfsj.qaa.core.tools

import java.nio.file.Path

/**
 * 授权根：一个可读写的挂载点（docs/design/harness-core-design.md §3.2）。
 *
 * 两类根共用同一张路由表：路径首段等于根 id 时定位到该根，否则落到默认根。
 * [File] 是真实文件系统目录（App 私有目录等）；[Saf] 是系统 SAF 授权树，
 * 内容经 [SafDocumentGateway] 访问，不映射为 /storage 真实路径。
 */
sealed class SandboxRoot {

    /** 挂载点 id：路径首段与之相等（区分大小写）时定位到该根。 */
    abstract val id: String

    /**
     * 真实文件系统根：构造时校验目录存在，解析时校验符号链接不逃逸。
     */
    data class File(
        override val id: String,
        /** 授权的真实目录（构造时即校验存在并规范化为真实路径）。 */
        val directory: Path,
    ) : SandboxRoot()

    /**
     * SAF 目录树根：所有访问经 [gateway] 的 DocumentsContract 语义完成。
     *
     * [treeUri] 是 ACTION_OPEN_DOCUMENT_TREE 返回并已持久化的树 Uri；[displayName]
     * 仅用于中文回显，不参与路径解析。授权边界由树本身限定，不存在符号链接逃逸面；
     * 相对路径的 '..' 仍在词法层拒绝。
     */
    data class Saf(
        override val id: String,
        val treeUri: String,
        val displayName: String,
        val gateway: SafDocumentGateway,
    ) : SandboxRoot()
}

/** resolve() 的产物：归一化后仍落在授权根内的路径。 */
data class ResolvedPath(
    /** 命中的授权根。 */
    val root: SandboxRoot,
    /** 相对根路径（分隔符统一为 '/'，根本身为空串）。 */
    val relativePath: String,
    /** File 根的真实绝对路径；SAF 根没有文件系统路径，为 null。 */
    val absolutePath: Path?,
)

/** 目录项（listDirectory 的产物）。 */
data class SandboxEntry(
    val name: String,
    val isDirectory: Boolean,
    /** 文件字节数；目录或提供者未返回体积时为 -1。 */
    val sizeBytes: Long,
)

/** applyEdit() 的判定结果（docs/design/harness-core-design.md §3.3 edit 细则）。 */
sealed class EditOutcome {

    /** 替换成功：replacements 为实际替换次数，newLength 为新内容字符数。 */
    data class Applied(val replacements: Int, val newLength: Int) : EditOutcome()

    /** oldText 未出现；nearestHint 为最近似片段提示（无相似片段时为 null）。 */
    data class NotFound(val nearestHint: String?) : EditOutcome()

    /** oldText 出现多处且 replaceAll=false；matches 为匹配数。 */
    data class MultiMatch(val matches: Int) : EditOutcome()
}

/** 沙盒路径违规（越界或非法路径形态），映射稳定错误码 E_PATH_ESCAPE。 */
class SandboxPathException(message: String) : IllegalStateException(message)

/** 沙盒内文件不存在，映射稳定错误码 E_NOT_FOUND。 */
class SandboxFileNotFoundException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** 内容判定为二进制文件（read 细则：前 8KB 含 NUL），映射稳定错误码 E_BINARY_FILE。 */
class SandboxBinaryFileException(message: String) : RuntimeException(message)

/** 沙盒 IO 故障，映射稳定错误码 E_IO。 */
class SandboxIoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 沙盒文件系统（docs/design/harness-core-design.md §3.2）。
 *
 * 契约：所有方法先做路径归一化与授权根校验，越界一律以 [SandboxPathException]
 * 拒绝（映射 E_PATH_ESCAPE）。实现执行阻塞 NIO 或 SAF 查询，由调用方负责调度在 IO 线程。
 */
interface SandboxFs {

    /** 归一化并校验路径；越界或非法形态抛 [SandboxPathException]。 */
    suspend fun resolve(path: String): ResolvedPath

    suspend fun exists(path: String): Boolean

    /**
     * 读取文本内容，最多返回 maxChars 个字符。
     * 文件不存在抛 [SandboxFileNotFoundException]；前 8KB 含 NUL 判定为二进制，
     * 抛 [SandboxBinaryFileException]。
     */
    suspend fun readText(path: String, maxChars: Int): String

    /** 写入/覆盖文件：父目录自动创建；File 根临时文件 + 原子重命名，SAF 根覆盖流。 */
    suspend fun writeText(path: String, content: String)

    /**
     * 精确字符串替换（literal，非正则）。目标必须已存在（不隐式创建），否则抛
     * [SandboxFileNotFoundException]。oldText 为空串属参数错误，由工具层在调用前拦截。
     */
    suspend fun applyEdit(path: String, oldText: String, newText: String, replaceAll: Boolean): EditOutcome

    /** 文本字符总数（read 截断标记需要总长）。二进制文件抛 [SandboxBinaryFileException]。 */
    suspend fun textLength(path: String): Long

    /** 是否目录。 */
    suspend fun isDirectory(path: String): Boolean

    /** 列目录（按名称稳定排序）。目标不是目录时抛 [SandboxIoException]。 */
    suspend fun listDirectory(path: String): List<SandboxEntry>
}
