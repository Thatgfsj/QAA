package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.inference.ComputeBackend
import com.thatgfsj.qaa.core.inference.LlmEnginePort
import com.thatgfsj.qaa.core.inference.LlmLoadConfig
import com.thatgfsj.qaa.feature.terminal.BackendRequest

/** backend 锁定结果：装配层据此决定是否采纳新后端。 */
internal sealed interface BackendSwitchResult {

    val message: String

    /** 引擎未加载：只记录锁定值，下次载入模型时生效。 */
    data class Deferred(val next: ComputeBackend, override val message: String) : BackendSwitchResult

    /** 已按新后端重载成功。 */
    data class Applied(val next: ComputeBackend, override val message: String) : BackendSwitchResult

    /** 新后端重载失败；[restored] 表示旧后端是否已重新载入成功。 */
    data class RolledBack(override val message: String, val restored: Boolean) : BackendSwitchResult
}

/**
 * backend 锁定的即时生效策略：引擎已加载时卸载并按新后端重载同一模型；
 * 新后端失败时按旧后端重载一次以恢复可用性，仍失败则提示重新选择模型。
 *
 * 只操作 LLM 引擎本身，不经过 ModelMemoryCoordinator（其状态机仅管 LLM/画图互斥）。
 */
internal class BackendSwitcher(
    private val engine: LlmEnginePort,
    private val statusSink: (String) -> Unit = {},
) {

    /** 引擎未加载（或没有可重载的模型）时零引擎调用，只回传待生效的锁定值。 */
    suspend fun apply(
        modelSource: String?,
        previous: ComputeBackend,
        request: BackendRequest,
    ): BackendSwitchResult {
        val next = request.toComputeBackend()
        if (modelSource == null) {
            return BackendSwitchResult.Deferred(
                next,
                "已锁定请求后端：${request.displayName}；当前没有可重载的模型，下次载入模型时生效。",
            )
        }
        if (!engine.isLoaded.value) {
            return BackendSwitchResult.Deferred(
                next,
                "已锁定请求后端：${request.displayName}；引擎尚未加载，下次载入模型时生效。",
            )
        }
        return reload(modelSource, previous, next, request)
    }

    private suspend fun reload(
        modelSource: String,
        previous: ComputeBackend,
        next: ComputeBackend,
        request: BackendRequest,
    ): BackendSwitchResult {
        statusSink("正在切换推理后端并重载模型…")
        engine.unload()
        val failure = engine.load(modelSource, LlmLoadConfig(backend = next)).exceptionOrNull()
        if (failure == null) {
            statusSink(AgentRuntime.READY_TEXT)
            return BackendSwitchResult.Applied(
                next,
                "已切换推理后端：${request.displayName}（模型已按新后端重新载入）。",
            )
        }
        val reason = failure.message ?: failure::class.simpleName.orEmpty()
        // 新档位失败：先清掉可能残留的半加载状态，再按旧档位重载。
        engine.unload()
        val restored = engine.load(modelSource, LlmLoadConfig(backend = previous)).isSuccess
        statusSink(if (restored) AgentRuntime.READY_TEXT else "后端切换失败，模型未载入")
        return if (restored) {
            BackendSwitchResult.RolledBack(
                "切换后端失败：$reason；已回滚到 ${previous.displayName()} 并重新载入模型。",
                restored = true,
            )
        } else {
            BackendSwitchResult.RolledBack(
                "切换后端失败：$reason；旧后端 ${previous.displayName()} 重载也失败，" +
                    "请用 chat <模型名|序号> 重新载入。",
                restored = false,
            )
        }
    }
}

/** 后端枚举的展示名（与 BackendRequest.displayName 保持一致）。 */
internal fun ComputeBackend.displayName(): String = when (this) {
    ComputeBackend.AUTO -> "auto（自动降级链）"
    ComputeBackend.NPU -> "NPU"
    ComputeBackend.HYBRID -> "HYBRID"
    ComputeBackend.CPU -> "CPU"
}
