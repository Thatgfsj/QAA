package com.thatgfsj.qaa.assembly

import android.app.ActivityManager
import android.content.Context
import com.thatgfsj.qaa.core.inference.GgufKvEstimator
import com.thatgfsj.qaa.core.inference.KvEstimate
import com.thatgfsj.qaa.core.inference.KvEstimateSource
import java.util.Locale

/** 设备内存快照（ActivityManager.MemoryInfo 的可用 / 总量）。 */
internal data class DeviceMemoryInfo(val availBytes: Long, val totalBytes: Long)

/** 设备内存读取端口：Android 实现走 ActivityManager，JVM 单测可注入 fake。 */
internal fun interface DeviceMemoryProbe {
    fun read(): DeviceMemoryInfo
}

/** ActivityManager.MemoryInfo 实现；读取失败时由调用方 runCatching 兜底。 */
internal class AndroidDeviceMemoryProbe(private val context: Context) : DeviceMemoryProbe {

    override fun read(): DeviceMemoryInfo {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return DeviceMemoryInfo(availBytes = info.availMem, totalBytes = info.totalMem)
    }
}

/** 字节数 → 人类可读（GiB / MiB / KiB，保留一位小数）。 */
internal fun formatByteSize(bytes: Long): String = when {
    bytes >= GIB -> "%.1f GiB".format(Locale.ROOT, bytes / GIB.toDouble())
    bytes >= MIB -> "%.1f MiB".format(Locale.ROOT, bytes / MIB.toDouble())
    bytes >= KIB -> "%.1f KiB".format(Locale.ROOT, bytes / KIB.toDouble())
    else -> "$bytes B"
}

/**
 * /ctx 回执（#112）：设置 / 引擎 / 预算 → kv 估算 → 设备内存 → 提示。
 *
 * 用户可见措辞纪律（#112 追加要求）：不出现 abort 字样，统一说「可能因内存溢出闪退」。
 * KV 估算远大于可用内存时追加显式警告，提示载入大概率失败并会自动回退 4096。
 */
internal fun describeContextReceipt(
    setting: Int,
    engine: Int,
    budget: Int,
    kv: KvEstimate,
    kvNctx: Int,
    memory: DeviceMemoryInfo?,
    modelLoaded: Boolean,
    fallbackNotice: String? = null,
): String {
    val kvBytes = GgufKvEstimator.bytesForTokens(kv.bytesPerToken, kvNctx)
    val layout = kv.layout
    val kvLine = when {
        kv.source == KvEstimateSource.GGUF_METADATA && layout != null ->
            "预计 KV 占用：≈ ${formatByteSize(kvBytes)}（f16 估算，依据 GGUF 头：" +
                "${layout.blockCount} 层 × ${layout.kvHeadsSummary} KV heads × ${layout.headDim} 维）"
        modelLoaded ->
            "预计 KV 占用：≈ ${formatByteSize(kvBytes)}（默认估算 144 KiB/token，GGUF 头解析失败）"
        else ->
            "预计 KV 占用：≈ ${formatByteSize(kvBytes)}（默认估算 144 KiB/token，尚未载入模型）"
    }
    val memoryLine = if (memory != null && memory.totalBytes > 0L) {
        "设备内存：可用 ${formatByteSize(memory.availBytes)} / 总 ${formatByteSize(memory.totalBytes)}"
    } else {
        "设备内存：不可用（ActivityManager 读取失败）"
    }
    return buildString {
        appendLine(describeContextLength(setting, engine, budget))
        appendLine(kvLine)
        appendLine(memoryLine)
        if (memory != null && memory.availBytes > 0L && kvBytes > memory.availBytes) {
            appendLine(
                "警告：预计 KV ≈ ${formatByteSize(kvBytes)} > 可用内存 " +
                    "${formatByteSize(memory.availBytes)}，载入大概率失败（会回退 4096）。",
            )
        }
        append("提示：>8192 需重新载入模型生效；长上下文可能因内存溢出闪退（进程级崩溃）。")
        fallbackNotice?.let { notice ->
            appendLine()
            append(notice)
        }
    }
}

private const val KIB = 1024L
private const val MIB = 1024L * 1024L
private const val GIB = 1024L * 1024L * 1024L
