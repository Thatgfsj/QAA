package com.thatgfsj.qaa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.thatgfsj.qaa.data.UiModeStore
import com.thatgfsj.qaa.data.dataStoreUiModeStore
import com.thatgfsj.qaa.feature.normal.NormalApp
import com.thatgfsj.qaa.feature.normal.status.FakeInferenceStatusSource
import kotlinx.coroutines.launch

/**
 * 入口 Activity：按 ui_mode 路由至 Normal / Terminal 前端（Issue #7）。
 * ui_mode 经 DataStore 持久化；设置页切换写入后立即重启 App 生效。
 */
class MainActivity : ComponentActivity() {

    private val uiModeStore: UiModeStore by lazy { dataStoreUiModeStore(this) }
    private val restarter: AppRestarter by lazy { ActivityAppRestarter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val mode by uiModeStore.mode.collectAsStateWithLifecycle(initialValue = null)
            when (val route = mode?.let(::resolveStartRoute)) {
                StartRoute.Normal -> NormalApp(
                    statusSource = remember { FakeInferenceStatusSource() },
                    onSwitchToTerminal = ::switchToTerminal,
                )
                StartRoute.Terminal -> TerminalHost(onStartNormalWindow = ::switchToNormalWindow)
                null -> LoadingPlaceholder()
            }
        }
    }

    /** 设置页"切换至极客终端模式"接线：写 ui_mode → 立即重启。 */
    private fun switchToTerminal() {
        lifecycleScope.launch {
            uiModeStore.setMode(UiMode.TERMINAL)
            restarter.restart()
        }
    }

    /** 终端 `start window` 接线：写 ui_mode = NORMAL → 立即重启回正常窗口。 */
    private fun switchToNormalWindow() {
        lifecycleScope.launch {
            uiModeStore.setMode(UiMode.NORMAL)
            restarter.restart()
        }
    }
}
