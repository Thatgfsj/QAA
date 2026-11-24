package com.thatgfsj.qaa.feature.normal.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thatgfsj.qaa.feature.normal.model.ModelInfo

/**
 * 模型管理页（Issue #8 骨架 + Issue #80 下载区）：URL 下载、进度、续传提示与真实清单。
 *
 * 端口由 :app 装配层经 [modelPort] 注入；未注入时保持骨架占位（目录数据与加载动作均为占位）。
 */
@Composable
fun ModelManageScreen(
    viewModel: ModelManageViewModel = viewModel(),
    modelPort: NormalModelPort? = null,
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val url by viewModel.urlInput.collectAsStateWithLifecycle()
    val error by viewModel.urlError.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    val resume by viewModel.resumeTask.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val bound by viewModel.portBound.collectAsStateWithLifecycle()

    // 端口注入幂等；进入页面即做恢复检测与清单刷新（未完成下载提示继续）。
    LaunchedEffect(modelPort) { viewModel.bindPort(modelPort) }
    LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            DownloadSection(
                url = url,
                error = error,
                state = download,
                resume = resume,
                bound = bound,
                onUrlChange = viewModel::onUrlChange,
                onSubmit = viewModel::submitDownload,
                onCancel = viewModel::cancelDownload,
                onContinue = viewModel::continueDownload,
                onDiscard = viewModel::discardDownload,
            )
        }
        notice?.let { message -> item { NoticeCard(message) } }
        if (models.isEmpty()) {
            item {
                Text(
                    text = "模型清单为空：下载或导入模型后显示在这里。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(models, key = { it.id }) { model ->
            ModelCard(model = model, onActivate = { viewModel.activate(model) })
        }
    }
}

@Composable
private fun DownloadSection(
    url: String,
    error: String?,
    state: NormalDownloadState,
    resume: NormalResumeTask?,
    bound: Boolean,
    onUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
    onDiscard: () -> Unit,
) {
    val running = state is NormalDownloadState.Running
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = "下载模型", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "粘贴 Hugging Face / ModelScope 链接，中断后可继续（已下载分块由 SDK 保留）。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型链接") },
                singleLine = true,
                isError = error != null,
                enabled = !running,
            )
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSubmit, enabled = bound && !running) { Text("下载") }
                if (running) TextButton(onClick = onCancel) { Text("取消") }
            }
            if (!bound) {
                Text(text = "下载入口未接线（骨架预览）。", style = MaterialTheme.typography.labelSmall)
            }
            when (state) {
                NormalDownloadState.Idle -> Unit
                is NormalDownloadState.Running -> {
                    Spacer(Modifier.height(10.dp))
                    val percent = state.percent
                    if (percent == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { percent }, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = progressText(state.bytes, state.totalBytes, percent),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is NormalDownloadState.Failed -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "下载失败：" + state.message +
                            if (state.resumable) "" else "（不可续传，请检查链接）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is NormalDownloadState.Completed -> {
                    Spacer(Modifier.height(10.dp))
                    Text(text = completionText(state), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (resume != null && !running) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "检测到未完成的下载：" + resume.target +
                        "（" + progressText(resume.bytes, resume.totalBytes, null) + "）",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onContinue) { Text("继续下载") }
                    TextButton(onClick = onDiscard) { Text("放弃") }
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ModelCard(model: ModelInfo, onActivate: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = model.name + "（" + model.quantization + "）",
                    style = MaterialTheme.typography.titleMedium,
                )
                LoadedBadge(loaded = model.loaded)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = (if (model.ordinal > 0) "序号 " + model.ordinal + " · " else "") +
                    "来源：" + model.source + " · GGUF",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(text = "体积：" + formatSize(model.sizeBytes), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onActivate) {
                Text(text = if (model.loaded) "卸载" else "加载")
            }
        }
    }
}

@Composable
private fun LoadedBadge(loaded: Boolean) {
    Surface(
        color = if (loaded) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = if (loaded) "已加载" else "未加载",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
