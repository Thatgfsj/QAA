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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thatgfsj.qaa.feature.normal.model.ModelInfo

/**
 * 模型管理页（Issue #8）：内置模型目录占位列表。
 * 加载/卸载为占位动作；真实加载链路（含互斥状态机）属 Issue #6。
 */
@Composable
fun ModelManageScreen(viewModel: ModelManageViewModel = viewModel()) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(models, key = { it.id }) { model ->
            ModelCard(model = model, onToggleLoaded = { viewModel.toggleLoaded(model.id) })
        }
    }
}

@Composable
private fun ModelCard(model: ModelInfo, onToggleLoaded: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${model.name}（${model.quantization}）",
                    style = MaterialTheme.typography.titleMedium,
                )
                LoadedBadge(loaded = model.loaded)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "来源：${model.source} · GGUF",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "体积：${formatSize(model.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onToggleLoaded) {
                Text(text = if (model.loaded) "卸载" else "加载")
            }
            Text(
                text = "占位动作：真实加载/卸载由模型内存互斥状态机接线。",
                style = MaterialTheme.typography.labelSmall,
            )
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

/** 体积格式化（MB，一位小数）。 */
internal fun formatSize(bytes: Long): String = "%.1f MB".format(bytes / 1024f / 1024f)
