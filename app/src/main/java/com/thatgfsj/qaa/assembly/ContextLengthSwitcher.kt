package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.ContextWindow
import com.thatgfsj.qaa.core.inference.ComputeBackend
import com.thatgfsj.qaa.core.inference.LlmEnginePort
import com.thatgfsj.qaa.core.inference.LlmLoadConfig
import java.io.File

/** /ctx 切换结果：调用方据此决定是否落盘（#106 决策 2）。 */
internal sealed interface ContextLengthChangeResult {

    val message: String

    /** 引擎未加载、档位相同或档位 > [ContextLengthSwitcher.SAFE_AUTO_RELOAD_MAX]：只保存设置。 */
    data class Deferred(val next: Int, override val message: String) : ContextLengthChangeResult

    /** 已按新档位卸载并重载成功，立即生效。 */
    data class Applied(val next: Int, override val message: String) : ContextLengthChangeResult

    /** 新档位重载失败；[restored] 表示旧档位是否已重新载入成功。 */
    data class RolledBack(override val message: String, val restored: Boolean) : ContextLengthChangeResult
}

/** 变更结果分类：纯文案函数与切换结果共用的稳定枚举。 */
internal enum class ContextLengthChange {
    APPLIED,
    SAVED_ONLY,
    NOT_LOADED,
    ROLLED_BACK,
    ROLLBACK_FAILED,
}

/**
 * 上下文长度即时生效策略（#106 决策 2）：
 * - 引擎已加载且目标档位 ≤ [SAFE_AUTO_RELOAD_MAX]（8192）：卸载并按新 nCtx 重载同一模型；
 *   失败时按旧档位重载一次回滚，调用方不得落盘；
 * - 目标档位 > [SAFE_AUTO_RELOAD_MAX]：只保存设置，不自动重载（长上下文在 HTP/NPU 上
 *   有 abort 风险，交用户显式重新载入模型）；
 * - 引擎未加载 / 没有可重载模型：只保存，下次载入模型时生效。
 *
 * 与 [BackendSwitcher] 同构：只操作 LLM 引擎本身，不经过 ModelMemoryCoordinator。
 */
internal class ContextLengthSwitcher(
    private val engine: LlmEnginePort,
    private val statusSink: (String) -> Unit = {},
) {

    /**
     * 应用一次 /ctx 变更。
     *
     * @param modelSource 引擎已载入的模型路径（null 表示没有可重载模型）
     * @param current 当前生效档位（用于失败回滚）
     * @param requested 目标档位（必须是 [ContextWindow.OPTIONS] 之一）
     * @param backend 当前锁定后端：重载必须沿用，避免顺带改后端
     */
    suspend fun set(
        modelSource: String?,
        current: Int,
        requested: Int,
        backend: ComputeBackend = ComputeBackend.AUTO,
    ): ContextLengthChangeResult {
        require(ContextWindow.isValid(requested)) { "非法的上下文档位：$requested" }
        if (requested == current) {
            return ContextLengthChangeResult.Deferred(
                requested,
                "当前上下文长度已是 $requested token，无需重载模型。",
            )
        }
        if (requested > SAFE_AUTO_RELOAD_MAX) {
            return ContextLengthChangeResult.Deferred(
                requested,
                describeContextLengthChange(current, requested, ContextLengthChange.SAVED_ONLY),
            )
        }
        if (modelSource == null || !engine.isLoaded.value) {
            return ContextLengthChangeResult.Deferred(
                requested,
                describeContextLengthChange(current, requested, ContextLengthChange.NOT_LOADED),
            )
        }
        return reload(modelSource, current, requested, backend)
    }

    private suspend fun reload(
        modelSource: String,
        current: Int,
        requested: Int,
        backend: ComputeBackend,
    ): ContextLengthChangeResult {
        statusSink("正在按新的上下文长度重载模型…")
        AppLog.modelUnloaded(File(modelSource).name)
        engine.unload()
        val failure = engine
            .load(modelSource, LlmLoadConfig(backend = backend, nCtx = requested))
            .exceptionOrNull()
        if (failure == null) {
            statusSink(AgentRuntime.READY_TEXT)
            return ContextLengthChangeResult.Applied(
                requested,
                describeContextLengthChange(current, requested, ContextLengthChange.APPLIED),
            )
        }
        val reason = failure.message ?: failure::class.simpleName.orEmpty()
        engine.unload()
        val restored = engine
            .load(modelSource, LlmLoadConfig(backend = backend, nCtx = current))
            .isSuccess
        statusSink(if (restored) AgentRuntime.READY_TEXT else "上下文长度切换失败，模型未载入")
        return if (restored) {
            ContextLengthChangeResult.RolledBack(
                describeContextLengthChange(current, requested, ContextLengthChange.ROLLED_BACK, reason),
                restored = true,
            )
        } else {
            ContextLengthChangeResult.RolledBack(
                describeContextLengthChange(
                    current,
                    requested,
                    ContextLengthChange.ROLLBACK_FAILED,
                    reason,
                ),
                restored = false,
            )
        }
    }

    companion object {

        /** 允许自动重载的最大档位：更大档位只保存设置，避免长上下文在 HTP/NPU 上 abort。 */
        const val SAFE_AUTO_RELOAD_MAX = 8192
    }
}

/** 变更回执文案（纯函数，单测锁定）。 */
internal fun describeContextLengthChange(
    current: Int,
    requested: Int,
    change: ContextLengthChange,
    detail: String? = null,
): String = when (change) {
    ContextLengthChange.APPLIED ->
        "已切换上下文长度：$current → $requested token（模型已按新值重新载入）。"
    ContextLengthChange.SAVED_ONLY ->
        "已保存上下文长度：$requested token（原 $current）；大于 " +
            "${ContextLengthSwitcher.SAFE_AUTO_RELOAD_MAX} token，需重新载入模型后生效" +
            "（长上下文在 HTP/NPU 上可能 abort）。"
    ContextLengthChange.NOT_LOADED ->
        "已保存上下文长度：$requested token（原 $current）；引擎尚未加载，下次载入模型时生效。"
    ContextLengthChange.ROLLED_BACK ->
        "上下文长度切换失败：${detail ?: "未知原因"}；已回退 $current token 并重新载入模型。"
    ContextLengthChange.ROLLBACK_FAILED ->
        "上下文长度切换失败：${detail ?: "未知原因"}；$current token 重载也失败，" +
            "请用 chat <模型名|序号> 重新载入。"
}

/** /ctx 查询与 /status 共用文案：设置值 / 引擎实际 nCtx / 派生提示预算。 */
internal fun describeContextLength(setting: Int, engine: Int, budget: Int): String =
    "上下文：设置 $setting（引擎 $engine）→ 预算 $budget token；" +
        "档位：${ContextWindow.OPTIONS.joinToString(" / ")}（/ctx <档位> 修改）"
