package com.thatgfsj.qaa.assembly

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 助手流式事件（装配层 → UI）：一次生成步对应一条 AI 行。
 *
 * 三态与 UI 入口一一对应：Started → beginAssistantTurn；Delta → appendToken；
 * Finished 携带该步最终可见文本 —— 空串表示整步只有工具调用 JSON（UI 丢弃该行），
 * null 表示生成异常中断（保留已流式内容）。streamId 单调递增，供 UI 做迟到 / 乱序防护。
 */
sealed interface AssistantStreamEvent {

    val streamId: Long

    /** 生成步首个片段到达，UI 开启占位 AI 行。 */
    data class Started(override val streamId: Long) : AssistantStreamEvent

    /** 增量片段，UI 追加到当前 AI 行。 */
    data class Delta(override val streamId: Long, val text: String) : AssistantStreamEvent

    /** 生成步收口：text 为空表示丢弃该行，null 表示保留已流式内容。 */
    data class Finished(override val streamId: Long, val text: String?) : AssistantStreamEvent
}

/**
 * token 级流式转发器（生成步粒度）。
 *
 * 生命周期由装配层驱动：首个 token 到达时惰性开流（Started），生成步末由
 * [RecordingTurnRecorder.appendAssistant] 收口（Finished 定稿或丢弃），轮次结束由
 * [AgentRuntime] 兜底收口（异常中断时不丢已流式内容）。
 *
 * [silenced] 供压缩摘要等内部生成期间静默：置位时片段直接丢弃且不开启新行，
 * 避免内部 token 混入用户可见文本（与关遥测语义一致）。
 *
 * 线程契约：开流 / 收口与引擎回调同处一个轮次协程（[AgentRuntime] 以轮次互斥串行化），
 * 事件总线本身线程安全；[silenced] 由装配层在轮次内切换，标记为 @Volatile 以防越线程可见性。
 */
class AssistantTokenRelay {

    private val _events = MutableSharedFlow<AssistantStreamEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 流式事件流（UI 装配期收集）。 */
    val events: SharedFlow<AssistantStreamEvent> = _events.asSharedFlow()

    /** 内部生成期间静默（压缩摘要生成时由装配层置位）。 */
    @Volatile
    var silenced: Boolean = false

    private var nextStreamId = 1L
    private var openStreamId: Long? = null

    /** 开启一轮：上个轮次若遗留未收口的行，按保留已流式内容收口。 */
    fun beginTurn() {
        endTurn()
    }

    /** 转发一个 token 片段；首个片段惰性开启本步 AI 行。 */
    fun emit(text: String) {
        if (text.isEmpty() || silenced) return
        val id = openStreamId ?: nextStreamId++.also { streamId ->
            openStreamId = streamId
            _events.tryEmit(AssistantStreamEvent.Started(streamId))
        }
        _events.tryEmit(AssistantStreamEvent.Delta(id, text))
    }

    /**
     * 生成步收口：返回该步是否已流式输出（true 时整步文本已逐字上屏，调用方不再整段重放）。
     * [finalText] 为该步最终可见文本，空串表示丢弃该行，null 表示保留已流式内容。
     */
    fun finishStep(finalText: String?): Boolean {
        val id = openStreamId ?: return false
        _events.tryEmit(AssistantStreamEvent.Finished(id, finalText))
        openStreamId = null
        return true
    }

    /** 轮次收口：未走生成步收口的残留行保留已流式内容后关闭。 */
    fun endTurn() {
        val id = openStreamId ?: return
        openStreamId = null
        if (!silenced) _events.tryEmit(AssistantStreamEvent.Finished(id, null))
    }

    companion object {
        /** 事件缓冲：UI 消费滞后时丢弃最旧片段，不阻塞引擎生成。 */
        private const val EVENT_BUFFER = 256
    }
}
