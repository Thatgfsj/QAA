package com.thatgfsj.qaa.feature.normal.status

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thatgfsj.qaa.feature.normal.model.InferenceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 推理状态源契约：Issue #5（后端降级链）接线点。
 * 届时真实实现订阅 BackendFallbackManager 的命中后端流并替换 fake。
 */
interface InferenceStatusSource {
    val status: StateFlow<InferenceStatus>
}

/** 骨架期 fake 状态源：固定 Supra2.5-Pro-Instruct(Q4_0) / CHAT / NPU。 */
class FakeInferenceStatusSource : InferenceStatusSource {
    override val status: StateFlow<InferenceStatus> = MutableStateFlow(
        InferenceStatus(
            modelName = "Supra2.5-Pro-Instruct",
            quantization = "Q4_0",
            agentMode = "CHAT",
            backend = "NPU",
        )
    ).asStateFlow()
}

/**
 * 顶部后端状态栏（Issue #8，双前端共用同一数据源契约）。
 * 展示格式：[MODEL] 名称(量化) | [CHAT/AGENT] | [NPU/HYBRID/CPU]。
 *
 * 安全区：状态栏与刘海 / 挖孔区域接入 WindowInsets，圆角屏不遮挡文字；
 * 其余 Normal 布局沿用 Scaffold 的默认行为。
 */
@Composable
fun BackendStatusBar(status: InferenceStatus, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = "[MODEL] ${status.modelName}(${status.quantization})",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.weight(0.2f))
            Text(
                text = "[${status.agentMode}]",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.padding(horizontal = 6.dp))
            Text(
                text = "[${status.backend}]",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
