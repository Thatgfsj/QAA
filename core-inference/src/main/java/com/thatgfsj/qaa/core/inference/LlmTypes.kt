package com.thatgfsj.qaa.core.inference

import kotlinx.coroutines.flow.Flow

/** 会话消息（角色 + 文本），用于把多轮历史注入 chat template。 */
data class LlmMessage(val role: String, val content: String) {

    init {
        require(role.isNotBlank()) { "role 不能为空" }
    }

    companion object {
        fun system(content: String): LlmMessage = LlmMessage("system", content)
        fun user(content: String): LlmMessage = LlmMessage("user", content)
        fun assistant(content: String): LlmMessage = LlmMessage("assistant", content)
    }
}

/**
 * 单轮生成请求。
 *
 * @param messages 完整多轮历史（最后一条为本轮用户输入）
 * @param maxTokens 生成长度上限；GenieX 的 GenerationConfig 默认仅 32，必须显式给值
 * @param temperature 采样温度；null 表示沿用插件默认，0 为贪心解码
 * @param toolsJson 工具定义 JSON，null 表示不启用工具调用模板
 * @param enableThinking 是否启用模型思考模式
 */
data class LlmRequest(
    val messages: List<LlmMessage>,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Float? = null,
    val toolsJson: String? = null,
    val enableThinking: Boolean = false,
) {

    init {
        require(messages.isNotEmpty()) { "messages 不能为空" }
        require(maxTokens > 0) { "maxTokens 必须为正数" }
        require(temperature == null || temperature >= 0f) { "temperature 不能为负" }
    }

    companion object {
        const val DEFAULT_MAX_TOKENS = 512
    }
}

/** 流式生成事件。 */
sealed interface LlmStreamEvent {

    /** 增量文本片段（token 或 token 组）。 */
    data class Token(val text: String) : LlmStreamEvent

    /** 生成正常结束，携带本轮指标。 */
    data class Completed(val metrics: InferenceMetrics) : LlmStreamEvent

    /** 生成期错误（SDK 错误事件或 chat template 失败）。 */
    data class Failed(val cause: Throwable) : LlmStreamEvent
}

/** 模型加载配置。 */
data class LlmLoadConfig(
    /** 锁定后端；默认 AUTO 走完整降级链。 */
    val backend: ComputeBackend = ComputeBackend.AUTO,
    /** GenieX runtime_id；GGUF 固定为 llama_cpp。 */
    val runtimeId: String = RUNTIME_LLAMA_CPP,
    /** 上下文长度，0 表示沿用模型自带默认。 */
    val nCtx: Int = 4096,
    val nThreads: Int = 8,
    val nThreadsBatch: Int = 8,
    /** HTP 电源模式（"low_power_saver".."burst"）；空串表示 SDK 默认 burst。 */
    val powerMode: String = "",
) {

    init {
        require(nCtx >= 0) { "nCtx 不能为负" }
        require(nThreads > 0 && nThreadsBatch > 0) { "线程数必须为正数" }
    }

    companion object {
        const val RUNTIME_LLAMA_CPP = "llama_cpp"
    }
}

/** SDK 初始化入口（幂等）；Android 实现见 [GenieXSdkClient]，单测可注入 fake。 */
fun interface SdkInitializer {
    suspend fun initialize(): Result<Unit>
}

/**
 * 模型路径解析：把调用方给的模型来源转换为可直接喂给 LlmCreateInput 的 model_path。
 *
 * Android 实现 [GenieXModelResolver] 走 HubSource.LOCALFS 导入 SDK 缓存；
 * 单测可注入直接返回路径的 fake。
 */
fun interface ModelResolver {
    suspend fun resolve(modelSource: String): Result<String>
}

/** 已创建、可生成的一档引擎会话。 */
interface LlmSession {

    fun generate(request: LlmRequest): Flow<LlmStreamEvent>

    /** 停止 native 生成；必须幂等且可在取消后的上下文中调用。 */
    suspend fun cancel()

    /** 释放 native 资源；必须幂等。 */
    suspend fun close()
}

/**
 * 按 compute_unit 档位创建会话，是降级链的 create 级重试点：
 * 任一档返回 [Result.failure] 即由 [BackendFallbackManager] 尝试下一档。
 */
fun interface LlmSessionFactory {
    suspend fun create(modelPath: String, tier: ActiveBackend, config: LlmLoadConfig): Result<LlmSession>
}

/** 模型尚未加载即调用 generate。 */
class LlmNotLoadedException : IllegalStateException("模型尚未加载")

/** 同一会话并发发起生成（GenieX nSeqMax = 1，不支持多序列）。 */
class LlmGenerationBusyException : IllegalStateException("上一轮生成尚未结束")
