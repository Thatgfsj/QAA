package com.thatgfsj.qaa.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.thatgfsj.qaa.UiMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** ui_mode 持久化契约（Issue #7）：NORMAL / TERMINAL，切换后立即重启生效。 */
interface UiModeStore {
    val mode: Flow<UiMode>
    suspend fun setMode(mode: UiMode)
}

internal val UI_MODE_KEY = intPreferencesKey("ui_mode")

private val Context.qaaDataStore by preferencesDataStore(name = "qaa_settings")

/**
 * DataStore preferences 实现。
 * 缺失/未知存储值回退 NORMAL；写入后 [mode] 发射新值。
 */
class DataStoreUiModeStore(private val dataStore: DataStore<Preferences>) : UiModeStore {

    override val mode: Flow<UiMode> = dataStore.data
        .map { preferences -> UiMode.fromRaw(preferences[UI_MODE_KEY] ?: UiMode.NORMAL.raw) }

    override suspend fun setMode(mode: UiMode) {
        dataStore.edit { preferences -> preferences[UI_MODE_KEY] = mode.raw }
    }
}

fun dataStoreUiModeStore(context: Context): UiModeStore = DataStoreUiModeStore(context.qaaDataStore)
