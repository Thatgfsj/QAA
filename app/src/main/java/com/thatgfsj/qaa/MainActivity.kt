package com.thatgfsj.qaa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.thatgfsj.qaa.assembly.AgentRuntime
import com.thatgfsj.qaa.core.harness.ContextWindow
import com.thatgfsj.qaa.core.harness.PromptAssembler
import com.thatgfsj.qaa.data.ContextLengthStore
import com.thatgfsj.qaa.data.SystemPromptStore
import com.thatgfsj.qaa.data.UiModeStore
import com.thatgfsj.qaa.data.dataStoreContextLengthStore
import com.thatgfsj.qaa.data.dataStoreSystemPromptStore
import com.thatgfsj.qaa.data.dataStoreUiModeStore
import com.thatgfsj.qaa.feature.normal.NormalApp
import com.thatgfsj.qaa.feature.normal.confirm.NormalGate
import com.thatgfsj.qaa.feature.normal.status.FakeInferenceStatusSource
import kotlinx.coroutines.launch

/**
 * 入口 Activity：按 ui_mode 路由至 Normal / Terminal 前端（Issue #7）。
 * ui_mode 经 DataStore 持久化；设置页切换写入后立即重启 App 生效。
 *
 * Agent 运行链（[AgentRuntime]）在 Activity 生命周期内单例持有并随启动触发首启链，
 * 双前端共享同一会话树、技能表与内存互斥状态机；确认门由当前前端在组合期绑定。
 */
class MainActivity : ComponentActivity() {

    private val uiModeStore: UiModeStore by lazy { dataStoreUiModeStore(this) }
    private val contextLengthStore: ContextLengthStore by lazy { dataStoreContextLengthStore(this) }
    private val systemPromptStore: SystemPromptStore by lazy { dataStoreSystemPromptStore(this) }
    private val restarter: AppRestarter by lazy { ActivityAppRestarter(this) }
    private val runtime: AgentRuntime by lazy { AgentRuntime.create(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime.start()
        setContent {
            val mode by uiModeStore.mode.collectAsStateWithLifecycle(initialValue = null)
            // 上下文长度设置（#106）：设置页只保存，重新载入模型（或终端 /ctx）后生效。
            val contextTokens by contextLengthStore.tokens
                .collectAsStateWithLifecycle(initialValue = ContextWindow.DEFAULT)
            // 系统提示词设置（#105）：与终端 /system 共用 DataStore，保存后下一轮组装生效。
            val systemPrompt by systemPromptStore.prompt
                .collectAsStateWithLifecycle(initialValue = PromptAssembler.DEFAULT_SYSTEM_PROMPT)
            when (val route = mode?.let(::resolveStartRoute)) {
                StartRoute.Normal -> {
                    // 确认门在组合期绑定到运行链：工具执行前由中文对话框挂起决策。
                    val confirmGate = remember { NormalGate() }
                    DisposableEffect(runtime, confirmGate) {
                        runtime.bindConfirmationGate(confirmGate)
                        onDispose { }
                    }
                    NormalApp(
                        statusSource = remember { FakeInferenceStatusSource() },
                        onSwitchToTerminal = ::switchToTerminal,
                        confirmGate = confirmGate,
                        agentPort = runtime,
                        modelPort = runtime,
                        contextTokens = contextTokens,
                        onContextTokensChange = ::saveContextLength,
                        systemPrompt = systemPrompt,
                        onSystemPromptSave = ::saveSystemPrompt,
                        onSystemPromptReset = ::resetSystemPrompt,
                    )
                }
                StartRoute.Terminal -> TerminalHost(
                    runtime = runtime,
                    onStartNormalWindow = ::switchToNormalWindow,
                )
                null -> LoadingPlaceholder()
            }
        }
    }

    /** 设置页"切换至终端模式"接线：写 ui_mode → 立即重启。 */
    private fun switchToTerminal() {
        lifecycleScope.launch {
            uiModeStore.setMode(UiMode.TERMINAL)
            restarter.restart()
        }
    }

    /** 设置页"上下文长度"接线（#106）：只保存，不重载引擎；立即生效走终端 /ctx。 */
    private fun saveContextLength(tokens: Int) {
        lifecycleScope.launch { contextLengthStore.setTokens(tokens) }
    }

    /** 设置页"保存系统提示词"接线（#105）：写同一 DataStore key，空白草稿等价恢复默认。 */
    private fun saveSystemPrompt(text: String) {
        lifecycleScope.launch {
            if (text.isBlank()) systemPromptStore.reset() else systemPromptStore.setPrompt(text)
        }
    }

    /** 设置页"恢复默认系统提示词"接线（#105）。 */
    private fun resetSystemPrompt() {
        lifecycleScope.launch { systemPromptStore.reset() }
    }

    /** 终端 `start window` 接线：写 ui_mode = NORMAL → 立即重启回正常窗口。 */
    private fun switchToNormalWindow() {
        lifecycleScope.launch {
            uiModeStore.setMode(UiMode.NORMAL)
            restarter.restart()
        }
    }
}
