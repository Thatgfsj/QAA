package com.thatgfsj.qaa.core.tools

import java.nio.file.Path

/**
 * 授权根：一个可读写的目录挂载点（docs/design/harness-core-design.md §3.2）。
 * App 私有目录为默认根；外部存储授权目录（SAF grant）后续作为新的
 * [SandboxRoot] 注入，无需改动工具实现。
 */
data class SandboxRoot(
    /** 挂载点 id：路径首段与之相等（区分大小写）时定位到该根。 */
    val id: String,
    /** 授权的真实目录（构造时即校验存在并规范化为真实路径）。 */
    val directory: Path,
)

/** resolve() 的产物：归一化后仍落在授权根内的路径。 */
data class ResolvedPath(
    /** 命中的授权根。 */
    val root: SandboxRoot,
    /** 相对 root.directory 的规范相对路径（分隔符统一为 '/'，根本身为空串）。 */
    val relativePath: String,
    /** 归一化后的绝对路径。 */
    val absolutePath: Path,
)

/** 目录项（listDirectory 的产物）。 */
data class SandboxEntry(
    val name: String,
    val isDirectory: Boolean,
    /** 文件字节数；目录为 -1。 */
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
class SandboxFileNotFoundException(message: String) : RuntimeException(message)

/** 内容判定为二进制文件（read 细则：前 8KB 含 NUL），映射稳定错误码 E_BINARY_FILE。 */
class SandboxBinaryFileException(message: String) : RuntimeException(message)

/** 沙盒 IO 故障，映射稳定错误码 E_IO。 */
class SandboxIoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 沙盒文件系统（docs/design/harness-core-design.md §3.2）。
 *
 * 契约：所有方法先做路径归一化与授权根校验，越界一律以 [SandboxPathException]
 * 拒绝（映射 E_PATH_ESCAPE）。实现执行阻塞 NIO，由调用方负责调度在 IO 线程。
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

    /** 写入/覆盖文件：父目录自动创建，临时文件 + 原子重命名，失败不动原文件。 */
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
