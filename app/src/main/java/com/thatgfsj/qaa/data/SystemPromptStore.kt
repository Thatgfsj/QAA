package com.thatgfsj.qaa.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thatgfsj.qaa.core.harness.PromptAssembler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 系统提示词持久化契约（#105）：终端 /system 与 Normal 设置页共用同一存储。
 *
 * 语义：存储缺失 / 空串 / 全空白一律回退 [PromptAssembler.DEFAULT_SYSTEM_PROMPT]，
 * 避免把空系统段发给模型导致工具协议段落丢失；自定义值写入时去除首尾空白。
 * 写入后由 AgentRuntime 在下一轮组装前同步，无需重载引擎。
 */
interface SystemPromptStore {

    /** 当前生效的系统提示词；未自定义或存储为空时回退默认值。 */
    val prompt: Flow<String>

    /** 保存自定义提示词；空白输入等价于 [reset]（删除存储键，保留默认语义）。 */
    suspend fun setPrompt(text: String)

    /** 恢复默认（删除存储键）。 */
    suspend fun reset()
}

internal val SYSTEM_PROMPT_KEY = stringPreferencesKey("system_prompt")

/**
 * DataStore preferences 实现。
 *
 * 默认值直接引用 core-harness 的 [PromptAssembler.DEFAULT_SYSTEM_PROMPT]，不在本层重复字面量：
 * 提示词模板的唯一来源仍是 PromptAssembler，避免两处漂移。
 */
class DataStoreSystemPromptStore(
    private val dataStore: DataStore<Preferences>,
) : SystemPromptStore {

    override val prompt: Flow<String> = dataStore.data.map { preferences ->
        preferences[SYSTEM_PROMPT_KEY]?.takeIf { it.isNotBlank() }
            ?: PromptAssembler.DEFAULT_SYSTEM_PROMPT
    }

    override suspend fun setPrompt(text: String) {
        val normalized = text.trim()
        dataStore.edit { preferences ->
            if (normalized.isEmpty()) {
                preferences.remove(SYSTEM_PROMPT_KEY)
            } else {
                preferences[SYSTEM_PROMPT_KEY] = normalized
            }
        }
    }

    override suspend fun reset() {
        dataStore.edit { preferences -> preferences.remove(SYSTEM_PROMPT_KEY) }
    }
}

fun dataStoreSystemPromptStore(context: Context): SystemPromptStore =
    DataStoreSystemPromptStore(context.qaaDataStore)
