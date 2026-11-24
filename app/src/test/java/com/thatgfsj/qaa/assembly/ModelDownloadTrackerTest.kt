package com.thatgfsj.qaa.assembly

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 下载记录器状态机 JVM 单测（Issue #80 验收）：进行中/完成/失败可续传/恢复检测。 */
class ModelDownloadTrackerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000L

    private fun newTracker(file: File = File(folder.root, ModelDownloadTracker.FILE_NAME)) =
        ModelDownloadTracker(file = file, clock = { now }, ioDispatcher = Dispatchers.Unconfined)

    @Test
    fun 开始任务_记录为进行中并落盘() = runTest {
        val tracker = newTracker()
        val record = tracker.start(URL, "repo")

        assertEquals(DownloadStatus.RUNNING, record.status)
        assertEquals("repo", record.target)
        assertEquals(0L, record.bytes)
        assertFalse(record.resumable)
        // 新实例（模拟进程重启）从文件读回同一条记录。
        val reopened = newTracker().records().single()
        assertEquals(URL, reopened.url)
        assertEquals(DownloadStatus.RUNNING, reopened.status)
    }

    @Test
    fun 进度更新_内存立即生效且落盘按间隔节流() = runTest {
        val tracker = newTracker()
        tracker.start(URL, "repo")

        now += 500
        tracker.progress(URL, bytes = 512, totalBytes = 4096)
        // 未到落盘间隔：内存已最新，文件仍是开始时的 0 字节。
        assertEquals(512L, tracker.find(URL)?.bytes)
        assertEquals(0L, newTracker().find(URL)?.bytes)

        now += 1_000
        tracker.progress(URL, bytes = 2048, totalBytes = 4096)
        val persisted = newTracker().find(URL)
        assertEquals(2048L, persisted?.bytes)
        assertEquals(4096L, persisted?.totalBytes)
        assertEquals(0.5f, persisted?.percent)
    }

    @Test
    fun 完成_进度归位且不再可续传() = runTest {
        val tracker = newTracker()
        tracker.start(URL, "repo")
        tracker.progress(URL, bytes = 1024, totalBytes = 4096)
        tracker.markCompleted(URL)

        val record = tracker.find(URL)
        assertEquals(DownloadStatus.COMPLETED, record?.status)
        assertEquals(4096L, record?.bytes)
        assertFalse(record?.resumable ?: true)
        assertNull(tracker.resumable())
        assertEquals(DownloadStatus.COMPLETED, newTracker().find(URL)?.status)
    }

    @Test
    fun 可续传失败_保留记录并提供继续() = runTest {
        val tracker = newTracker()
        tracker.start(URL, "repo")
        tracker.progress(URL, bytes = 2048, totalBytes = 4096)

        val failed = tracker.markFailed(URL, "网络中断", resumable = true)

        assertEquals(DownloadStatus.FAILED, failed?.status)
        assertTrue(failed?.resumable ?: false)
        val resumable = tracker.resumable()
        assertNotNull(resumable)
        assertEquals(2048L, resumable?.bytes)
        assertEquals(0.5f, resumable?.percent)
    }

    @Test
    fun 不可续传失败_不提示继续() = runTest {
        val tracker = newTracker()
        tracker.start(URL, "repo")
        tracker.markFailed(URL, "hub 无此模型", resumable = false)

        assertNull(tracker.resumable())
        assertEquals(DownloadStatus.FAILED, newTracker().find(URL)?.status)
    }

    @Test
    fun 恢复检测_遗留进行中转为已中断() = runTest {
        newTracker().start(URL, "repo")

        val recovered = newTracker().recover()

        assertEquals(URL, recovered?.url)
        assertEquals(DownloadStatus.INTERRUPTED, recovered?.status)
        assertTrue(recovered?.resumable ?: false)
        assertEquals("上次退出时下载未完成", recovered?.message)
        // 恢复后的状态已落盘，二次检测不再变化（幂等）。
        val again = newTracker().recover()
        assertEquals(DownloadStatus.INTERRUPTED, again?.status)
    }

    @Test
    fun 放弃续传_清除记录() = runTest {
        val tracker = newTracker()
        tracker.start(URL, "repo")
        tracker.markFailed(URL, "网络中断", resumable = true)

        tracker.discard(URL)

        assertNull(tracker.resumable())
        assertTrue(tracker.records().isEmpty())
        assertTrue(newTracker().records().isEmpty())
    }

    @Test
    fun 记录上限_只保留最近任务() = runTest {
        val tracker = newTracker()
        repeat(ModelDownloadTracker.MAX_RECORDS + 5) { index ->
            now += 1
            tracker.start("https://huggingface.co/org/repo-$index", "repo-$index")
        }

        val reopened = newTracker().records()
        assertEquals(ModelDownloadTracker.MAX_RECORDS, reopened.size)
        assertEquals("repo-" + (ModelDownloadTracker.MAX_RECORDS + 4), reopened.first().target)
    }

    @Test
    fun 按模型名清理记录_删除模型后不留遗留任务() = runTest {
        val tracker = newTracker()
        tracker.start(URL, "repo")
        tracker.start("https://huggingface.co/org/other", "other")

        val removed = tracker.discardByTarget("repo")

        assertEquals(1, removed)
        assertNull(tracker.find(URL))
        // 落盘同步：重启后也不会恢复出已删模型的下载记录。
        assertNull(newTracker().find(URL))
        assertNotNull(newTracker().find("https://huggingface.co/org/other"))
        // 无匹配时返回 0 且不动其他记录。
        assertEquals(0, tracker.discardByTarget("missing"))
    }

    private companion object {
        const val URL = "https://huggingface.co/org/repo"
    }
}
