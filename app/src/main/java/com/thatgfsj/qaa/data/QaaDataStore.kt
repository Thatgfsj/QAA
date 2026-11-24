package com.thatgfsj.qaa.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 应用设置共享 DataStore（#106）。
 *
 * 同一个文件名 qaa_settings 只允许存在一个 preferencesDataStore 委托：delegate 重复声明会在
 * 运行期抛 "There are multiple DataStores active for the same file"，因此 ui_mode 与
 * context_length 等设置统一从这里取，各 Store 实现只负责 key 与映射。
 */
internal val Context.qaaDataStore: DataStore<Preferences> by preferencesDataStore(name = "qaa_settings")
