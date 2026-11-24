package com.thatgfsj.qaa.assembly

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 应用内环形日志单测（#94）：容量淘汰、顺序、快照边界、导出路径与正文格式，
 * 以及装配层埋点关键路径（fake runtime 层：按运行链顺序调用门面后断言日志）。
 */
class AppRingLogTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    @Before
    fun setUp() {
        // 埋点门面写进程级单例：每个用例先清空，保证行数与顺序可断言。
        AppRingLog.global.clear()
    }

    // ---- 环形缓冲 ----

    @Test
    fun capacityEvictsOldestAndKeepsChronologicalOrder() {
        val ring = AppRingLog(capacity = 3, clock = { 1_000L })
        ring.info("T", "a")
        ring.info("T", "b")
        ring.warn("T", "c")
        ring.error("T", "d")

        assertEquals(3, ring.size)
        assertEquals(listOf("b", "c", "d"), ring.snapshot().map { it.message })
        assertEquals(
            listOf(RingLogLevel.INFO, RingLogLevel.WARN, RingLogLevel.ERROR),
            ring.snapshot().map { it.level },
        )
    }

    @Test
    fun snapshotTakesNewestLinesAndClampsLimits() {
        val ring = AppRingLog(capacity = 10, clock = { 0L })
        (1..5).forEach { ring.info("T", "m$it") }

        assertEquals(listOf("m3", "m4", "m5"), ring.snapshot(3).map { it.message })
        assertEquals(5, ring.snapshot(99).size)
        assertTrue(ring.snapshot(0).isEmpty())
        assertTrue(ring.snapshot(-1).isEmpty())
    }

    @Test
    fun blankTagOrMessageIsIgnored() {
        val ring = AppRingLog(capacity = 5)
        ring.info("", "消息")
        ring.info("T", "   ")
        assertEquals(0, ring.size)
    }

    @Test
    fun capacityIsAtLeastOneLine() {
        val ring = AppRingLog(capacity = 0)
        ring.info("T", "a")
        ring.info("T", "b")
        assertEquals(1, ring.size)
        assertEquals("b", ring.snapshot().single().message)
    }

    @Test
    fun clearEmptiesBuffer() {
        val ring = AppRingLog(capacity = 5)
        ring.info("T", "a")
        ring.clear()
        assertEquals(0, ring.size)
        assertTrue(ring.snapshot().isEmpty())
    }

    // ---- 导出落点与正文 ----

    @Test
    fun exportPathUsesUtcTimestampWithMillis() {
        val millis = Instant.parse("2026-11-24T15:11:02.123Z").toEpochMilli()
        assertEquals("exports/logs/log-20261124-151102-123.txt", appLogExportPath(millis, utc))
    }

    @Test
    fun exportFormatPinsHeaderAndLineLayout() {
        val first = Instant.parse("2026-11-24T15:10:00.000Z").toEpochMilli()
        val second = Instant.parse("2026-11-24T15:11:02.123Z").toEpochMilli()
        val records = listOf(
            RingLogRecord(first, RingLogLevel.INFO, "Model", "模型载入成功：Qwen"),
            RingLogRecord(second, RingLogLevel.ERROR, "Tool", "第 2 步 write：FAILURE，错误码 E_BOOM"),
        )

        val text = formatAppLogExport(records, second, buffered = 7, zone = utc)
        val expected = listOf(
            "# QAA 应用内日志导出",
            "# 导出时间：2026-11-24 15:11:02.123",
            "# 本次导出 2 行 / 缓冲共 7 行（容量上限 500 行）",
            "# 格式：时间 级别 标签 消息",
            "2026-11-24 15:10:00.000 I Model 模型载入成功：Qwen",
            "2026-11-24 15:11:02.123 E Tool 第 2 步 write：FAILURE，错误码 E_BOOM",
        ).joinToString("\n") + "\n"

        assertEquals(expected, text)
    }

    @Test
    fun describeLogExportGivesChineseReceipt() {
        val ok = describeLogExport("exports/logs/log-1.txt", exported = 200, buffered = 340)
        assertTrue(ok.contains("已导出 200 行日志 → exports/logs/log-1.txt"))
        assertTrue(ok.contains("缓冲共 340 行"))
        assertTrue(describeLogExport("exports/logs/log-1.txt", exported = 0, buffered = 0).contains("暂无记录"))
    }

    // ---- 埋点关键路径（fake runtime 层） ----

    @Test
    fun runtimeKeyPathEventsAreRecordedInOrder() {
        // 按运行链顺序触发：首启 → 载入 → 降级 → 确认 → 工具 → 会话 → 导出 → 失败。
        AppLog.sdkInit(true)
        AppLog.modelLoadStart("Qwen")
        AppLog.modelLoaded("Qwen", "后端 NPU")
        AppLog.backend("cpu", "切换后端失败：boom；已回滚到 NPU。", fallback = true)
        AppLog.confirmDecision("write 路径=\"a.txt\"", allowed = true)
        AppLog.confirmAuto("write 路径=\"b.txt\"", policy = "自动放行")
        AppLog.toolExecuted("write", 2, "SUCCESS", null)
        AppLog.sessionOpened("main-2")
        AppLog.exportTrace("exports/session-main-2.jsonl", 3)
        AppLog.logsExported("exports/logs/log-1.txt", 200, 240)
        AppLog.error("E_TURN", "解码失败")

        val lines = AppRingLog.global.snapshot().map { "${it.level.marker} ${it.tag} ${it.message}" }
        assertEquals(11, lines.size)
        assertEquals("I Runtime 推理 SDK 初始化成功", lines[0])
        assertEquals("I Model 开始载入模型 Qwen", lines[1])
        assertEquals("I Model 模型载入成功：Qwen（后端 NPU）", lines[2])
        assertEquals("W Backend 请求后端 cpu；结果：切换后端失败：boom；已回滚到 NPU。", lines[3])
        assertEquals("I Confirm 放行：write 路径=\"a.txt\"", lines[4])
        assertEquals("W Confirm 会话级自动放行生效：write 路径=\"b.txt\"", lines[5])
        assertEquals("I Tool 第 2 步 write：SUCCESS", lines[6])
        assertEquals("I Session 新建会话：main-2", lines[7])
        assertEquals("I Export 轨迹导出：exports/session-main-2.jsonl（3 行）", lines[8])
        assertEquals("I Export 日志导出：exports/logs/log-1.txt（200 行 / 缓冲 240 行）", lines[9])
        assertEquals("E Runtime E_TURN：解码失败", lines[10])
    }

    @Test
    fun runtimeFailurePathsRecordErrorLevelAndCodes() {
        AppLog.sdkInit(false, "原生库缺失")
        AppLog.modelLoadFailed("Qwen", "解码器不兼容")
        AppLog.modelUnloaded("Qwen")
        AppLog.toolExecuted("write", 1, "FAILURE", "E_SANDBOX_DENIED")
        AppLog.error("E_LOG_EXPORT", "沙盒只读")

        val lines = AppRingLog.global.snapshot()
        assertEquals(5, lines.size)
        assertEquals(RingLogLevel.ERROR, lines[0].level)
        assertTrue(lines[0].message.contains("推理 SDK 初始化失败：原生库缺失"))
        assertTrue(lines[1].message.contains("模型载入失败：Qwen：解码器不兼容"))
        assertEquals("已卸载模型 Qwen", lines[2].message)
        assertTrue(lines[3].message.contains("FAILURE，错误码 E_SANDBOX_DENIED"))
        assertEquals(RingLogLevel.ERROR, lines[4].level)
    }

    @Test
    fun logsSnapshotIsolatesExportFromLaterWrites() {
        AppLog.sessionOpened("main")
        val shot = AppRingLog.global.snapshot(200)
        AppLog.sessionOpened("main-2")
        // 快照是副本：导出期间的新日志不改变本次导出行数。
        assertEquals(1, shot.size)
        assertEquals(2, AppRingLog.global.size)
    }
}
