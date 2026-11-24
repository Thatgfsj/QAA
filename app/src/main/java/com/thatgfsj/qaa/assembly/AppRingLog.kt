package com.thatgfsj.qaa.assembly

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 日志级别：导出成单字母标记（D / I / W / E），与常见终端日志习惯一致。 */
enum class RingLogLevel(val marker: String) {
    DEBUG("D"),
    INFO("I"),
    WARN("W"),
    ERROR("E"),
}

/** 单行日志：缓冲里只存 epoch 毫秒，导出时按导出时区格式化。 */
data class RingLogRecord(
    val timeMillis: Long,
    val level: RingLogLevel,
    val tag: String,
    val message: String,
)

/**
 * 应用内环形日志（#94）：普通 App 拿不到 READ_LOGS（系统权限），
 * 关键事件改为进程内自采集——固定容量、超限丢最旧行、导出时再格式化。
 *
 * 线程安全：工具执行与 SDK 回调可能在后台线程写入，读写共用同一把锁；
 * 写入只做入队与淘汰，不做字符串格式化，避免拖慢运行链。
 */
class AppRingLog(
    capacity: Int = CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 有效容量：至少 1 行，避免淘汰循环空转。 */
    val capacity: Int = capacity.coerceAtLeast(1)

    private val records = ArrayDeque<RingLogRecord>()
    private val lock = Any()

    /** 当前缓冲行数（不超过 [capacity]）。 */
    val size: Int get() = synchronized(lock) { records.size }

    fun debug(tag: String, message: String) = record(RingLogLevel.DEBUG, tag, message)

    fun info(tag: String, message: String) = record(RingLogLevel.INFO, tag, message)

    fun warn(tag: String, message: String) = record(RingLogLevel.WARN, tag, message)

    fun error(tag: String, message: String) = record(RingLogLevel.ERROR, tag, message)

    /** 写入一行；空白标签 / 消息直接忽略，避免噪声行挤占容量。 */
    fun record(level: RingLogLevel, tag: String, message: String) {
        if (tag.isBlank() || message.isBlank()) return
        val record = RingLogRecord(clock(), level, tag.trim(), message.trim())
        synchronized(lock) {
            records.addLast(record)
            while (records.size > capacity) records.removeFirst()
        }
    }

    /**
     * 取最近 [limit] 行（时间顺序）：不足时给全部，[limit] <= 0 给空列表。
     * 返回快照副本，导出期间的新写入不影响本次结果。
     */
    fun snapshot(limit: Int = capacity): List<RingLogRecord> {
        if (limit <= 0) return emptyList()
        return synchronized(lock) {
            val from = (records.size - limit).coerceAtLeast(0)
            records.drop(from)
        }
    }

    fun clear() {
        synchronized(lock) { records.clear() }
    }

    companion object {

        /** 缓冲容量（行）：500 行足以覆盖一次故障前后的关键路径，内存占用可忽略。 */
        const val CAPACITY = 500

        /** 进程级单例：装配层所有埋点写这一份，/logs 从这里导出。 */
        val global = AppRingLog()
    }
}

/** /logs 默认导出行数（与 help 表文案一致）。 */
const val APP_LOG_DEFAULT_LINES = 200

/** /logs 导出目录（沙盒相对路径）：exports/logs。 */
internal const val APP_LOG_EXPORT_DIR = "exports/logs"

private const val APP_LOG_FILE_PREFIX = "log-"
private const val APP_LOG_FILE_EXTENSION = ".txt"

private val LOG_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

private val LOG_FILE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)

/** 导出落点：exports/logs/log-<yyyyMMdd-HHmmss-SSS>.txt（毫秒精度，连按两次不互相覆盖）。 */
internal fun appLogExportPath(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val stamp = LOG_FILE_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
    return APP_LOG_EXPORT_DIR + "/" + APP_LOG_FILE_PREFIX + stamp + APP_LOG_FILE_EXTENSION
}

/**
 * 导出正文：文件头（导出时间 / 本次行数 / 缓冲占用）+ 逐行「时间 级别 标签 消息」。
 * 纯函数，单测锁定格式；行数与落点回执由 [describeLogExport] 生成。
 */
internal fun formatAppLogExport(
    records: List<RingLogRecord>,
    exportedAtMillis: Long,
    buffered: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val stamp = LOG_TIME_FORMAT.format(Instant.ofEpochMilli(exportedAtMillis).atZone(zone))
    return buildString {
        appendLine("# QAA 应用内日志导出")
        appendLine("# 导出时间：$stamp")
        appendLine("# 本次导出 ${records.size} 行 / 缓冲共 $buffered 行（容量上限 ${AppRingLog.CAPACITY} 行）")
        appendLine("# 格式：时间 级别 标签 消息")
        records.forEach { record ->
            val time = LOG_TIME_FORMAT.format(Instant.ofEpochMilli(record.timeMillis).atZone(zone))
            appendLine("$time ${record.level.marker} ${record.tag} ${record.message}")
        }
    }
}

/** /logs 中文回执：成功给「导出 N 行 → 路径」，无记录时说明未写出文件。 */
internal fun describeLogExport(path: String, exported: Int, buffered: Int): String =
    if (exported <= 0) {
        "应用内日志暂无记录；未写出文件（目标：$path）。"
    } else {
        "已导出 $exported 行日志 → $path（缓冲共 $buffered 行）。"
    }

/**
 * 装配层埋点门面（#94）：把关键运行事件写成统一的「标签 + 中文消息」行。
 *
 * 只做格式化，不持有业务状态；写入目标固定为 [AppRingLog.global]。
 * 单测通过清理 global 缓冲后断言顺序与级别，覆盖运行链关键路径。
 */
object AppLog {

    const val TAG_RUNTIME = "Runtime"
    const val TAG_MODEL = "Model"
    const val TAG_BACKEND = "Backend"
    const val TAG_CONFIRM = "Confirm"
    const val TAG_TOOL = "Tool"
    const val TAG_SESSION = "Session"
    const val TAG_EXPORT = "Export"

    private fun suffix(detail: String): String =
        detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()

    /** SDK 初始化结果（首启链）。 */
    fun sdkInit(ok: Boolean, detail: String = "") {
        if (ok) {
            AppRingLog.global.info(TAG_RUNTIME, "推理 SDK 初始化成功${suffix(detail)}")
        } else {
            AppRingLog.global.error(TAG_RUNTIME, "推理 SDK 初始化失败${suffix(detail)}")
        }
    }

    /** 模型载入开始 / 成功 / 失败 / 卸载。 */
    fun modelLoadStart(name: String) {
        AppRingLog.global.info(TAG_MODEL, "开始载入模型 $name")
    }

    fun modelLoaded(name: String, detail: String = "") {
        val tail = detail.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        AppRingLog.global.info(TAG_MODEL, "模型载入成功：$name$tail")
    }

    fun modelLoadFailed(name: String, reason: String) {
        AppRingLog.global.error(TAG_MODEL, "模型载入失败：$name${suffix(reason)}")
    }

    fun modelUnloaded(name: String) {
        AppRingLog.global.info(TAG_MODEL, "已卸载模型 $name")
    }

    /** 后端锁定 / 降级链结果：[fallback] 为 true 表示命中了回滚或降级。 */
    fun backend(request: String, result: String, fallback: Boolean = false) {
        val message = "请求后端 $request；结果：$result"
        if (fallback) {
            AppRingLog.global.warn(TAG_BACKEND, message)
        } else {
            AppRingLog.global.info(TAG_BACKEND, message)
        }
    }

    /** 工具确认：人工放行 / 拒绝。 */
    fun confirmDecision(description: String, allowed: Boolean) {
        AppRingLog.global.info(
            TAG_CONFIRM,
            "${if (allowed) "放行" else "拒绝"}：$description",
        )
    }

    /** 会话级自动决策生效（#93 长按记忆）。 */
    fun confirmAuto(description: String, policy: String) {
        AppRingLog.global.warn(TAG_CONFIRM, "会话级${policy}生效：$description")
    }

    /** 工具执行：名称 / 步序号 / 结果码。 */
    fun toolExecuted(name: String, step: Int, status: String, errorCode: String?) {
        val code = errorCode?.takeIf { it.isNotBlank() }?.let { "，错误码 $it" }.orEmpty()
        AppRingLog.global.info(TAG_TOOL, "第 $step 步 $name：$status$code")
    }

    /** /export：轨迹导出结果。 */
    fun exportTrace(path: String, lines: Int) {
        AppRingLog.global.info(TAG_EXPORT, "轨迹导出：$path（$lines 行）")
    }

    /** /logs：日志导出结果。 */
    fun logsExported(path: String, lines: Int, buffered: Int) {
        AppRingLog.global.info(TAG_EXPORT, "日志导出：$path（$lines 行 / 缓冲 $buffered 行）")
    }

    /** /new：新建会话。 */
    fun sessionOpened(id: String) {
        AppRingLog.global.info(TAG_SESSION, "新建会话：$id")
    }

    /** 运行链错误码（失败原因短码 + 可读信息）。 */
    fun error(code: String, message: String) {
        AppRingLog.global.error(TAG_RUNTIME, "$code${suffix(message)}")
    }
}
