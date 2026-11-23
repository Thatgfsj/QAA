package com.thatgfsj.qaa.feature.normal.models

import androidx.lifecycle.ViewModel
import com.thatgfsj.qaa.feature.normal.model.ModelInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 模型管理页 ViewModel（Issue #8，占位目录数据）。
 *
 * 严禁接真实引擎：加载/卸载仅为目录状态占位；
 * 真实加载链路（含 ModelMemoryCoordinator 互斥状态机）属 Issue #6。
 */
class ModelManageViewModel : ViewModel() {

    private val _models = MutableStateFlow(defaultCatalog())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    /** 占位动作：切换某条目的加载状态。 */
    fun toggleLoaded(modelId: String) {
        _models.update { list ->
            list.map { if (it.id == modelId) it.copy(loaded = !it.loaded) else it }
        }
    }

    private fun defaultCatalog(): List<ModelInfo> = listOf(
        ModelInfo(
            id = "supra2.5-pro-instruct-q4_0",
            name = "Supra2.5-Pro-Instruct",
            quantization = "Q4_0",
            sizeBytes = 40L * 1024 * 1024,
            source = "liodon-ai/Supra2.5-Pro-Instruct-GGUF",
            loaded = false,
        ),
    )
}
