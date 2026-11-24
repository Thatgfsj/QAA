package com.thatgfsj.qaa.feature.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 模型载入占位行单测（Issue #88）：状态流驱动的出现、原地更新、静默移除与清屏恢复。
 * 占位行不依赖任何固定时长，状态未变则界面不变。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalModelLoadTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun 载入中显示占位行_完成后静默移除() = runTest {
        val viewModel = TerminalViewModel()
        val source = MutableStateFlow<TerminalModelLoadState>(TerminalModelLoadState.Idle)
        viewModel.bindModelLoad(source)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.entries.isEmpty())

        source.value = TerminalModelLoadState.Loading("Qwen3-4B")
        advanceUntilIdle()
        val entry = viewModel.uiState.value.entries.single()
        assertEquals(TerminalRole.SYSTEM, entry.role)
        assertEquals("正在载入模型 Qwen3-4B…", entry.text)
        assertEquals(TerminalViewModel.MODEL_LOAD_TAG, entry.tag)

        source.value = TerminalModelLoadState.Idle
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.entries.isEmpty())
    }

    @Test
    fun 连续切换模型_占位行原地更新不叠加() = runTest {
        val viewModel = TerminalViewModel()
        val source = MutableStateFlow<TerminalModelLoadState>(TerminalModelLoadState.Idle)
        viewModel.bindModelLoad(source)
        advanceUntilIdle()

        source.value = TerminalModelLoadState.Loading("Qwen3-4B")
        advanceUntilIdle()
        source.value = TerminalModelLoadState.Loading("Supra2.5-Pro-Instruct")
        advanceUntilIdle()

        val entries = viewModel.uiState.value.entries
        assertEquals(1, entries.size)
        assertEquals("正在载入模型 Supra2.5-Pro-Instruct…", entries.single().text)
    }

    @Test
    fun 清屏时载入未完成_占位行恢复() = runTest {
        val viewModel = TerminalViewModel()
        val source = MutableStateFlow<TerminalModelLoadState>(TerminalModelLoadState.Idle)
        viewModel.bindModelLoad(source)
        advanceUntilIdle()

        source.value = TerminalModelLoadState.Loading("Qwen3-4B")
        advanceUntilIdle()
        viewModel.appendOutput("旧会话内容")

        viewModel.resetSession()
        val entries = viewModel.uiState.value.entries
        assertEquals(1, entries.size)
        assertEquals(TerminalViewModel.MODEL_LOAD_TAG, entries.single().tag)

        source.value = TerminalModelLoadState.Idle
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.entries.isEmpty())
    }

    @Test
    fun 载入失败_占位行移除后只保留失败原因() = runTest {
        val viewModel = TerminalViewModel()
        val source = MutableStateFlow<TerminalModelLoadState>(TerminalModelLoadState.Idle)
        viewModel.bindModelLoad(source)
        advanceUntilIdle()

        source.value = TerminalModelLoadState.Loading("Qwen3-4B")
        advanceUntilIdle()
        source.value = TerminalModelLoadState.Idle
        advanceUntilIdle()
        viewModel.appendOutput("模型加载失败：解码器不兼容")

        val entry = viewModel.uiState.value.entries.single()
        assertEquals("模型加载失败：解码器不兼容", entry.text)
        assertNull(entry.tag)
    }

    @Test
    fun 载入占位文案固定格式() {
        assertEquals("正在载入模型 Qwen3-4B…", modelLoadPlaceholderText("Qwen3-4B"))
    }
}
