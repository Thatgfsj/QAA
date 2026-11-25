package com.thatgfsj.qaa.core.tools

import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.ArrayDeque
import kotlin.io.path.createDirectories

/**
 * 组合根 [SandboxFs] 实现：同一张路由表挂载两类授权根（纯 JVM，零 Android 依赖）。
 *
 * 路径规则：
 * 1. 空路径、以 '/' 开头、含 '..' 段、含反斜杠、Windows 盘符样式首段（如 "C:"）、
 *    含 NUL 字符 —— 一律 [SandboxPathException]（E_PATH_ESCAPE）；
 * 2. 首段与某根 [SandboxRoot.id] 相等（区分大小写）→ 相对该根解析（首段剥离）；
 *    否则相对默认根（roots.first()）。'.' 段与空段（'//'、尾随 '/'）静默折叠；
 * 3. File 根：词法归一后必须仍位于授权根内；已存在部分经 toRealPath() 校验，
 *    符号链接解析后落在授权根之外同样拒绝；写入走「临时文件 + 原子重命名」；
 * 4. SAF 根：路径只作为授权树内的相对路径交给 [SafDocumentGateway]，逐段解析为
 *    DocumentId，不构造 /storage 路径。授权边界由树限定，无符号链接逃逸面；
 *    写入为 openOutputStream 覆盖，不具备 File 根的原子替换语义。
 */
open class LocalSandboxFs(roots: List<SandboxRoot>) : SandboxFs {

    private sealed class RootMount {
        abstract val root: SandboxRoot

        class FileRoot(val fileRoot: SandboxRoot.File, val realDir: Path) : RootMount() {
            override val root: SandboxRoot get() = fileRoot
        }

        class SafRoot(val safRoot: SandboxRoot.Saf) : RootMount() {
            override val root: SandboxRoot get() = safRoot
        }
    }

    /** 路由后的目标：File 根带真实绝对路径，SAF 根只带树内相对路径。 */
    private sealed class Routed {
        abstract val display: String

        class File(
            val mount: RootMount.FileRoot,
            val relativePath: String,
            val absolutePath: Path,
            override val display: String,
        ) : Routed()

        class Saf(
            val mount: RootMount.SafRoot,
            val relativePath: String,
            override val display: String,
        ) : Routed()
    }

    private data class RootTable(val mounts: List<RootMount>)

    @Volatile
    private var table: RootTable = RootTable(emptyList())

    init {
        require(roots.isNotEmpty()) { "至少需要一个授权根" }
        require(roots.map { it.id }.distinct().size == roots.size) { "授权根 id 重复" }
        table = RootTable(roots.map(::mountOf))
    }

    /**
     * 动态挂载授权根：id 已存在时原位替换（旧根立即解除挂载），否则追加到末尾。
     *
     * 默认根（首个授权根）承载私有目录，不允许替换；解析始终读取完整快照，
     * 并发调用不会看到根列表与真实目录错位。
     *
     * @return 被替换的旧根；新增根返回 null
     */
    @Synchronized
    fun addRoot(root: SandboxRoot): SandboxRoot? {
        val mount = mountOf(root)
        val current = table
        require(root.id != current.mounts.first().root.id) { "默认授权根不可替换: " + root.id }
        val next = current.mounts.toMutableList()
        val index = next.indexOfFirst { it.root.id == root.id }
        val replaced = if (index >= 0) {
            next.set(index, mount).root
        } else {
            next.add(mount)
            null
        }
        table = RootTable(next)
        return replaced
    }

    /** 当前授权根快照（顺序即路由优先级，首根为默认根）。 */
    @Synchronized
    fun roots(): List<SandboxRoot> = table.mounts.map { it.root }

    /**
     * 整体替换授权根表（Issue #126 工作区切换）：首根为默认根，[roots] 必须非空且 id 唯一。
     *
     * 与 [addRoot] 共用同一把锁：替换在单个快照内生效，解析不会看到新旧根混排。
     * 调用方负责保留需要继续挂载的附加根（如 storage grant 的 ext 根）。
     */
    @Synchronized
    fun replaceRoots(roots: List<SandboxRoot>) {
        require(roots.isNotEmpty()) { "至少需要一个授权根" }
        require(roots.map { it.id }.distinct().size == roots.size) { "授权根 id 重复" }
        table = RootTable(roots.map(::mountOf))
    }

    private fun mountOf(root: SandboxRoot): RootMount {
        validateRoot(root)
        return when (root) {
            is SandboxRoot.File -> RootMount.FileRoot(root, root.directory.toRealPath())
            is SandboxRoot.Saf -> RootMount.SafRoot(root)
        }
    }

    private fun validateRoot(root: SandboxRoot) {
        require(root.id.isNotEmpty() && root.id.none { it == '/' || it == '\\' || it == '.' }) {
            "非法授权根 id: " + root.id
        }
        when (root) {
            is SandboxRoot.File -> require(Files.isDirectory(root.directory)) {
                "授权根目录不存在: " + root.directory
            }
            is SandboxRoot.Saf -> require(root.treeUri.isNotBlank()) {
                "授权树 Uri 为空: " + root.id
            }
        }
    }

    override suspend fun resolve(path: String): ResolvedPath = when (val routed = route(path)) {
        is Routed.File -> ResolvedPath(routed.mount.fileRoot, routed.relativePath, routed.absolutePath)
        is Routed.Saf -> ResolvedPath(routed.mount.safRoot, routed.relativePath, null)
    }

    override suspend fun exists(path: String): Boolean = when (val routed = route(path)) {
        is Routed.File -> Files.exists(routed.absolutePath)
        is Routed.Saf -> querySafOrNull(routed) != null
    }

    override suspend fun readText(path: String, maxChars: Int): String {
        require(maxChars > 0) { "maxChars 必须为正数" }
        return when (val routed = route(path)) {
            is Routed.File -> {
                ensureExistingFile(routed.absolutePath, routed.display)
                ensureTextFile(routed.absolutePath, routed.display)
                readCharsUpTo(routed.absolutePath, maxChars)
            }
            is Routed.Saf -> readSafText(routed, maxChars)
        }
    }

    override suspend fun writeText(path: String, content: String) {
        when (val routed = route(path)) {
            is Routed.File -> writeAtomic(routed.absolutePath, content)
            is Routed.Saf -> writeSafText(routed, content)
        }
    }

    override suspend fun applyEdit(
        path: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean,
    ): EditOutcome {
        require(oldText.isNotEmpty()) { "oldText 为空串" }
        val routed = route(path)
        val content = when (routed) {
            is Routed.File -> {
                ensureExistingFile(routed.absolutePath, routed.display)
                readAllText(routed.absolutePath)
            }
            is Routed.Saf -> readAllSafText(routed)
        }
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
                when (routed) {
                    is Routed.File -> writeAtomic(routed.absolutePath, replaced)
                    is Routed.Saf -> writeSafText(routed, replaced)
                }
                EditOutcome.Applied(if (replaceAll) matches else 1, replaced.length)
            }
        }
    }

    override suspend fun textLength(path: String): Long = when (val routed = route(path)) {
        is Routed.File -> {
            ensureExistingFile(routed.absolutePath, routed.display)
            ensureTextFile(routed.absolutePath, routed.display)
            countFileChars(routed.absolutePath)
        }
        is Routed.Saf -> textLengthSaf(routed)
    }

    override suspend fun isDirectory(path: String): Boolean = when (val routed = route(path)) {
        is Routed.File -> Files.isDirectory(routed.absolutePath)
        is Routed.Saf -> querySafOrNull(routed)?.isDirectory ?: false
    }

    override suspend fun listDirectory(path: String): List<SandboxEntry> = when (val routed = route(path)) {
        is Routed.File -> listFileDirectory(routed.absolutePath, routed.display)
        is Routed.Saf -> listSafDirectory(routed)
    }

    // ---- 内部：路由 ----

    private fun route(path: String): Routed {
        val snapshot = table
        val segments = parseSegments(path)
        val matchedIndex = snapshot.mounts.indexOfFirst { segments.isNotEmpty() && it.root.id == segments.first() }
        val rootIndex = if (matchedIndex >= 0) matchedIndex else 0
        val mount = snapshot.mounts[rootIndex]
        val relSegments = if (matchedIndex >= 0) segments.drop(1) else segments
        val rel = relSegments.joinToString("/")
        return when (mount) {
            is RootMount.FileRoot -> {
                val abs = mount.fileRoot.directory.resolve(rel).normalize()
                ensureInsideRoot(mount, abs, path)
                Routed.File(mount, rel, abs, path)
            }
            is RootMount.SafRoot -> Routed.Saf(mount, rel, path)
        }
    }

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
    private fun ensureInsideRoot(mount: RootMount.FileRoot, abs: Path, original: String) {
        val root = mount.fileRoot
        if (!abs.startsWith(root.directory)) {
            throw SandboxPathException("路径越界: " + original)
        }
        val realRoot = mount.realDir
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

    // ---- 内部：File 根 ----

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

    private fun countFileChars(abs: Path): Long {
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

    private fun listFileDirectory(abs: Path, display: String): List<SandboxEntry> {
        if (!Files.isDirectory(abs)) throw SandboxIoException("目标不是目录: " + display)
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

    // ---- 内部：SAF 根 ----

    private fun querySafOrNull(routed: Routed.Saf): SafDocumentInfo? = try {
        routed.mount.safRoot.gateway.queryDocument(routed.mount.safRoot.treeUri, routed.relativePath)
    } catch (e: SafNotFoundException) {
        null
    } catch (e: SafGatewayException) {
        throw SandboxIoException("访问授权目录失败: " + routed.display + "（" + (e.message ?: "SAF 错误") + "）", e)
    } catch (e: Exception) {
        throw SandboxIoException("访问授权目录失败: " + routed.display, e)
    }

    /** 读取前确认目标存在且为文件；目录按 File 根的既有语义报 E_IO。 */
    private fun requireSafFile(routed: Routed.Saf): SafDocumentInfo {
        val info = querySafOrNull(routed) ?: throw SandboxFileNotFoundException("文件不存在: " + routed.display)
        if (info.isDirectory) throw SandboxIoException("目标是目录: " + routed.display)
        return info
    }

    /**
     * 打开 SAF 输入流并做二进制嗅探；SAF 失败的稳定翻译：
     * 不存在 → [SandboxFileNotFoundException]，其余 → [SandboxIoException]。
     */
    private fun <T> withSafInput(routed: Routed.Saf, block: (PushbackInputStream) -> T): T {
        val stream = try {
            routed.mount.safRoot.gateway.openRead(routed.mount.safRoot.treeUri, routed.relativePath)
        } catch (e: SafNotFoundException) {
            throw SandboxFileNotFoundException("文件不存在: " + routed.display, e)
        } catch (e: SafGatewayException) {
            throw SandboxIoException("读取失败: " + routed.display + "（" + (e.message ?: "SAF 错误") + "）", e)
        } catch (e: Exception) {
            throw SandboxIoException("读取失败: " + routed.display, e)
        }
        return stream.use { raw ->
            val input = PushbackInputStream(raw, BINARY_SNIFF_BYTES)
            try {
                sniffBinary(input, routed.display)
                block(input)
            } catch (e: SandboxBinaryFileException) {
                throw e
            } catch (e: IOException) {
                throw SandboxIoException("读取失败: " + routed.display, e)
            }
        }
    }

    private fun sniffBinary(input: PushbackInputStream, display: String) {
        val sniff = ByteArray(BINARY_SNIFF_BYTES)
        var total = 0
        while (total < sniff.size) {
            val n = input.read(sniff, total, sniff.size - total)
            if (n < 0) break
            total += n
        }
        for (i in 0 until total) {
            if (sniff[i] == 0.toByte()) throw SandboxBinaryFileException("二进制文件: " + display)
        }
        if (total > 0) input.unread(sniff, 0, total)
    }

    private fun readSafText(routed: Routed.Saf, maxChars: Int): String {
        requireSafFile(routed)
        return withSafInput(routed) { input ->
            val reader = InputStreamReader(input, StandardCharsets.UTF_8)
            val sb = StringBuilder()
            val buf = CharArray(4096)
            while (sb.length < maxChars) {
                val need = minOf(buf.size, maxChars - sb.length)
                val n = reader.read(buf, 0, need)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
            sb.toString()
        }
    }

    private fun readAllSafText(routed: Routed.Saf): String {
        requireSafFile(routed)
        return withSafInput(routed) { input ->
            val reader = InputStreamReader(input, StandardCharsets.UTF_8)
            val sb = StringBuilder()
            val buf = CharArray(8192)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
            sb.toString()
        }
    }

    private fun textLengthSaf(routed: Routed.Saf): Long {
        requireSafFile(routed)
        return withSafInput(routed) { input ->
            val reader = InputStreamReader(input, StandardCharsets.UTF_8)
            val buf = CharArray(8192)
            var total = 0L
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                total += n
            }
            total
        }
    }

    private fun writeSafText(routed: Routed.Saf, content: String) {
        val stream = try {
            routed.mount.safRoot.gateway.openWrite(routed.mount.safRoot.treeUri, routed.relativePath)
        } catch (e: SafGatewayException) {
            throw SandboxIoException("写入失败: " + routed.display + "（" + (e.message ?: "SAF 错误") + "）", e)
        } catch (e: Exception) {
            throw SandboxIoException("写入失败: " + routed.display, e)
        }
        try {
            stream.use { out ->
                out.write(content.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
        } catch (e: Exception) {
            throw SandboxIoException("写入失败: " + routed.display, e)
        }
    }

    private fun listSafDirectory(routed: Routed.Saf): List<SandboxEntry> {
        val info = querySafOrNull(routed) ?: throw SandboxIoException("目标不是目录: " + routed.display)
        if (!info.isDirectory) throw SandboxIoException("目标不是目录: " + routed.display)
        val children = try {
            routed.mount.safRoot.gateway.listChildren(
                routed.mount.safRoot.treeUri,
                routed.relativePath,
            )
        } catch (e: SafNotFoundException) {
            throw SandboxIoException("目标不是目录: " + routed.display, e)
        } catch (e: SafGatewayException) {
            throw SandboxIoException("列目录失败: " + routed.display + "（" + (e.message ?: "SAF 错误") + "）", e)
        } catch (e: Exception) {
            throw SandboxIoException("列目录失败: " + routed.display, e)
        }
        return children
            .map { SandboxEntry(it.name, it.isDirectory, if (it.isDirectory) -1L else it.sizeBytes) }
            .sortedBy { it.name }
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
