package com.yunian.ai.feature.memory.engine

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SerializedIndex(
    val keywordToIds: Map<String, List<String>>,
    val categoryToIds: Map<String, List<String>>,
    val importanceSorted: List<String>,
    val timeSorted: List<String>
)

class MemoryIndex {
    private val keywordToIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val categoryToIds = ConcurrentHashMap<MemoryCategory, MutableSet<String>>()
    private val importanceSorted = ConcurrentHashMap<String, Float>()
    private val timeSorted = ConcurrentHashMap<String, Long>()

    fun add(item: MemoryItem) {
        val tokens = MemoryTokenizer.tokenize(item.content)
        tokens.forEach { token ->
            keywordToIds.getOrPut(token) { ConcurrentHashMap.newKeySet() }.add(item.id)
        }
        categoryToIds.getOrPut(item.category) { ConcurrentHashMap.newKeySet() }.add(item.id)
        importanceSorted[item.id] = item.importance
        timeSorted[item.id] = item.timestamp
        item.tags.forEach { tag ->
            keywordToIds.getOrPut(tag) { ConcurrentHashMap.newKeySet() }.add(item.id)
        }
    }

    fun remove(id: String) {
        keywordToIds.values.forEach { it.remove(id) }
        categoryToIds.values.forEach { it.remove(id) }
        importanceSorted.remove(id)
        timeSorted.remove(id)
        keywordToIds.entries.removeAll { it.value.isEmpty() }
    }

    fun touch(id: String, importanceBoost: Float = 0.01f) {
        importanceSorted.computeIfPresent(id) { _, v -> (v + importanceBoost).coerceAtMost(1.0f) }
    }

    fun search(query: String, category: MemoryCategory? = null, limit: Int = 5): List<String> {
        val tokens = MemoryTokenizer.tokenize(query)
        if (tokens.isEmpty()) {

            var result = importanceSorted.entries
                .sortedByDescending { it.value }
                .map { it.key }
            if (category != null) {
                val catIds = categoryToIds[category] ?: emptySet()
                result = result.filter { it in catIds }
            }
            return result.take(limit)
        }

        val candidateScores = ConcurrentHashMap<String, Int>()
        tokens.forEach { token ->
            keywordToIds[token]?.forEach { id ->
                candidateScores.compute(id) { _, v -> (v ?: 0) + 1 }
            }
        }

        var result = candidateScores.entries
            .sortedByDescending { it.value }
            .map { it.key }

        if (category != null) {
            val catIds = categoryToIds[category] ?: emptySet()
            result = result.filter { it in catIds }
        }

        return result.take(limit)
    }

    fun allIds(): Set<String> {
        return importanceSorted.keys.toSet()
    }

    fun size(): Int = importanceSorted.size

    fun serialize(): SerializedIndex {
        return SerializedIndex(
            keywordToIds = keywordToIds.mapValues { it.value.toList() },
            categoryToIds = categoryToIds.mapKeys { it.key.name }.mapValues { it.value.toList() },
            importanceSorted = importanceSorted.entries.sortedByDescending { it.value }.map { it.key },
            timeSorted = timeSorted.entries.sortedByDescending { it.value }.map { it.key }
        )
    }

    fun deserialize(data: SerializedIndex, items: Map<String, MemoryItem>) {
        clear()
        data.keywordToIds.forEach { (k, v) ->
            keywordToIds[k] = ConcurrentHashMap.newKeySet<String>().apply { addAll(v) }
        }
        data.categoryToIds.forEach { (k, v) ->
            val cat = MemoryCategory.valueOf(k)
            categoryToIds[cat] = ConcurrentHashMap.newKeySet<String>().apply { addAll(v) }
        }

        items.forEach { (id, item) ->
            importanceSorted[id] = item.importance
            timeSorted[id] = item.timestamp
        }
    }

    fun clear() {
        keywordToIds.clear()
        categoryToIds.clear()
        importanceSorted.clear()
        timeSorted.clear()
    }
}
