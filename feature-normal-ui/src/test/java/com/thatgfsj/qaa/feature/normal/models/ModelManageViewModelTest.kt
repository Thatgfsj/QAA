package com.thatgfsj.qaa.feature.normal.models

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 模型管理页 JVM 单测：骨架占位（Issue #8）+ 下载区（Issue #80）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelManageViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun 默认目录_含冒烟模型_未加载() {
        val vm = ModelManageViewModel()
        val catalog = vm.models.value
        assertEquals(1, catalog.size)
        val supra = catalog.single()
        assertEquals("Supra2.5-Pro-Instruct", supra.name)
        assertEquals("Q4_0", supra.quantization)
        assertEquals(40L * 1024 * 1024, supra.sizeBytes)
        assertFalse(supra.loaded)
    }

    @Test
    fun 切换加载状态_往返翻转() {
        val vm = ModelManageViewModel()
        val id = vm.models.value.single().id
        vm.toggleLoaded(id)
        assertTrue(vm.models.value.single().loaded)
        vm.toggleLoaded(id)
        assertFalse(vm.models.value.single().loaded)
    }

    @Test
    fun 未知id_不影响目录() {
        val vm = ModelManageViewModel()
        vm.toggleLoaded("no-such-model")
        assertFalse(vm.models.value.single().loaded)
    }

    @Test
    fun 体积格式化() {
        assertEquals("40.0 MB", formatSize(40L * 1024 * 1024))
        assertEquals("未知", formatSize(null))
    }

    @Test
    fun 绑定端口_清单替换为真实目录() = runTest {
        val vm = ModelManageViewModel()
        val port = FakeModelPort()
        vm.bindPort(port)
        advanceUntilIdle()

        assertTrue(vm.portBound.value)
        assertEquals(1, vm.models.value.size)
        assertEquals("Supra2.5-Pro-Instruct", vm.models.value.single().name)
        assertEquals(1, vm.models.value.single().ordinal)

        // 进入页面（refresh）触发恢复检测：遗留任务由端口上抛。
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, port.recoverCalls)
    }

    @Test
    fun 空白链接_中文校验错误且不触发下载() = runTest {
        val vm = ModelManageViewModel()
        val port = FakeModelPort()
        vm.bindPort(port)
        advanceUntilIdle()

        vm.onUrlChange("   ")
        vm.submitDownload()

        assertEquals("请填写模型链接（Hugging Face 或 ModelScope）", vm.urlError.value)
        assertTrue(port.started.isEmpty())
    }

    @Test
    fun 端口拒绝链接_错误上屏且不触发下载() = runTest {
        val vm = ModelManageViewModel()
        val port = FakeModelPort(reject = "不支持的模型站点：example.com")
        vm.bindPort(port)
        advanceUntilIdle()

        vm.onUrlChange("https://example.com/org/repo")
        vm.submitDownload()

        assertEquals("不支持的模型站点：example.com", vm.urlError.value)
        assertEquals("https://example.com/org/repo", port.validateCalls.single())
        assertTrue(port.started.isEmpty())
    }

    @Test
    fun 合法链接_触发下载并清空错误() = runTest {
        val vm = ModelManageViewModel()
        val port = FakeModelPort()
        vm.bindPort(port)
        advanceUntilIdle()

        vm.onUrlChange("   ")
        vm.submitDownload()
        vm.onUrlChange("  https://huggingface.co/org/repo  ")
        vm.submitDownload()

        assertNull(vm.urlError.value)
        assertEquals(listOf("https://huggingface.co/org/repo"), port.started)
    }

    @Test
    fun 进度事件_映射到下载状态() = runTest {
        val vm = ModelManageViewModel()
        val port = FakeModelPort()
        vm.bindPort(port)
        advanceUntilIdle()

        port.state.value = NormalDownloadState.Running("repo", 2048, 4096, 0.5f)
        advanceUntilIdle()

        val running = vm.download.value as NormalDownloadState.Running
        assertEquals("repo", running.name)
        assertEquals(0.5f, running.percent)
    }

    @Test
    fun 完成事件_刷新清单并清空输入() = runTest {
        val vm = ModelManageViewModel()
        val port = FakeModelPort()
        vm.bindPort(port)
        advanceUntilIdle()
        vm.onUrlChange("https://huggingface.co/org/repo")

        port.entries = listOf(
            NormalModelEntry(1, "Supra2.5-Pro-Instruct", "Q4_0", 40L, "内置"),
            NormalModelEntry(2, "repo", "Q4_K_M", 8L, "Hub 下载"),
        )
        port.state.value = NormalDownloadState.Completed("repo", "/data/models/repo.gguf", 2)
        advanceUntilIdle()

        assertEquals("", vm.urlInput.value)
        assertEquals(2, vm.models.value.size)
        assertEquals("repo", vm.models.value.last().name)
        assertEquals(2, vm.models.value.last().ordinal)
        assertTrue(vm.download.value is NormalDownloadState.Completed)
    }

    @Test
    fun 失败事件_中文提示上屏() = runTest {
        val vm = ModelManageViewModel()
        val port = FakeModelPort()
        vm.bindPort(port)
        advanceUntilIdle()

        port.state.value = NormalDownloadState.Failed("网络中断", resumable = true)
        advanceUntilIdle()

        assertEquals("下载失败：网络中断", vm.notice.value)
    }

    @Test
    fun 继续下载_使用待续传链接() = runTest {
        val vm = ModelManageViewModel()
        val port = FakeModelPort()
        vm.bindPort(port)
        advanceUntilIdle()

        port.resume.value = NormalResumeTask("https://modelscope.cn/models/org/repo", "repo", 2048, 4096)
        advanceUntilIdle()
        vm.continueDownload()

        assertEquals("https://modelscope.cn/models/org/repo", vm.urlInput.value)
        assertEquals(listOf("https://modelscope.cn/models/org/repo"), port.started)
    }

    @Test
    fun 取消下载_转调端口() = runTest {
        val vm = ModelManageViewModel()
        val port = FakeModelPort()
        vm.bindPort(port)
        advanceUntilIdle()

        vm.cancelDownload()
        vm.discardDownload()

        assertEquals(1, port.cancels)
        assertEquals(1, port.discards)
        assertTrue(vm.download.value is NormalDownloadState.Idle)
    }

    @Test
    fun 载入模型_按钮载入中态随挂起调用复位() = runTest {
        val vm = ModelManageViewModel()
        val gate = CompletableDeferred<Unit>()
        // 内置模型序号为 0：同样能触达加载动作（序号 0 不是「未接线」占位）。
        val port = object : FakeModelPort(
            entries = listOf(NormalModelEntry(0, "Supra2.5-Pro-Instruct", "Q4_0", 40L, "内置")),
        ) {
            override suspend fun loadModel(ordinal: Int): String {
                gate.await()
                return "模型已载入：" + ordinal
            }
        }
        vm.bindPort(port)
        advanceUntilIdle()

        vm.activate(vm.models.value.single())
        runCurrent()
        assertEquals(0, vm.loadingOrdinal.value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.loadingOrdinal.value)
        assertEquals("模型已载入：0", vm.notice.value)
    }

    @Test
    fun 完成提示_含名称路径序号() {
        val text = completionText(NormalDownloadState.Completed("repo", "/data/models/repo.gguf", 2))
        assertTrue(text.contains("repo"))
        assertTrue(text.contains("/data/models/repo.gguf"))
        assertTrue(text.contains("2"))
    }

    @Test
    fun 进度文案_总量未知只显示已下载() {
        assertEquals("已下载 512 B", progressText(512, null, null))
        assertEquals("已下载 1.0 KB / 2.0 KB（50%）", progressText(1024, 2048, null))
    }

    /** 假端口：记录调用参数，状态由测试显式推送。 */
    private open class FakeModelPort(
        var entries: List<NormalModelEntry> = listOf(
            NormalModelEntry(1, "Supra2.5-Pro-Instruct", "Q4_0", 40L * 1024 * 1024, "内置"),
        ),
        private val reject: String? = null,
    ) : NormalModelPort {

        val state = MutableStateFlow<NormalDownloadState>(NormalDownloadState.Idle)
        val resume = MutableStateFlow<NormalResumeTask?>(null)
        val started = mutableListOf<String>()
        val validateCalls = mutableListOf<String>()
        var recoverCalls = 0
        var cancels = 0
        var discards = 0

        override val downloadState: StateFlow<NormalDownloadState> get() = state
        override val resumeTask: StateFlow<NormalResumeTask?> get() = resume

        override suspend fun modelEntries(): List<NormalModelEntry> = entries

        override suspend fun loadModel(ordinal: Int): String = "模型已载入：" + ordinal

        override fun validateSource(source: String): String? {
            validateCalls += source
            return reject
        }

        override fun startDownload(source: String): String? {
            started += source
            return null
        }

        override fun cancelDownload() {
            cancels++
        }

        override fun discardDownload() {
            discards++
        }

        override suspend fun recoverDownload() {
            recoverCalls++
        }
    }
}
