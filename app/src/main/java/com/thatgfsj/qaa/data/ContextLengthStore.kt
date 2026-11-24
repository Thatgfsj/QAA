package com.thatgfsj.qaa.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.thatgfsj.qaa.core.harness.ContextWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 上下文长度持久化契约（#106 / #112）：任意整数 1024..1048576，默认 32768。
 *
 * 终端 /ctx 与 Normal 设置页共用同一存储；写入后由 AgentRuntime 在下一次载入模型时读取。
 */
interface ContextLengthStore {

    /** 当前设置值；缺失或非法存储值回退 [ContextWindow.DEFAULT]。 */
    val tokens: Flow<Int>

    /** 写入设置值；只接受 ${ContextWindow.MIN_N_CTX}..${ContextWindow.MAX_N_CTX}。 */
    suspend fun setTokens(tokens: Int)
}

internal val CONTEXT_LENGTH_KEY = intPreferencesKey("context_length")

/**
 * DataStore preferences 实现。
 *
 * 非法存储值（历史版本 / 人工改文件）回退 [ContextWindow.DEFAULT]，避免把越界 nCtx
 * 直接喂给引擎；写入侧同样拒绝非法值。
 */
class DataStoreContextLengthStore(
    private val dataStore: DataStore<Preferences>,
) : ContextLengthStore {

    override val tokens: Flow<Int> = dataStore.data.map { preferences ->
        val stored = preferences[CONTEXT_LENGTH_KEY]
        if (stored != null && ContextWindow.isAllowed(stored)) stored else ContextWindow.DEFAULT
    }

    override suspend fun setTokens(tokens: Int) {
        require(ContextWindow.isAllowed(tokens)) {
            "上下文长度必须在 ${ContextWindow.MIN_N_CTX}~${ContextWindow.MAX_N_CTX} 之间：$tokens"
        }
        dataStore.edit { preferences -> preferences[CONTEXT_LENGTH_KEY] = tokens }
    }
}

fun dataStoreContextLengthStore(context: Context): ContextLengthStore =
    DataStoreContextLengthStore(context.qaaDataStore)
