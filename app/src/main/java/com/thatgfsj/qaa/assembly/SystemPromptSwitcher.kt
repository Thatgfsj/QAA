package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.PromptAssembler
import com.thatgfsj.qaa.data.SystemPromptStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 系统提示词生效策略（#105）：终端 /system 与 Normal 设置页写同一 DataStore，
 * 装配层在每轮组装 / 查询 / 导出前调用 [sync] 取最新值，实现「保存后下一轮生效」，
 * 不需要重载引擎，也不打断进行中的轮次。
 *
 * 稳定前缀纪律：内容未变化时 [apply] 不触发回调，PromptAssembler 的系统段保持字节级一致，
 * 同一会话内的 NPU Prompt Cache 语义不受影响。
 */
internal class SystemPromptSwitcher(
    private val store: SystemPromptStore,
    private val onChanged: (String) -> Unit = {},
) {

    /** 当前生效的系统提示词（默认 [PromptAssembler.DEFAULT_SYSTEM_PROMPT]）。 */
    @Volatile
    var current: String = PromptAssembler.DEFAULT_SYSTEM_PROMPT
        private set

    /** 来源标记：内容与默认值一致时视为「默认」，否则「自定义」。 */
    val sourceLabel: String get() = systemPromptSource(current)

    /** 从 DataStore 同步最新值；读取失败保持内存旧值，设置链异常不阻断对话轮次。 */
    suspend fun sync() {
        val stored = try {
            store.prompt.first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        apply(stored)
    }

    /** /system 无参回显：来源 + 提示词全文 + 工具协议提示。 */
    suspend fun describe(): String {
        sync()
        return buildString {
            appendLine("系统提示词（来源：${sourceLabel}，${current.length} 字）")
            appendLine(current)
            append("提示：删除工具协议段落会导致 agent 模式工具调用失效；输入 /system reset 可恢复默认。")
        }
    }

    /**
     * 应用一次终端 /system 变更：null / 空白表示恢复默认（删除存储键），其余整体替换。
     * 返回可直接回显的中文回执；存储写入失败时保持内存值不变。
     */
    suspend fun applyChange(text: String?): String {
        val target = text?.trim().orEmpty()
        return if (target.isEmpty()) reset() else save(target)
    }

    private suspend fun reset(): String {
        if (!persist { store.reset() }) {
            return "恢复默认系统提示词失败：设置存储不可写；当前仍为${sourceLabel}提示词。"
        }
        apply(PromptAssembler.DEFAULT_SYSTEM_PROMPT)
        return "已恢复默认系统提示词；下一轮生效。"
    }

    private suspend fun save(text: String): String {
        if (!persist { store.setPrompt(text) }) {
            return "保存系统提示词失败：设置存储不可写。"
        }
        apply(text)
        return "已更新系统提示词（${text.length} 字，来源：${sourceLabel}）；下一轮生效。" +
            "提示：删除工具协议段落会导致 agent 模式工具调用失效，可输入 /system reset 恢复默认。"
    }

    private suspend fun persist(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        AppLog.error(ERROR_SYSTEM_PROMPT, error.message ?: error::class.simpleName.orEmpty())
        false
    }

    /** 写入内存值；仅在内容变化时通知装配层（稳定前缀 token 数随之重算）。 */
    private fun apply(next: String) {
        val normalized = next.trim().ifEmpty { PromptAssembler.DEFAULT_SYSTEM_PROMPT }
        if (normalized == current) return
        current = normalized
        onChanged(normalized)
        AppLog.systemPromptChanged(
            custom = !isDefaultSystemPrompt(normalized),
            length = normalized.length,
        )
    }

    private companion object {
        const val ERROR_SYSTEM_PROMPT = "E_SYSTEM_PROMPT"
    }
}

/** 来源标记（纯函数）：内容与默认值一致时视为默认，允许用户把提示词改回默认文本。 */
internal fun systemPromptSource(text: String): String =
    if (isDefaultSystemPrompt(text)) "默认" else "自定义"

internal fun isDefaultSystemPrompt(text: String): Boolean =
    text.trim() == PromptAssembler.DEFAULT_SYSTEM_PROMPT
