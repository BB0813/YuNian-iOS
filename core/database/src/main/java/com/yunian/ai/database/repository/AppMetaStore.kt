package com.yunian.ai.database.repository

import com.yunian.ai.database.dao.AppMetaDao
import com.yunian.ai.database.model.AppMetaEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class AppMetaStore(private val dao: AppMetaDao) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun getString(key: String): String? = dao.get(key)

    suspend fun putString(key: String, value: String) {
        dao.put(AppMetaEntity(key = key, value = value))
    }

    suspend fun remove(key: String) = dao.remove(key)

    suspend fun contains(key: String): Boolean = dao.get(key) != null

    suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        val raw = dao.get(key) ?: return null
        return runCatching { json.decodeFromString(serializer, raw) }.getOrNull()
    }

    suspend fun <T> put(key: String, value: T, serializer: KSerializer<T>) {
        dao.put(AppMetaEntity(key = key, value = json.encodeToString(serializer, value)))
    }

    suspend fun <T> getOrPut(
        key: String,
        serializer: KSerializer<T>,
        default: suspend () -> T
    ): T {
        return get(key, serializer) ?: default().also { put(key, it, serializer) }
    }
}
