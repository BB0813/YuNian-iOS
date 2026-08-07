package com.lianyu.ai.feature.automation.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 自动化数据 DataStore 唯一实例。
 * 文件名与其它模块错开，避免 "multiple DataStores active for the same file"。
 */
object AutomationDataStoreProvider {
    private const val NAME = "automation_store"

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = NAME)

    fun get(context: Context): DataStore<Preferences> = context.applicationContext.dataStore
}
