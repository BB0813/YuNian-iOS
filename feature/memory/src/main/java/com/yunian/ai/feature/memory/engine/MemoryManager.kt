package com.yunian.ai.feature.memory.engine

import android.content.Context
import android.util.Log
import com.yunian.ai.common.DeviceIdProvider
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.domain.MemoryProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Deprecated("使用 UnifiedMemoryProvider 替代，新系统基于 Room unified_memories 表", ReplaceWith("UnifiedMemoryProvider"))
class MemoryManager private constructor(
    private val context: Context,
    private val deviceId: String
) : MemoryProvider {
    companion object {
        private const val TAG = "MemoryManager"
        private const val SHORT_TERM_TTL_MS = 5L * 60 * 1000
        private const val SHORT_TERM_MAX_PER_SCOPE = 50
        private const val MID_TERM_MAX_MEMORY = 500
        private const val CLEANUP_INTERVAL_MS = 5L * 60 * 1000
        private const val SYNC_IMPORTANCE_THRESHOLD = 0.7f
        private const val DEDUP_SIMILARITY_THRESHOLD = 0.6f

        @Volatile
        private var instance: MemoryManager? = null

        fun getInstance(context: Context): MemoryManager {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val deviceId = DeviceIdProvider.getDeviceId(context)
                    MemoryManager(context.applicationContext, deviceId).also {
                        instance = it
                    }
                }
            }
        }
    }

    private val store = MemoryStore(context, deviceId)
    private val ioScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)

    private val shortTermCache = ConcurrentHashMap<String, MutableList<MemoryItem>>()

    private val midTermCache = ConcurrentHashMap<String, MutableList<MemoryItem>>()

    private val longTermIndex = ConcurrentHashMap<String, MemoryIndex>()

    private val indexCache = ConcurrentHashMap<String, MemoryIndex>()

    private val queryCache = LinkedHashMap<String, List<MemoryItem>>(32, 0.75f, true)

    private val writeLocks = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var initialized = false

    private val initLock = Any()

    override fun initialize() {

        synchronized(initLock) {
            if (initialized) return
            initialized = true
        }
        ioScope.launch {
            runCatching {
                loadPersistedMemories()
                startCleanupTask()
            }.onFailure { Log.e(TAG, "初始化失败", it) }
        }
    }

    private fun scopeKey(scope: MemoryScope, id: Long): String {
        return "${scope.name}_$id"
    }

    private fun getLock(scope: MemoryScope, id: Long): Mutex {
        return writeLocks.computeIfAbsent(scopeKey(scope, id)) { Mutex() }
    }

    private fun loadPersistedMemories() {

        loadScopeFromDisk(MemoryScope.GLOBAL, 0L)

        val globalDir = java.io.File(context.filesDir, "memory/$deviceId")
        globalDir.listFiles()?.forEach { dir ->
            if (dir.name.startsWith("companion_")) {
                val id = dir.name.removePrefix("companion_").toLongOrNull()
                if (id != null) loadScopeFromDisk(MemoryScope.COMPANION, id)
            } else if (dir.name.startsWith("group_")) {
                val id = dir.name.removePrefix("group_").toLongOrNull()
                if (id != null) loadScopeFromDisk(MemoryScope.GROUP, id)
            }
        }
    }

    private fun loadScopeFromDisk(scope: MemoryScope, id: Long) {
        val key = scopeKey(scope, id)
        runCatching {

            val midItems = store.loadTier(scope, id, MemoryTier.MID)
            if (midItems.isNotEmpty()) {
                midTermCache[key] = midItems.toMutableList()
            }

            val longItems = store.loadTier(scope, id, MemoryTier.LONG)
            if (longItems.isNotEmpty()) {
                val index = MemoryIndex()
                longItems.forEach { index.add(it) }
                longTermIndex[key] = index

                midTermCache[key]?.addAll(0, longItems)
            }

            store.loadIndex(scope, id)?.let { serialized ->
                val index = MemoryIndex()
                val allItems = (longItems + midItems).associateBy { it.id }
                index.deserialize(serialized, allItems)
                indexCache[key] = index
            }
        }.onFailure { Log.e(TAG, "加载作用域 $key 失败", it) }
    }

    private fun startCleanupTask() {
        ioScope.launch {
            while (true) {
                delay(CLEANUP_INTERVAL_MS)
                runCatching { cleanupExpiredMemories() }
                    .onFailure { Log.e(TAG, "清理任务失败", it) }
            }
        }
    }

    private fun cleanupExpiredMemories() {
        val now = System.currentTimeMillis()
        shortTermCache.forEach { (key, items) ->
            synchronized(items) {
                items.removeAll { it.isExpired(now) }

                if (items.size > SHORT_TERM_MAX_PER_SCOPE) {
                    items.sortBy { it.lastAccessed }
                    val toRemove = items.size - SHORT_TERM_MAX_PER_SCOPE
                    repeat(toRemove) { items.removeAt(0) }
                }
            }
        }

        val midSnapshot = midTermCache.entries.toList()
        val totalMid = midSnapshot.sumOf { it.value.size }
        if (totalMid > MID_TERM_MAX_MEMORY) {

            val allMid = midSnapshot
                .flatMap { (key, items) ->
                    synchronized(items) { items.toList() }.map { key to it }
                }
                .sortedBy { it.second.lastAccessed }
            val toRemoveCount = totalMid - MID_TERM_MAX_MEMORY
            allMid.take(toRemoveCount).forEach { (key, item) ->
                midTermCache[key]?.let { items ->
                    synchronized(items) { items.removeAll { it.id == item.id } }
                }
            }
        }
    }

    override suspend fun getMemoryContext(
        companionId: Long?,
        groupId: Long?,
        query: String,
        limit: Int
    ): String {
        return runCatching {
            val memories = mutableListOf<MemoryItem>()

            memories.addAll(searchMemories(MemoryScope.GLOBAL, 0L, query, limit))

            when {
                companionId != null -> {
                    memories.addAll(searchMemories(MemoryScope.COMPANION, companionId, query, limit))
                }
                groupId != null -> {
                    memories.addAll(searchMemories(MemoryScope.GROUP, groupId, query, limit))
                }
            }

            val deduped = memories.distinctBy { it.id }
                .sortedByDescending { it.importance }
                .take(limit)

            deduped.forEach { touchMemory(it) }

            formatMemoryContext(deduped)
        }.onFailure { Log.e(TAG, "获取记忆上下文失败", it) }
            .getOrElse { "" }
    }

    private fun searchMemories(
        scope: MemoryScope,
        id: Long,
        query: String,
        limit: Int
    ): List<MemoryItem> {
        val key = scopeKey(scope, id)
        val result = mutableListOf<MemoryItem>()
        val resultIds = mutableSetOf<String>()

        val cacheKey = "$key:$query:$limit"
        synchronized(queryCache) {
            queryCache[cacheKey]?.let { return it }
        }

        shortTermCache[key]?.let { items ->
            synchronized(items) {
                val matched = MemoryIndex().apply {
                    items.forEach { add(it) }
                }.search(query, limit = limit)
                items.filter { it.id in matched }.forEach {
                    if (it.id !in resultIds) {
                        result.add(it)
                        resultIds.add(it.id)
                    }
                }
            }
        }

        val index = indexCache[key] ?: MemoryIndex()
        if (result.size < limit) {
            val matchedIds = index.search(query, limit = limit - result.size)

            midTermCache[key]?.let { cache ->
                cache.filter { it.id in matchedIds }.forEach {
                    if (it.id !in resultIds) {
                        result.add(it)
                        resultIds.add(it.id)
                    }
                }
            }

            if (result.size < limit) {
                val stillNeeded = matchedIds.filter { it !in resultIds }
                if (stillNeeded.isNotEmpty()) {
                    val longItems = store.loadTier(scope, id, MemoryTier.LONG)
                    longItems.filter { it.id in stillNeeded }.forEach {
                        if (it.id !in resultIds) {
                            result.add(it)
                            resultIds.add(it.id)
                        }
                    }
                }
            }
        }

        synchronized(queryCache) {
            if (queryCache.size > 32) {
                queryCache.remove(queryCache.keys.first())
            }
            queryCache[cacheKey] = result.toList()
        }

        return result
    }

    private fun formatMemoryContext(memories: List<MemoryItem>): String {
        if (memories.isEmpty()) return ""
        return buildString {
            append("\n=== 关于用户的记忆 ===\n")
            memories.forEach { item ->
                val categoryLabel = when (item.category) {
                    MemoryCategory.FACT -> "事实"
                    MemoryCategory.EMOTION -> "情感"
                    MemoryCategory.PREFERENCE -> "偏好"
                    MemoryCategory.EVENT -> "事件"
                    MemoryCategory.HABIT -> "习惯"
                    MemoryCategory.RELATIONSHIP -> "关系"
                }
                append("【$categoryLabel】${item.content}\n")
            }
        }
    }

    private fun touchMemory(item: MemoryItem) {
        val now = System.currentTimeMillis()
        val touched = item.touch(now)

        val key = scopeKey(item.scope, item.sourceId)
        shortTermCache[key]?.let { items ->
            synchronized(items) {
                val idx = items.indexOfFirst { it.id == item.id }
                if (idx >= 0) items[idx] = touched
            }
        }

        midTermCache[key]?.let { items ->
            synchronized(items) {
                val idx = items.indexOfFirst { it.id == item.id }
                if (idx >= 0) items[idx] = touched
            }
        }

        indexCache[key]?.touch(item.id)
    }

    suspend fun saveMemory(
        content: String,
        category: MemoryCategory,
        importance: Float,
        source: MemorySource,
        sourceId: Long,
        scope: MemoryScope
    ): String? {
        val key = scopeKey(scope, sourceId)
        return getLock(scope, sourceId).withLock {
            runCatching {

                val existing = findSimilar(key, content)
                if (existing != null) {

                    val merged = existing.copy(
                        importance = maxOf(existing.importance, importance),
                        accessCount = existing.accessCount + 1,
                        lastAccessed = System.currentTimeMillis()
                    )
                    updateMemoryInternal(scope, sourceId, merged)
                    return@runCatching existing.id
                }

                val now = System.currentTimeMillis()
                val item = MemoryItem(
                    id = UUID.randomUUID().toString(),
                    content = content,
                    category = category,
                    importance = importance.coerceIn(0f, 1f),
                    timestamp = now,
                    lastAccessed = now,
                    source = source,
                    sourceId = sourceId,
                    scope = scope,
                    tags = MemoryTokenizer.extractKeywords(content),
                    expireAt = if (scope == MemoryScope.GLOBAL) null else now + SHORT_TERM_TTL_MS,
                    tier = MemoryTier.SHORT
                )

                shortTermCache.computeIfAbsent(key) { mutableListOf() }
                    .let { items ->
                        synchronized(items) {
                            items.add(item)

                            if (items.size > SHORT_TERM_MAX_PER_SCOPE) {
                                val toPromote = items.removeAt(0)
                                promoteToMid(scope, sourceId, toPromote)
                            }
                        }
                    }

                indexCache.computeIfAbsent(key) { MemoryIndex() }.add(item)

                if (scope != MemoryScope.GLOBAL && shouldSyncToGlobal(category, importance)) {
                    syncToGlobal(item)
                }

                schedulePersist(scope, sourceId)

                invalidateQueryCache()

                item.id
            }.onFailure { Log.e(TAG, "保存记忆失败", it) }
                .getOrNull()
        }
    }

    private fun invalidateQueryCache() {
        synchronized(queryCache) {
            queryCache.clear()
        }
    }

    private fun findSimilar(key: String, content: String): MemoryItem? {
        val allItems = mutableListOf<MemoryItem>()
        shortTermCache[key]?.let { allItems.addAll(it) }
        midTermCache[key]?.let { allItems.addAll(it) }

        return allItems.firstOrNull { existing ->
            MemoryTokenizer.similarity(existing.content, content) > DEDUP_SIMILARITY_THRESHOLD
        }
    }

    private fun shouldSyncToGlobal(category: MemoryCategory, importance: Float): Boolean {
        if (importance < SYNC_IMPORTANCE_THRESHOLD) return false

        return category !in setOf(MemoryCategory.EMOTION, MemoryCategory.EVENT)
    }

    private suspend fun syncToGlobal(item: MemoryItem) {
        val globalItem = item.copy(
            id = UUID.randomUUID().toString(),
            scope = MemoryScope.GLOBAL,
            sourceId = 0L,
            tier = MemoryTier.MID,
            expireAt = null
        )

        val globalKey = scopeKey(MemoryScope.GLOBAL, 0L)
        shortTermCache.computeIfAbsent(globalKey) { mutableListOf() }
            .let { items ->
                synchronized(items) { items.add(globalItem) }
            }
        indexCache.computeIfAbsent(globalKey) { MemoryIndex() }.add(globalItem)
        schedulePersist(MemoryScope.GLOBAL, 0L)
    }

    private fun promoteToMid(scope: MemoryScope, id: Long, item: MemoryItem) {
        val key = scopeKey(scope, id)
        val promoted = item.copy(
            tier = MemoryTier.MID,
            expireAt = null
        )
        midTermCache.computeIfAbsent(key) { mutableListOf() }
            .let { items ->
                synchronized(items) { items.add(promoted) }
            }
        schedulePersist(scope, id)
    }

    private suspend fun promoteToLong(scope: MemoryScope, id: Long, item: MemoryItem) {
        val key = scopeKey(scope, id)
        val promoted = item.copy(tier = MemoryTier.LONG)

        midTermCache[key]?.let { items ->
            synchronized(items) { items.removeAll { it.id == item.id } }
        }

        val longItems = store.loadTier(scope, id, MemoryTier.LONG).toMutableList()
        longItems.add(promoted)
        store.saveTier(scope, id, MemoryTier.LONG, longItems)

        longTermIndex.computeIfAbsent(key) { MemoryIndex() }.add(promoted)
        indexCache[key]?.add(promoted)

        schedulePersist(scope, id)
    }

    private fun updateMemoryInternal(scope: MemoryScope, sourceId: Long, item: MemoryItem) {
        val key = scopeKey(scope, sourceId)

        shortTermCache[key]?.let { items ->
            synchronized(items) {
                val idx = items.indexOfFirst { it.id == item.id }
                if (idx >= 0) items[idx] = item
            }
        }

        midTermCache[key]?.let { items ->
            synchronized(items) {
                val idx = items.indexOfFirst { it.id == item.id }
                if (idx >= 0) items[idx] = item
            }
        }

        if (item.shouldPromoteToLong() && item.tier != MemoryTier.LONG) {
            ioScope.launch { promoteToLong(scope, sourceId, item) }
        }

        schedulePersist(scope, sourceId)

        invalidateQueryCache()
    }

    private fun schedulePersist(scope: MemoryScope, id: Long) {
        val key = scopeKey(scope, id)
        ioScope.launch {
            runCatching {
                val shortItems = shortTermCache[key]?.toList() ?: emptyList()
                val midItems = midTermCache[key]?.toList() ?: emptyList()

                store.saveTier(scope, id, MemoryTier.SHORT, shortItems)
                store.saveTier(scope, id, MemoryTier.MID, midItems)

                indexCache[key]?.let { index ->
                    store.saveIndex(scope, id, index.serialize())
                }
            }.onFailure { Log.e(TAG, "持久化失败 scope=$key", it) }
        }
    }

    suspend fun getMemories(scope: MemoryScope, id: Long): List<MemoryItem> {
        val key = scopeKey(scope, id)
        val result = mutableListOf<MemoryItem>()

        shortTermCache[key]?.let { result.addAll(it) }
        midTermCache[key]?.let { result.addAll(it) }

        result.addAll(store.loadTier(scope, id, MemoryTier.LONG))

        return result.sortedByDescending { it.timestamp }
    }

    suspend fun deleteMemory(id: String) {

        listOf(MemoryScope.GLOBAL to 0L).forEach { (scope, sid) ->
            deleteMemoryFromScope(scope, sid, id)
        }
    }

    private suspend fun deleteMemoryFromScope(scope: MemoryScope, sourceId: Long, id: String) {
        val key = scopeKey(scope, sourceId)
        var deleted = false

        shortTermCache[key]?.let { items ->
            synchronized(items) { deleted = items.removeAll { it.id == id } || deleted }
        }
        midTermCache[key]?.let { items ->
            synchronized(items) { deleted = items.removeAll { it.id == id } || deleted }
        }
        indexCache[key]?.remove(id)

        if (deleted) {
            schedulePersist(scope, sourceId)

            invalidateQueryCache()
        }

        val longItems = store.loadTier(scope, sourceId, MemoryTier.LONG).toMutableList()
        if (longItems.removeAll { it.id == id }) {
            store.saveTier(scope, sourceId, MemoryTier.LONG, longItems)
            longTermIndex[key]?.remove(id)
        }
    }

    suspend fun deleteAllMemories(scope: MemoryScope, id: Long) {
        val key = scopeKey(scope, id)
        shortTermCache.remove(key)
        midTermCache.remove(key)
        indexCache.remove(key)
        longTermIndex.remove(key)
        store.deleteScope(scope, id)
    }

    override suspend fun extractAndSaveFromConversation(
        userInput: String,
        aiResponse: String,
        companionId: Long,
        groupId: Long?
    ) {
        runCatching {
            val scope = if (groupId != null) MemoryScope.GROUP else MemoryScope.COMPANION
            val sourceId = if (groupId != null) groupId else companionId
            val source = if (groupId != null) MemorySource.GROUP_CHAT else MemorySource.CHAT

            val extracted = extractMemories(userInput)
            extracted.forEach { (content, category, importance) ->
                saveMemory(content, category, importance, source, sourceId, scope)
            }
        }.onFailure { Log.e(TAG, "提取记忆失败", it) }
    }

    private fun extractMemories(text: String): List<Triple<String, MemoryCategory, Float>> {
        val result = mutableListOf<Triple<String, MemoryCategory, Float>>()

        val factPatterns = listOf("我叫", "我是", "我来自", "我在", "我的名字", "我住", "我工作", "我学", "我的职业")
        factPatterns.forEach { pattern ->
            if (text.contains(pattern)) {
                extractAfterPattern(text, pattern)?.let {
                    result.add(Triple(it, MemoryCategory.FACT, 0.8f))
                }
            }
        }

        val preferencePatterns = listOf("我喜欢", "我爱好", "我偏爱", "我讨厌", "我不喜欢", "我反感", "我爱", "我恨")
        preferencePatterns.forEach { pattern ->
            if (text.contains(pattern)) {
                extractAfterPattern(text, pattern)?.let {
                    result.add(Triple(it, MemoryCategory.PREFERENCE, 0.75f))
                }
            }
        }

        val habitPatterns = listOf("我每天", "我经常", "我总是", "我通常", "我习惯", "我一般")
        habitPatterns.forEach { pattern ->
            if (text.contains(pattern)) {
                extractAfterPattern(text, pattern)?.let {
                    result.add(Triple(it, MemoryCategory.HABIT, 0.7f))
                }
            }
        }

        val relationshipPatterns = listOf("我的朋友", "我的家人", "我的父母", "我的同学", "我的同事", "我的男朋友", "我的女朋友")
        relationshipPatterns.forEach { pattern ->
            if (text.contains(pattern)) {
                extractAfterPattern(text, pattern)?.let {
                    result.add(Triple(it, MemoryCategory.RELATIONSHIP, 0.8f))
                }
            }
        }

        return result.distinctBy { it.first }
    }

    private fun extractAfterPattern(text: String, pattern: String): String? {
        val idx = text.indexOf(pattern)
        if (idx < 0) return null
        val start = idx + pattern.length
        val endText = text.substring(start)
        val endIdx = endText.indexOfFirst { it in "。，！？；\n" }
        val content = if (endIdx > 0) endText.substring(0, endIdx) else endText.take(50)
        val full = "$pattern$content".trim()
        return if (full.length > pattern.length + 1) full else null
    }
}
