package com.thatgfsj.qaa.feature.normal.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thatgfsj.qaa.feature.normal.model.ChatMessage
import com.thatgfsj.qaa.feature.normal.model.ChatRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 会话页 ViewModel（Issue #8，骨架期 fake 流式实现）。
 *
 * 依赖约束：本期严禁引入 :core-harness / :core-inference 真实逻辑；
 * 真实引擎流式接入属 Issue #6（LlmEngine）+ #5（降级链），
 * 届时仅需替换 [scriptedReplyTokens] 的 fake 来源，UI 契约不变。
 */
class ChatViewModel(
    private val tokenIntervalMs: Long = DEFAULT_TOKEN_INTERVAL_MS,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var nextId = 0L

    /**
     * 发送一条用户消息并触发 fake 流式回复（脚本化延迟逐 token 推送）。
     * 生成中或空白输入将被忽略。
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return
        _messages.update { it + ChatMessage(nextId++, ChatRole.USER, trimmed) }
        _isGenerating.value = true
        viewModelScope.launch {
            val replyId = nextId++
            _messages.update { it + ChatMessage(replyId, ChatRole.ASSISTANT, "") }
            scriptedReplyTokens(trimmed).forEach { token ->
                delay(tokenIntervalMs)
                _messages.update { list ->
                    list.map { msg ->
                        if (msg.id == replyId) msg.copy(content = msg.content + token) else msg
                    }
                }
            }
            _isGenerating.value = false
        }
    }

    /** fake 脚本化回复：按中文语感切 token，仅演示流式逐字渲染。 */
    internal fun scriptedReplyTokens(userText: String): List<String> = listOf(
        "已收到", "：", "「", userText, "」。",
        "这是", "骨架期", "的", "fake 流式回复，",
        "真实", "推理链路", "将在", "引擎接线", "完成后", "接入。",
    )

    companion object {
        const val DEFAULT_TOKEN_INTERVAL_MS = 120L
    }
}
