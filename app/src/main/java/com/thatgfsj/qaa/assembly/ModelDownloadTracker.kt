package com.thatgfsj.qaa.assembly

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 下载任务状态（由 [ModelDownloadTracker] 持久化）。 */
enum class DownloadStatus {

    /** 进程内正在下载。 */
    RUNNING,

    /** 上次进程退出时仍在下载；重启后转为可续传。 */
    INTERRUPTED,

    /** 失败；[DownloadRecord.resumable] 决定是否提示继续。 */
    FAILED,

    /** 已下载完成并登记清单。 */
    COMPLETED,
}

/**
 * 一条下载记录：目标名 / 来源 URL / 状态 / 最近进度。
 *
 * @param target 模型名（`org/repo` 的 repo 段），用于进度与续传提示
 * @param totalBytes 总字节；SDK 未知时为 null（进度条退化为不确定态）
 * @param resumable true 表示同参数重跑可由 GenieX 原生断点续传（已下载分块仍保留）
 */
data class DownloadRecord(
    val url: String,
    val target: String,
    val status: DownloadStatus,
    val bytes: Long,
    val totalBytes: Long?,
    val updatedAt: Long,
    val resumable: Boolean = false,
    val message: String? = null,
) {
    /** 已下载比例；总长未知为 null。 */
    val percent: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { total ->
            (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
        }
}

/**
 * 下载任务记录器：把进行中/已完成任务写入私有清单文件（TSV，原子替换）。
 *
 * 与 GenieX 断点续传的分工：SDK 负责保留已下载分块并按参数续传，本记录器只回答
 * 「上次下到哪、是否值得提示继续」——重启后把遗留 [DownloadStatus.RUNNING] 翻成
 * [DownloadStatus.INTERRUPTED]，UI 依 [DownloadRecord.resumable] 提示继续。
 *
 * 进度事件密集，落盘按 [PERSIST_INTERVAL_MS] 节流；状态切换（开始/完成/失败/放弃）强制落盘。
 * 落盘失败不阻断下载（下次状态变化重试），内存态始终最新。
 */
class ModelDownloadTracker(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutex = Mutex()
    private val records = LinkedHashMap<String, DownloadRecord>()
    private var loaded = false
    private var lastPersistAt = 0L

    /** 全部记录（最近更新在前）。 */
    suspend fun records(): List<DownloadRecord> = locked { records.values.sortedByDescending { it.updatedAt } }

    /** 按来源 URL 取记录。 */
    suspend fun find(url: String): DownloadRecord? = locked { records[url] }

    /** 最近一条可续传记录（无则 null），不改变状态。 */
    suspend fun resumable(): DownloadRecord? = locked { latestResumable() }

    /** 开始或重启一个任务：同 URL 重跑视为续传，保留已知进度。 */
    suspend fun start(url: String, target: String): DownloadRecord = locked {
        val previous = records[url]
        val record = DownloadRecord(
            url = url,
            target = target,
            status = DownloadStatus.RUNNING,
            bytes = previous?.bytes ?: 0L,
            totalBytes = previous?.totalBytes,
            updatedAt = clock(),
        )
        records[url] = record
        lastPersistAt = clock()
        persist()
        record
    }

    /** 更新最近进度；节流落盘（未到间隔只更新内存态）。 */
    suspend fun progress(url: String, bytes: Long, totalBytes: Long?): Unit = locked {
        val current = records[url] ?: return@locked
        val record = current.copy(
            bytes = bytes,
            totalBytes = totalBytes ?: current.totalBytes,
            updatedAt = clock(),
        )
        records[url] = record
        if (record.updatedAt - lastPersistAt >= PERSIST_INTERVAL_MS) {
            lastPersistAt = record.updatedAt
            persist()
        }
    }

    /** 完成：进度归位到总量，清空可续传标记。 */
    suspend fun markCompleted(url: String): Unit = locked {
        val current = records[url] ?: return@locked
        records[url] = current.copy(
            status = DownloadStatus.COMPLETED,
            bytes = current.totalBytes ?: current.bytes,
            updatedAt = clock(),
            resumable = false,
            message = null,
        )
        lastPersistAt = clock()
        persist()
    }

    /** 中断（用户取消或进程退出清点）：转为可续传。 */
    suspend fun markInterrupted(url: String, message: String = "下载已中断"): DownloadRecord? = locked {
        val current = records[url] ?: return@locked null
        val record = current.copy(
            status = DownloadStatus.INTERRUPTED,
            resumable = true,
            updatedAt = clock(),
            message = message,
        )
        records[url] = record
        lastPersistAt = clock()
        persist()
        record
    }

    /** 失败：按事件给出的 resumable 决定是否提示继续。 */
    suspend fun markFailed(url: String, message: String, resumable: Boolean): DownloadRecord? = locked {
        val current = records[url] ?: return@locked null
        val record = current.copy(
            status = DownloadStatus.FAILED,
            resumable = resumable,
            updatedAt = clock(),
            message = message,
        )
        records[url] = record
        lastPersistAt = clock()
        persist()
        record
    }

    /** 放弃续传：只清除记录（SDK 侧的已下载分块由后续清理任务处理）。 */
    suspend fun discard(url: String): Unit = locked {
        if (records.remove(url) != null) {
            lastPersistAt = clock()
            persist()
        }
    }

    /**
     * 按模型名清理记录（#111 删除模型联动）：目标名大小写不敏感匹配，返回清除条数。
     *
     * 记录以来源 URL 为键、target 为模型名投影，因此同时比对 URL 末段，覆盖
     * `https://…/org/repo` 与 target=`repo` 的常见形态；无匹配时不落盘。
     */
    suspend fun discardByTarget(target: String): Int = locked {
        val needle = target.trim().lowercase()
        if (needle.isEmpty()) return@locked 0
        val matched = records.filterValues { record ->
            record.target.lowercase() == needle ||
                record.url.substringAfterLast('/').substringBefore('?').lowercase() == needle
        }.keys.toList()
        if (matched.isEmpty()) return@locked 0
        matched.forEach { records.remove(it) }
        lastPersistAt = clock()
        persist()
        matched.size
    }

    /** 启动恢复检测：遗留 RUNNING 转 INTERRUPTED，返回最近可续传记录（无则 null）。 */
    suspend fun recover(): DownloadRecord? = locked {
        var changed = false
        records.keys.toList().forEach { key ->
            val record = records[key] ?: return@forEach
            if (record.status == DownloadStatus.RUNNING) {
                records[key] = record.copy(
                    status = DownloadStatus.INTERRUPTED,
                    resumable = true,
                    updatedAt = clock(),
                    message = record.message ?: "上次退出时下载未完成",
                )
                changed = true
            }
        }
        if (changed) {
            lastPersistAt = clock()
            persist()
        }
        latestResumable()
    }

    private fun latestResumable(): DownloadRecord? =
        records.values.filter { it.resumable }.maxByOrNull { it.updatedAt }

    private suspend fun <T> locked(block: () -> T): T = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoaded()
            block()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        runCatching {
            file.readLines().forEach { line ->
                val record = decode(line) ?: return@forEach
                records[record.url] = record
            }
        }
    }

    private fun persist() {
        runCatching {
            val parent = file.absoluteFile.parentFile ?: File(".")
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("无法创建下载记录目录：" + parent.absolutePath)
            }
            val snapshot = records.values.sortedByDescending { it.updatedAt }.take(MAX_RECORDS)
            val tmp = File(parent, file.name + ".tmp")
            try {
                tmp.bufferedWriter().use { writer ->
                    writer.write(HEADER)
                    writer.newLine()
                    snapshot.forEach { record ->
                        writer.write(encode(record))
                        writer.newLine()
                    }
                }
                if (file.exists() && !file.delete()) {
                    throw IOException("无法覆盖下载记录：" + file.absolutePath)
                }
                if (!tmp.renameTo(file)) throw IOException("下载记录改名失败：" + tmp.absolutePath)
            } catch (throwable: Throwable) {
                tmp.delete()
                throw throwable
            }
        }
    }

    private fun encode(record: DownloadRecord): String = listOf(
        escape(record.url),
        escape(record.target),
        record.status.name,
        record.bytes.toString(),
        record.totalBytes?.toString().orEmpty(),
        record.updatedAt.toString(),
        record.resumable.toString(),
        escape(record.message.orEmpty()),
    ).joinToString("\t")

    private fun decode(line: String): DownloadRecord? {
        val trimmed = line.trimEnd('\r')
        if (trimmed.isBlank() || trimmed.startsWith("#")) return null
        val fields = trimmed.split('\t')
        if (fields.size < FIELD_COUNT) return null
        val status = DownloadStatus.entries.firstOrNull { it.name == fields[2].trim() } ?: return null
        val url = unescape(fields[0])
        if (url.isEmpty()) return null
        return DownloadRecord(
            url = url,
            target = unescape(fields[1]),
            status = status,
            bytes = fields[3].trim().toLongOrNull() ?: 0L,
            totalBytes = fields[4].trim().toLongOrNull(),
            updatedAt = fields[5].trim().toLongOrNull() ?: 0L,
            resumable = fields[6].trim().toBooleanStrictOrNull() ?: false,
            message = unescape(fields[7]).ifEmpty { null },
        )
    }

    /** 字段内制表符/换行转义，保证一行一条记录。 */
    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "")

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    't' -> { append('\t'); index += 2 }
                    'n' -> { append('\n'); index += 2 }
                    '\\' -> { append('\\'); index += 2 }
                    else -> { append(char); index++ }
                }
            } else {
                append(char)
                index++
            }
        }
    }

    companion object {

        /** 记录条数上限（保留最近任务，避免清单无限增长）。 */
        const val MAX_RECORDS = 20

        /** 进度落盘节流间隔（毫秒）。 */
        const val PERSIST_INTERVAL_MS = 1000L

        /** 记录文件名（位于 filesDir，随应用卸载清除）。 */
        const val FILE_NAME = "model-downloads.tsv"

        private const val FIELD_COUNT = 8
        private const val HEADER = "# url\ttarget\tstatus\tbytes\ttotalBytes\tupdatedAt\tresumable\tmessage"
    }
}
