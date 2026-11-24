package com.thatgfsj.qaa.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.thatgfsj.qaa.core.harness.ContextWindow
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
 * 上下文长度 DataStore 持久化 JVM 单测（#106 / #112）：默认 32768、写入读回、非法值回退。
 * 与 ui_mode 的测试一致，直接实例化真实 preferences DataStore。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreContextLengthStoreTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun newDataStore() = PreferenceDataStoreFactory.create(
        produceFile = { tmpFolder.newFile("qaa_settings_${System.nanoTime()}.preferences_pb") },
        scope = CoroutineScope(UnconfinedTestDispatcher() + Job()),
    )

    @Test
    fun 无记录时_默认32768() = runTest {
        val store = DataStoreContextLengthStore(newDataStore())
        assertEquals(ContextWindow.DEFAULT, store.tokens.first())
    }

    @Test
    fun 写入后_可读取新值且支持覆盖写() = runTest {
        val store = DataStoreContextLengthStore(newDataStore())
        store.setTokens(8192)
        assertEquals(8192, store.tokens.first())
        store.setTokens(4096)
        assertEquals(4096, store.tokens.first())
    }

    @Test
    fun 非法存储值_回退默认32768() = runTest {
        val dataStore = newDataStore()
        dataStore.edit { it[CONTEXT_LENGTH_KEY] = 700 }
        val store = DataStoreContextLengthStore(dataStore)
        assertEquals(ContextWindow.DEFAULT, store.tokens.first())
    }

    @Test
    fun 自由数值_可写入并读回() = runTest {
        val store = DataStoreContextLengthStore(newDataStore())
        store.setTokens(3072)
        assertEquals(3072, store.tokens.first())
        store.setTokens(1048576)
        assertEquals(1048576, store.tokens.first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun 写入超出范围值_直接拒绝() = runTest {
        DataStoreContextLengthStore(newDataStore()).setTokens(700)
    }
}
