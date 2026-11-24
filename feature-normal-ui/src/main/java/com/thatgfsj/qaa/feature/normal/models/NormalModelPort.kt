package com.thatgfsj.qaa.feature.normal.models

import kotlinx.coroutines.flow.StateFlow

/**
 * Normal 端模型下载/清单端口：由 :app 装配层用 core-inference 的 ModelPullClient 与
 * ModelCatalog 实现并注入。
 *
 * 模块边界：feature 模块不依赖 core-inference，URL 解析规则（Hugging Face / ModelScope
 * 形态）经 [validateSource] 由装配层转发，UI 只消费中文结果。
 */

/** 下载状态（ModelPullEvent 的 UI 投影）。 */
sealed interface NormalDownloadState {

    /** 空闲（无进行中任务，也没有刚结束的提示）。 */
    data object Idle : NormalDownloadState

    /** 进行中；[totalBytes]/[percent] 未知（SDK 未给出总长）时为 null。 */
    data class Running(
        val name: String,
        val bytes: Long,
        val totalBytes: Long?,
        val percent: Float?,
    ) : NormalDownloadState

    /** 失败；[resumable] 为 true 时 SDK 已保留分块，可用同一链接继续。 */
    data class Failed(val message: String, val resumable: Boolean) : NormalDownloadState

    /** 完成并已登记清单；[ordinal] 为清单序号（查询失败为 null）。 */
    data class Completed(val name: String, val path: String?, val ordinal: Int?) : NormalDownloadState
}

/** 待续传任务：App 重启或可续传失败后提示继续（继续即用同一 URL 重跑 pull）。 */
data class NormalResumeTask(
    val url: String,
    val target: String,
    val bytes: Long,
    val totalBytes: Long?,
)

/** 模型清单条目（core-inference ModelCatalog 的 UI 投影，序号从 1 起）。 */
data class NormalModelEntry(
    val ordinal: Int,
    val name: String,
    val quantization: String?,
    val sizeBytes: Long?,
    val sourceLabel: String,
)

/** 模型下载/清单端口。 */
interface NormalModelPort {

    /** 当前下载状态流。 */
    val downloadState: StateFlow<NormalDownloadState>

    /** 待续传任务流（null 表示无需提示）。 */
    val resumeTask: StateFlow<NormalResumeTask?>

    /** 读取真实模型清单。 */
    suspend fun modelEntries(): List<NormalModelEntry>

    /** 按清单序号加载模型，返回可直接展示的中文结果。 */
    suspend fun loadModel(ordinal: Int): String

    /** 校验模型来源；null 表示可下载，否则为中文原因。 */
    fun validateSource(source: String): String?

    /** 开始下载；返回立即失败的中文原因或 null。 */
    fun startDownload(source: String): String?

    /** 取消进行中的下载（SDK 保留分块，记录转为可续传）。 */
    fun cancelDownload()

    /** 放弃续传：清除记录与提示。 */
    fun discardDownload()

    /** 恢复检测（幂等）：把上次进程遗留的任务暴露到 [resumeTask]。 */
    suspend fun recoverDownload()

    /**
     * 删除模型（#111）：SDK 缓存 + 落盘文件 + 清单登记三处同步，返回可直接展示的中文结果。
     * 内置 0 号与当前已加载模型由装配层拒绝并在结果里说明原因。
     */
    suspend fun deleteModel(ordinal: Int): String
}
