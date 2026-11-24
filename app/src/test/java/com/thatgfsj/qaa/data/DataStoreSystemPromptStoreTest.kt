package com.thatgfsj.qaa.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.thatgfsj.qaa.core.harness.PromptAssembler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 系统提示词 DataStore 持久化 JVM 单测（#105）：默认回退、自定义读写、空白归一与 reset。
 * 直接实例化真实 preferences DataStore，与 ui_mode / 上下文长度测试同构。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSystemPromptStoreTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun newDataStore() = PreferenceDataStoreFactory.create(
        produceFile = { tmpFolder.newFile("qaa_settings_${System.nanoTime()}.preferences_pb") },
        scope = CoroutineScope(UnconfinedTestDispatcher() + Job()),
    )

    @Test
    fun 无记录时_返回默认提示词() = runTest {
        val store = DataStoreSystemPromptStore(newDataStore())

        assertEquals(PromptAssembler.DEFAULT_SYSTEM_PROMPT, store.prompt.first())
    }

    @Test
    fun 写入自定义后_读回并去除首尾空白() = runTest {
        val store = DataStoreSystemPromptStore(newDataStore())

        store.setPrompt("  你是自定义助手。  ")

        assertEquals("你是自定义助手。", store.prompt.first())
    }

    @Test
    fun 存储为空白串_回退默认() = runTest {
        val dataStore = newDataStore()
        dataStore.edit { it[SYSTEM_PROMPT_KEY] = "   " }
        val store = DataStoreSystemPromptStore(dataStore)

        assertEquals(PromptAssembler.DEFAULT_SYSTEM_PROMPT, store.prompt.first())
    }

    @Test
    fun reset后_回退默认且键被删除() = runTest {
        val dataStore = newDataStore()
        val store = DataStoreSystemPromptStore(dataStore)
        store.setPrompt("自定义提示词")

        store.reset()

        assertEquals(PromptAssembler.DEFAULT_SYSTEM_PROMPT, store.prompt.first())
        assertNull(dataStore.data.first()[SYSTEM_PROMPT_KEY])
    }

    @Test
    fun 写入空白_等价reset() = runTest {
        val store = DataStoreSystemPromptStore(newDataStore())
        store.setPrompt("自定义提示词")

        store.setPrompt("   ")

        assertEquals(PromptAssembler.DEFAULT_SYSTEM_PROMPT, store.prompt.first())
    }
}
