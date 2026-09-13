package com.yunian.ai.feature.memory.engine

import kotlinx.serialization.Serializable

@Serializable
enum class MemoryCategory { FACT, EMOTION, PREFERENCE, EVENT, HABIT, RELATIONSHIP }

@Serializable
enum class MemorySource { CHAT, GROUP_CHAT, MANUAL }

@Serializable
enum class MemoryTier { SHORT, MID, LONG }

@Serializable
enum class MemoryScope { GLOBAL, COMPANION, GROUP }

@Serializable
data class MemoryItem(
    val id: String,
    val content: String,
    val category: MemoryCategory,
    val importance: Float,
    val timestamp: Long,
    val lastAccessed: Long,
    val accessCount: Int = 1,
    val source: MemorySource,
    val sourceId: Long,
    val scope: MemoryScope,
    val tags: List<String> = emptyList(),
    val expireAt: Long? = null,
    val tier: MemoryTier = MemoryTier.SHORT
) {

    fun isExpired(now: Long): Boolean {
        return expireAt != null && now > expireAt
    }

    fun shouldPromoteToLong(): Boolean {
        return importance >= 0.8f && accessCount >= 3
    }

    fun shouldDemoteFromMid(now: Long): Boolean {
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        return (now - lastAccessed) > sevenDays && importance < 0.5f
    }

    fun touch(now: Long): MemoryItem {
        return copy(lastAccessed = now, accessCount = accessCount + 1)
    }
}
