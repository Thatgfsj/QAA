package com.thatgfsj.qaa.feature.terminal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 终端命令执行体的端口集合（Issue #19 接线）。
 *
 * 终端模块只声明「要什么」，实现由 :app 装配层注入（core-inference 的 ModelCatalog /
 * ModelPullClient 与 Agent 运行链路），保持 TUI→HAR 的模块依赖图不变、命令路由可 JVM 单测。
 */

/** 模型清单端口：由 :app 用 core-inference 的 ModelCatalog 实现，序号从 1 起。 */
fun interface TerminalModelListPort {
    suspend fun entries(): List<LocalModelEntry>
}

/** 拉取进度：core-inference ModelPullEvent 的终端投影（进度、完成、失败）。 */
sealed interface TerminalPullEvent {

    /** 已下载字节；[percent] 在总长未知时为 null。 */
    data class Progress(val bytes: Long, val totalBytes: Long?, val percent: Float?) : TerminalPullEvent

    /** 完成并已登记清单；[path] 为模型管理器给出的权威路径。 */
    data class Completed(val name: String, val path: String?) : TerminalPullEvent

    /** 失败；[resumable] 为 true 表示可用相同参数重跑续传。 */
    data class Failed(val code: Int, val message: String, val resumable: Boolean) : TerminalPullEvent
}

/** 模型拉取端口：由 :app 用 ModelPullClient 实现。 */
fun interface TerminalModelPullPort {
    fun pull(url: String): Flow<TerminalPullEvent>
}

/** Agent 运行状态（决定普通输入是开新轮还是进 Follow-up 队列）。 */
enum class TerminalAgentState {

    /** 首次启动链进行中（释放模型 / 初始化 SDK / 加载模型）。 */
    PREPARING,

    /** 空闲，可接受新轮次。 */
    IDLE,

    /** 轮次进行中。 */
    RUNNING,

    /** 运行链不可用（初始化失败或引擎缺席）。 */
    UNAVAILABLE,
}

/**
 * Agent 会话端口：由 :app 的 AgentRuntime 实现，命令路由只经此接口触发真实链路。
 *
 * 所有返回字符串的挂起方法都返回可直接回显的中文结果（成功或失败原因），
 * 由命令路由写入对话区，避免 UI 侧再拼错误文案。
 */
interface TerminalAgentPort {

    /** 运行状态流（装配层据此决定排队或直接执行）。 */
    val state: StateFlow<TerminalAgentState>

    /** 执行一个用户轮次；确认流由 Agent Loop 经确认门自行挂起。 */
    suspend fun runTurn(text: String)

    /** /steer：运行中干预。 */
    suspend fun steer(text: String): String

    /** Follow-up：轮末衔接的新指令。 */
    suspend fun followUp(text: String): String

    /** backend 命令：锁定请求后端（下次载入模型时交给引擎降级链）。 */
    suspend fun lockBackend(request: BackendRequest): String

    /** chat / agent 选定模型：解析清单条目并载入引擎。 */
    suspend fun selectModel(token: String): String

    /** /tree：渲染会话树。 */
    suspend fun tree(): String

    /** /fork [节点]：以指定节点为分叉点开新分支。 */
    suspend fun fork(nodeId: String?): String

    /** /compact：手动压缩上下文。 */
    suspend fun compact(): String

    /** /skills：技能注册表状态。 */
    suspend fun skills(): String

    /** /status 附加上半段之外的运行态信息（模型清单数、会话树、队列）。 */
    suspend fun statusExtra(): String
}
