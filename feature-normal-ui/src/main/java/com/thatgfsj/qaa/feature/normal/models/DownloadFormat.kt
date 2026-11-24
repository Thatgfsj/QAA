package com.thatgfsj.qaa.feature.normal.models

import kotlin.math.max

/** 字节数 → 人类可读文本（B/KB/MB/GB，一位小数；负数按 0 处理）。 */
internal fun formatByteCount(bytes: Long): String {
    val value = max(0L, bytes)
    if (value < KIB) return value.toString() + " B"
    var scaled = value / KIB.toDouble()
    var unit = 1
    while (scaled >= KIB && unit < UNITS.size - 1) {
        scaled /= KIB
        unit++
    }
    return "%.1f %s".format(scaled, UNITS[unit])
}

/** 进度文案：已下载 / 总量（百分比）；总量未知时只显示已下载。 */
internal fun progressText(bytes: Long, totalBytes: Long?, percent: Float?): String {
    val done = formatByteCount(bytes)
    if (totalBytes == null || totalBytes <= 0L) return "已下载 " + done
    val ratio = (percent ?: (bytes.toDouble() / totalBytes).toFloat()).coerceIn(0f, 1f)
    return "已下载 " + done + " / " + formatByteCount(totalBytes) + "（" + (ratio * 100).toInt() + "%）"
}

/** 完成态中文提示：名称 / 路径 / 清单序号。 */
internal fun completionText(state: NormalDownloadState.Completed): String =
    "下载完成：" + state.name +
        "，路径 " + (state.path ?: "待 SDK 刷新") +
        "，清单序号 " + (state.ordinal?.toString() ?: "待刷新")

/** 模型体积文案（MB，一位小数）；未知为 null 时显示「未知」。 */
internal fun formatSize(bytes: Long?): String =
    if (bytes == null) "未知" else "%.1f MB".format(bytes / 1024f / 1024f)

private const val KIB = 1024.0
private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")
