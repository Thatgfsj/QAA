package com.thatgfsj.qaa.feature.normal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.thatgfsj.qaa.core.harness.ContextWindow
import com.thatgfsj.qaa.core.harness.PromptAssembler
import com.thatgfsj.qaa.feature.normal.chat.ChatScreen
import com.thatgfsj.qaa.feature.normal.chat.ChatViewModel
import com.thatgfsj.qaa.feature.normal.chat.NormalAgentPort
import com.thatgfsj.qaa.feature.normal.confirm.NormalGate
import com.thatgfsj.qaa.feature.normal.confirm.NormalToolConfirmDialog
import com.thatgfsj.qaa.feature.normal.models.ModelManageScreen
import com.thatgfsj.qaa.feature.normal.models.NormalModelPort
import com.thatgfsj.qaa.feature.normal.settings.SettingsScreen
import com.thatgfsj.qaa.feature.normal.status.BackendStatusBar
import com.thatgfsj.qaa.feature.normal.status.FakeInferenceStatusSource
import com.thatgfsj.qaa.feature.normal.status.InferenceStatusSource
import com.thatgfsj.qaa.feature.normal.workspace.NormalWorkspacePort
import com.thatgfsj.qaa.feature.normal.workspace.WorkspaceScreen

/** Normal 前端三页路由（Issue #8）。 */
object NormalRoutes {
    const val CHAT = "chat"
    const val MODELS = "models"
    const val WORKSPACES = "workspaces"
    const val SETTINGS = "settings"
}

/**
 * Normal 前端入口：顶部后端状态栏 + 底部三页导航（会话 / 模型 / 设置）。
 *
 * 接线预留：:app 的 ui_mode 启动路由集成属 Issue #10；
 * [onSwitchToTerminal] 仅回调占位，ui_mode 写入与立即重启严禁在本次实现（Issue #7）；
 * [statusSource] 为 Issue #5 真实降级链数据源接入点。
 * [confirmGate] 为工具确认门（Issue #14）：本层装配中文确认对话框，AgentLoop 由 :app
 * 注入同一实例后即可在工具执行前挂起；[chatViewModel] 由本层持有并下发给会话页，
 * 避免 NavHost 目标页重复创建实例导致确认状态与确认门脱钩。
 * [modelPort] 为模型下载/清单端口（Issue #80）：null 时模型页保持骨架占位。
 * [contextTokens] / [onContextTokensChange] 为上下文长度设置（#106）：设置页只保存不重载。
 * [systemPrompt] / [onSystemPromptSave] / [onSystemPromptReset] 为系统提示词设置（#105）：
 * 与终端 /system 共用 DataStore，保存后下一轮生效。
 * [workspacePort] 为工作区页端口（#126）；[onPickWorkspaceTree] 由宿主触发 SAF 目录选择。
 */
@Composable
fun NormalApp(
    statusSource: InferenceStatusSource = remember { FakeInferenceStatusSource() },
    onSwitchToTerminal: () -> Unit = {},
    chatViewModel: ChatViewModel = viewModel(),
    confirmGate: NormalGate = remember { NormalGate() },
    agentPort: NormalAgentPort? = null,
    modelPort: NormalModelPort? = null,
    contextTokens: Int = ContextWindow.DEFAULT,
    onContextTokensChange: (Int) -> Unit = {},
    systemPrompt: String = PromptAssembler.DEFAULT_SYSTEM_PROMPT,
    onSystemPromptSave: (String) -> Unit = {},
    onSystemPromptReset: () -> Unit = {},
    workspacePort: NormalWorkspacePort? = null,
    onPickWorkspaceTree: () -> Unit = {},
) {
    val navController = rememberNavController()
    val status by statusSource.status.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val pendingConfirm by confirmGate.pending.collectAsStateWithLifecycle()

    // 确认门挂起状态镜像到会话页（决策仍归 NormalGate）；对话框挂在本层，切页不丢。
    LaunchedEffect(confirmGate, chatViewModel) {
        confirmGate.pending.collect { chatViewModel.setToolConfirmPending(it?.description) }
    }
    // Agent 运行链注入（:app 装配层）：null 时会话页保持骨架 fake 回复。
    LaunchedEffect(agentPort, chatViewModel) {
        chatViewModel.bindAgent(agentPort)
    }
    NormalToolConfirmDialog(
        pending = pendingConfirm,
        onAllow = confirmGate::allow,
        onDeny = confirmGate::deny,
    )

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
                    selected = currentRoute == NormalRoutes.WORKSPACES,
                    onClick = { navController.navigateTab(NormalRoutes.WORKSPACES) },
                    icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                    label = { Text("工作区") },
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
            composable(NormalRoutes.CHAT) { ChatScreen(viewModel = chatViewModel) }
            composable(NormalRoutes.MODELS) { ModelManageScreen(modelPort = modelPort) }
            composable(NormalRoutes.WORKSPACES) {
                WorkspaceScreen(port = workspacePort, onPickWorkspaceTree = onPickWorkspaceTree)
            }
            composable(NormalRoutes.SETTINGS) {
                SettingsScreen(
                    onSwitchToTerminal = onSwitchToTerminal,
                    contextTokens = contextTokens,
                    onContextTokensChange = onContextTokensChange,
                    systemPrompt = systemPrompt,
                    onSystemPromptSave = onSystemPromptSave,
                    onSystemPromptReset = onSystemPromptReset,
                )
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
