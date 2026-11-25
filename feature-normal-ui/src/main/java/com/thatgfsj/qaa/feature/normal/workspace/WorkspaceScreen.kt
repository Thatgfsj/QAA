package com.thatgfsj.qaa.feature.normal.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Normal 工作区页（Issue #126）：列表 + 新建（内置目录 / 手机目录 SAF）+ 切换 / 重命名 / 删除。
 *
 * SAF 目录选择依赖 Activity 结果 API，页面不直接申请权限：点击「从手机目录新建」时调用
 * 宿主回调 [onPickWorkspaceTree]，选择结果由 :app 登记后经端口事件推回并刷新清单。
 * 删除有会话的工作区会二次确认并提示会话文件一并删除；当前工作区与内置默认工作区不可删。
 */
@Composable
fun WorkspaceScreen(
    port: NormalWorkspacePort?,
    onPickWorkspaceTree: () -> Unit,
    viewModel: WorkspaceViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(port) {
        viewModel.bindPort(port)
    }
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val busyId by viewModel.busyId.collectAsStateWithLifecycle()
    val createName by viewModel.createName.collectAsStateWithLifecycle()
    val pendingRemove by viewModel.pendingRemove.collectAsStateWithLifecycle()
    val pendingRename by viewModel.pendingRename.collectAsStateWithLifecycle()
    val renameName by viewModel.renameName.collectAsStateWithLifecycle()

    pendingRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRemove,
            title = { Text("删除工作区") },
            text = {
                Text(
                    if (entry.sessionCount > 0) {
                        "确认删除工作区「${entry.name}」？该工作区还有 ${entry.sessionCount} 个会话，" +
                            "会话文件将一并删除，操作不可撤销；外部目录只解除授权、不删除手机上的文件。"
                    } else {
                        "确认删除工作区「${entry.name}」？外部目录只解除授权、不删除手机上的文件，操作不可撤销。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRemove) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRemove) { Text("取消") }
            },
        )
    }

    pendingRename?.let { entry ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRename,
            title = { Text("重命名工作区") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = viewModel::onRenameNameChange,
                    singleLine = true,
                    label = { Text("新名称") },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRename) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRename) { Text("取消") }
            },
        )
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("工作区", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "工作区决定工具沙盒的默认根，会话按工作区隔离。切换后新轮次的文件读写落在新根目录。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (port == null) {
            Text("工作区入口未接线：请确认 Normal 窗口由正式装配启动。")
            return@Column
        }

        OutlinedTextField(
            value = createName,
            onValueChange = viewModel::onCreateNameChange,
            singleLine = true,
            label = { Text("新建工作区名称") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::createInternal,
                enabled = busyId == null,
            ) { Text("新建内置工作区") }
            OutlinedButton(
                onClick = onPickWorkspaceTree,
                enabled = busyId == null,
            ) { Text("从手机目录新建") }
        }

        notice?.let { text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::consumeNotice) { Text("知道了") }
            }
        }
        if (busyId != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("正在处理…", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (entries.isEmpty()) {
            Text("暂无工作区。")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { entry ->
                    WorkspaceCard(
                        entry = entry,
                        busy = busyId == entry.id,
                        onSelect = { viewModel.select(entry) },
                        onRename = { viewModel.requestRename(entry) },
                        onDelete = { viewModel.requestRemove(entry) },
                    )
                }
            }
        }
    }
}

/** 单个工作区卡片：名称、来源 / 根 / 会话数标签与操作按钮。 */
@Composable
private fun WorkspaceCard(
    entry: NormalWorkspaceEntry,
    busy: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                entry.name + if (entry.current) "（当前）" else "",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${entry.sourceLabel} · 根：${entry.rootDisplay} · 会话 ${entry.sessionCount} 个" +
                    if (entry.writable) "" else " · 只读授权",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("id：${entry.id}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSelect, enabled = !entry.current && !busy) { Text("切换") }
                OutlinedButton(onClick = onRename, enabled = !busy) { Text("重命名") }
                TextButton(
                    onClick = onDelete,
                    enabled = !entry.current && entry.id != WorkspaceViewModel.BUILT_IN_ID && !busy,
                ) { Text("删除") }
            }
        }
    }
}
