package com.yunian.ai.feature.memory.engine

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class MemoryStore(private val context: Context, private val deviceId: String) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val baseDir: File by lazy {
        File(context.filesDir, "memory/$deviceId").apply { mkdirs() }
    }

    private fun scopeDir(scope: MemoryScope, id: Long): File {
        val dirName = when (scope) {
            MemoryScope.GLOBAL -> "global"
            MemoryScope.COMPANION -> "companion_$id"
            MemoryScope.GROUP -> "group_$id"
        }
        return File(baseDir, dirName).apply { mkdirs() }
    }

    private fun tierFile(scope: MemoryScope, id: Long, tier: MemoryTier): File {
        return File(scopeDir(scope, id), "${tier.name.lowercase()}.json")
    }

    private fun indexFile(scope: MemoryScope, id: Long): File {
        return File(scopeDir(scope, id), "index.json")
    }

    fun loadTier(scope: MemoryScope, id: Long, tier: MemoryTier): List<MemoryItem> {
        val file = tierFile(scope, id, tier)
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<MemoryItem>>(file.readText())
        }.getOrElse {
            emptyList()
        }
    }

    fun saveTier(scope: MemoryScope, id: Long, tier: MemoryTier, items: List<MemoryItem>) {
        val file = tierFile(scope, id, tier)
        runCatching {
            file.writeText(json.encodeToString(items))
        }
    }

    fun loadIndex(scope: MemoryScope, id: Long): SerializedIndex? {
        val file = indexFile(scope, id)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<SerializedIndex>(file.readText())
        }.getOrNull()
    }

    fun saveIndex(scope: MemoryScope, id: Long, index: SerializedIndex) {
        val file = indexFile(scope, id)
        runCatching {
            file.writeText(json.encodeToString(index))
        }
    }

    fun deleteScope(scope: MemoryScope, id: Long) {
        scopeDir(scope, id).deleteRecursively()
    }

    fun exists(scope: MemoryScope, id: Long): Boolean {
        return scopeDir(scope, id).exists()
    }

    fun size(scope: MemoryScope, id: Long): Long {
        val dir = scopeDir(scope, id)
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
