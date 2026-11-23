package com.thatgfsj.qaa.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.thatgfsj.qaa.UiMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ui_mode DataStore 持久化 JVM 单测（Issue #7）。
 * preferences-core 无 Android 依赖，可在 JVM 直接实例化真实 DataStore 验证读写链路。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreUiModeStoreTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun newDataStore() = PreferenceDataStoreFactory.create(
        produceFile = { tmpFolder.newFile("qaa_settings_${System.nanoTime()}.preferences_pb") },
        scope = CoroutineScope(UnconfinedTestDispatcher() + Job()),
    )

    @Test
    fun 无记录时_默认NORMAL() = runTest {
        val store = DataStoreUiModeStore(newDataStore())
        assertEquals(UiMode.NORMAL, store.mode.first())
    }

    @Test
    fun 写入后_可读取新值() = runTest {
        val store = DataStoreUiModeStore(newDataStore())
        store.setMode(UiMode.TERMINAL)
        assertEquals(UiMode.TERMINAL, store.mode.first())
        store.setMode(UiMode.NORMAL)
        assertEquals(UiMode.NORMAL, store.mode.first())
    }

    @Test
    fun 非法存储值_回退NORMAL() = runTest {
        val dataStore = newDataStore()
        dataStore.edit { it[UI_MODE_KEY] = 99 }
        val store = DataStoreUiModeStore(dataStore)
        assertEquals(UiMode.NORMAL, store.mode.first())
    }
}
