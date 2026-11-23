package com.thatgfsj.qaa.feature.normal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.thatgfsj.qaa.feature.normal.chat.ChatScreen
import com.thatgfsj.qaa.feature.normal.models.ModelManageScreen
import com.thatgfsj.qaa.feature.normal.settings.SettingsScreen
import com.thatgfsj.qaa.feature.normal.status.BackendStatusBar
import com.thatgfsj.qaa.feature.normal.status.FakeInferenceStatusSource
import com.thatgfsj.qaa.feature.normal.status.InferenceStatusSource

/** Normal 前端三页路由（Issue #8）。 */
object NormalRoutes {
    const val CHAT = "chat"
    const val MODELS = "models"
    const val SETTINGS = "settings"
}

/**
 * Normal 前端入口：顶部后端状态栏 + 底部三页导航（会话 / 模型 / 设置）。
 *
 * 接线预留：:app 的 ui_mode 启动路由集成属 Issue #10；
 * [onSwitchToTerminal] 仅回调占位，ui_mode 写入与立即重启严禁在本次实现（Issue #7）；
 * [statusSource] 为 Issue #5 真实降级链数据源接入点。
 */
@Composable
fun NormalApp(
    statusSource: InferenceStatusSource = remember { FakeInferenceStatusSource() },
    onSwitchToTerminal: () -> Unit = {},
) {
    val navController = rememberNavController()
    val status by statusSource.status.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        topBar = { BackendStatusBar(status) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == NormalRoutes.CHAT,
                    onClick = { navController.navigateTab(NormalRoutes.CHAT) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("会话") },
                )
                NavigationBarItem(
                    selected = currentRoute == NormalRoutes.MODELS,
                    onClick = { navController.navigateTab(NormalRoutes.MODELS) },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    label = { Text("模型") },
                )
                NavigationBarItem(
                    selected = currentRoute == NormalRoutes.SETTINGS,
                    onClick = { navController.navigateTab(NormalRoutes.SETTINGS) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = NormalRoutes.CHAT,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(NormalRoutes.CHAT) { ChatScreen() }
            composable(NormalRoutes.MODELS) { ModelManageScreen() }
            composable(NormalRoutes.SETTINGS) {
                SettingsScreen(onSwitchToTerminal = onSwitchToTerminal)
            }
        }
    }
}

/** 底部导航统一跳转策略：保留各页状态、避免重复入栈。 */
private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
