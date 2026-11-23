package com.thatgfsj.qaa.core.tools

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.ArrayDeque
import kotlin.io.path.createDirectories

/**
 * 基于 JVM NIO 的 [SandboxFs] 实现（纯 JVM，零 Android 依赖）。
 *
 * 路径规则：
 * 1. 空路径、以 '/' 开头、含 '..' 段、含反斜杠、Windows 盘符样式首段（如 "C:"）、
 *    含 NUL 字符 —— 一律 [SandboxPathException]（E_PATH_ESCAPE）；
 * 2. 首段与某 [SandboxRoot.id] 相等（区分大小写）→ 相对该根解析（首段剥离）；
 *    否则相对默认根（roots.first()）。'.' 段与空段（'//'、尾随 '/'）静默折叠；
 * 3. 词法归一后必须仍位于授权根内；已存在部分经 toRealPath() 校验，
 *    符号链接解析后落在授权根之外同样拒绝；
 * 4. 所有写入走「临时文件 + 原子重命名」，失败清理临时文件且不动原文件；
 *    文件系统不支持原子移动时回退为普通替换移动。
 */
open class LocalSandboxFs(roots: List<SandboxRoot>) : SandboxFs {

    /** 根表快照：授权根与真实目录一一对应，替换时整体发布。 */
    private data class RootTable(val roots: List<SandboxRoot>, val realDirs: List<Path>)

    @Volatile
    private var table: RootTable = RootTable(emptyList(), emptyList())

    init {
        require(roots.isNotEmpty()) { "至少需要一个授权根" }
        require(roots.map { it.id }.distinct().size == roots.size) { "授权根 id 重复" }
        roots.forEach(::validateRoot)
        table = RootTable(roots, roots.map { it.directory.toRealPath() })
    }

    /**
     * 动态挂载授权根：id 已存在时原位替换（旧目录立即解除挂载），否则追加到末尾。
     *
     * 默认根（首个授权根）承载私有目录，不允许替换；解析始终读取完整快照，
     * 并发调用不会看到根列表与真实目录错位。
     *
     * @return 被替换的旧根；新增根返回 null
     */
    @Synchronized
    fun addRoot(root: SandboxRoot): SandboxRoot? {
        validateRoot(root)
        val current = table
        require(root.id != current.roots.first().id) { "默认授权根不可替换: " + root.id }
        val realDir = root.directory.toRealPath()
        val nextRoots = current.roots.toMutableList()
        val nextRealDirs = current.realDirs.toMutableList()
        val index = nextRoots.indexOfFirst { it.id == root.id }
        val replaced = if (index >= 0) {
            nextRoots[index].also {
                nextRoots[index] = root
                nextRealDirs[index] = realDir
            }
        } else {
            nextRoots.add(root)
            nextRealDirs.add(realDir)
            null
        }
        table = RootTable(nextRoots, nextRealDirs)
        return replaced
    }

    private fun validateRoot(root: SandboxRoot) {
        require(root.id.isNotEmpty() && root.id.none { it == '/' || it == '\\' || it == '.' }) {
            "非法授权根 id: " + root.id
        }
        require(Files.isDirectory(root.directory)) { "授权根目录不存在: " + root.directory }
    }

    override suspend fun resolve(path: String): ResolvedPath {
        val snapshot = table
        val segments = parseSegments(path)
        val matchedIndex = snapshot.roots.indexOfFirst { segments.isNotEmpty() && it.id == segments.first() }
        val rootIndex = if (matchedIndex >= 0) matchedIndex else 0
        val root = snapshot.roots[rootIndex]
        val relSegments = if (matchedIndex >= 0) segments.drop(1) else segments
        val rel = relSegments.joinToString("/")
        val abs = root.directory.resolve(rel).normalize()
        ensureInsideRoot(snapshot, rootIndex, abs, path)
        return ResolvedPath(root, rel, abs)
    }

    override suspend fun exists(path: String): Boolean {
        val abs = resolve(path).absolutePath
        return Files.exists(abs)
    }

    override suspend fun readText(path: String, maxChars: Int): String {
        require(maxChars > 0) { "maxChars 必须为正数" }
        val abs = resolve(path).absolutePath
        ensureExistingFile(abs, path)
        ensureTextFile(abs, path)
        return readCharsUpTo(abs, maxChars)
    }

    override suspend fun writeText(path: String, content: String) {
        val abs = resolve(path).absolutePath
        writeAtomic(abs, content)
    }

    override suspend fun applyEdit(
        path: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean,
    ): EditOutcome {
        require(oldText.isNotEmpty()) { "oldText 为空串" }
        val abs = resolve(path).absolutePath
        ensureExistingFile(abs, path)
        val content = readAllText(abs)
        val matches = countOccurrences(content, oldText)
        return when {
            matches == 0 -> EditOutcome.NotFound(nearestHint(content, oldText))
            matches > 1 && !replaceAll -> EditOutcome.MultiMatch(matches)
            else -> {
                val replaced = if (replaceAll) {
                    content.replace(oldText, newText)
                } else {
                    val idx = content.indexOf(oldText)
                    content.substring(0, idx) + newText + content.substring(idx + oldText.length)
                }
                writeAtomic(abs, replaced)
                EditOutcome.Applied(if (replaceAll) matches else 1, replaced.length)
            }
        }
    }

    override suspend fun textLength(path: String): Long {
        val abs = resolve(path).absolutePath
        ensureExistingFile(abs, path)
        ensureTextFile(abs, path)
        Files.newBufferedReader(abs, StandardCharsets.UTF_8).use { reader ->
            val buf = CharArray(8192)
            var total = 0L
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                total += n
            }
            return total
        }
    }

    override suspend fun isDirectory(path: String): Boolean {
        val abs = resolve(path).absolutePath
        return Files.isDirectory(abs)
    }

    override suspend fun listDirectory(path: String): List<SandboxEntry> {
        val abs = resolve(path).absolutePath
        if (!Files.isDirectory(abs)) throw SandboxIoException("目标不是目录: " + path)
        val stream = Files.list(abs)
        val entries = try {
            val list = ArrayList<SandboxEntry>()
            val itr = stream.iterator()
            while (itr.hasNext()) {
                val p = itr.next()
                val name = p.fileName?.toString() ?: continue
                val isDir = Files.isDirectory(p)
                list.add(
                    SandboxEntry(
                        name = name,
                        isDirectory = isDir,
                        sizeBytes = if (isDir) -1L else runCatching { Files.size(p) }.getOrDefault(-1L),
                    )
                )
            }
            list
        } finally {
            stream.close()
        }
        return entries.sortedBy { it.name }
    }

    // ---- 内部：路径校验 ----

    private fun parseSegments(raw: String): List<String> {
        if (raw.isEmpty()) throw SandboxPathException("路径为空")
        if ('\u0000' in raw) throw SandboxPathException("路径含 NUL 字符: " + raw)
        if ('\\' in raw) throw SandboxPathException("不支持反斜杠路径: " + raw)
        if (raw.startsWith("/")) throw SandboxPathException("禁止绝对路径: " + raw)
        val segments = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) throw SandboxPathException("路径含 '..' 段: " + raw)
        val first = segments.firstOrNull()
        if (first != null && DRIVE_PATTERN.matches(first)) {
            throw SandboxPathException("疑似 Windows 盘符路径: " + raw)
        }
        return segments
    }

    /** 词法归一 + 真实路径双重校验；已存在部分解析符号链接后必须仍在根内。 */
    private fun ensureInsideRoot(snapshot: RootTable, rootIndex: Int, abs: Path, original: String) {
        val root = snapshot.roots[rootIndex]
        if (!abs.startsWith(root.directory)) {
            throw SandboxPathException("路径越界: " + original)
        }
        val realRoot = snapshot.realDirs[rootIndex]
        var existing = abs
        val tail = ArrayDeque<String>()
        while (!Files.exists(existing)) {
            val fileName = existing.fileName
                ?: throw SandboxPathException("路径越界: " + original)
            tail.addFirst(fileName.toString())
            val parent = existing.parent
                ?: throw SandboxPathException("路径越界: " + original)
            if (!parent.startsWith(root.directory) && parent != root.directory) {
                throw SandboxPathException("路径越界: " + original)
            }
            existing = parent
        }
        val realExisting = existing.toRealPath()
        if (!realExisting.startsWith(realRoot)) {
            throw SandboxPathException("符号链接逃逸: " + original)
        }
    }

    private fun ensureExistingFile(abs: Path, display: String) {
        if (!Files.exists(abs)) throw SandboxFileNotFoundException("文件不存在: " + display)
        if (Files.isDirectory(abs)) throw SandboxIoException("目标是目录: " + display)
    }

    /** read 细则：前 8KB 含 NUL 判定二进制。 */
    private fun ensureTextFile(abs: Path, display: String) {
        Files.newInputStream(abs).use { input ->
            val buf = ByteArray(BINARY_SNIFF_BYTES)
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            for (i in 0 until read) {
                if (buf[i] == 0.toByte()) throw SandboxBinaryFileException("二进制文件: " + display)
            }
        }
    }

    private fun readCharsUpTo(abs: Path, maxChars: Int): String {
        Files.newBufferedReader(abs, StandardCharsets.UTF_8).use { reader ->
            val sb = StringBuilder()
            val buf = CharArray(4096)
            while (sb.length < maxChars) {
                val need = minOf(buf.size, maxChars - sb.length)
                val n = reader.read(buf, 0, need)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
            return sb.toString()
        }
    }

    private fun readAllText(abs: Path): String {
        Files.newBufferedReader(abs, StandardCharsets.UTF_8).use { reader ->
            val sb = StringBuilder()
            val buf = CharArray(8192)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
            return sb.toString()
        }
    }

    // ---- 内部：写入 ----

    private fun writeAtomic(abs: Path, content: String) {
        val parent = abs.parent ?: throw SandboxIoException("目标缺少父目录: " + abs)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val tmp = try {
            parent.createDirectories()
            File.createTempFile(TMP_PREFIX, TMP_SUFFIX, parent.toFile()).toPath()
        } catch (e: IOException) {
            throw SandboxIoException("创建临时文件失败: " + abs, e)
        }
        try {
            stage(tmp, bytes)
            commit(tmp, abs)
        } catch (e: SandboxPathException) {
            cleanupTemp(tmp)
            throw e
        } catch (e: IOException) {
            cleanupTemp(tmp)
            throw SandboxIoException("写入失败: " + abs, e)
        } catch (e: Throwable) {
            cleanupTemp(tmp)
            throw e
        }
    }

    /** 写入临时文件内容；子类可覆写以注入半写失败场景。 */
    protected open fun stage(tmp: Path, bytes: ByteArray) {
        Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
            out.write(bytes)
            out.flush()
        }
    }

    /** 临时文件 → 目标的替换；不支持原子移动时回退为普通替换移动。 */
    private fun commit(tmp: Path, target: Path) {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun cleanupTemp(tmp: Path) {
        runCatching { Files.deleteIfExists(tmp) }
    }

    // ---- 内部：edit 辅助 ----

    private fun countOccurrences(content: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val idx = content.indexOf(needle, from)
            if (idx < 0) break
            count++
            from = idx + needle.length
        }
        return count
    }

    /** 最近似片段提示：先按前缀、再按后缀从长到短在文件中探测；均无则 null。 */
    private fun nearestHint(content: String, oldText: String): String? {
        val probe = oldText.trim().take(MAX_HINT_PROBE_CHARS)
        if (probe.length < MIN_HINT_CHARS) return null
        for (len in probe.length downTo MIN_HINT_CHARS) {
            val piece = probe.substring(0, len)
            val idx = content.indexOf(piece)
            if (idx >= 0) return hintAround(content, idx, piece.length)
        }
        for (len in probe.length downTo MIN_HINT_CHARS) {
            val piece = probe.substring(probe.length - len)
            val idx = content.indexOf(piece)
            if (idx >= 0) return hintAround(content, idx, piece.length)
        }
        return null
    }

    private fun hintAround(content: String, idx: Int, len: Int): String {
        val from = maxOf(0, idx - HINT_CONTEXT_CHARS)
        val to = minOf(content.length, idx + len + HINT_CONTEXT_CHARS)
        return "文件中存在相似片段（可能已有细微差异）：\"" + content.substring(from, to) + "\""
    }

    private companion object {
        const val BINARY_SNIFF_BYTES = 8 * 1024
        const val TMP_PREFIX = ".qaa-tmp-"
        const val TMP_SUFFIX = ".tmp"
        const val MIN_HINT_CHARS = 4
        const val MAX_HINT_PROBE_CHARS = 96
        const val HINT_CONTEXT_CHARS = 12
        val DRIVE_PATTERN = Regex("[A-Za-z]:.*")
    }
}
