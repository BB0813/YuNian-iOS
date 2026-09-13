package com.yunian.ai.domain

import kotlinx.serialization.Serializable

@Serializable
data class Lorebook(
    val id: Long,
    val name: String,
    val description: String,
    val companionId: Long?,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
enum class InjectionPosition {
    BEFORE_SYSTEM_PROMPT,
    AFTER_SYSTEM_PROMPT,
    TOP_OF_CHAT,
    BOTTOM_OF_CHAT,
    AT_DEPTH
}

@Serializable
enum class EntryRole {
    SYSTEM,
    USER,
    ASSISTANT
}

@Serializable
data class LorebookEntry(
    val id: Long,
    val lorebookId: Long,
    val keywords: List<String>,
    val content: String,
    val injectionPosition: InjectionPosition,
    val priority: Int,
    val injectDepth: Int?,
    val role: EntryRole,
    val caseSensitive: Boolean,
    val useRegex: Boolean = false,
    val scanDepth: Int,
    val constantActive: Boolean,
    val enabled: Boolean,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class TriggeredEntry(
    val entry: LorebookEntry,
    val matchedKeyword: String,
    val matchIndex: Int,
)

interface LorebookProvider {

    suspend fun getEnabledEntriesForCompanion(companionId: Long): List<LorebookEntry>

    suspend fun getTriggeredEntries(
        companionId: Long,
        recentMessages: List<ContextMessage>
    ): List<TriggeredEntry>

    suspend fun getLorebookWithEntries(lorebookId: Long): LorebookWithEntries?

    /** 查询指定世界书的全部条目（含禁用条目，供编辑回显与条目计数） */
    suspend fun getEntries(lorebookId: Long): List<LorebookEntry>

    suspend fun createLorebook(lorebook: Lorebook, entries: List<LorebookEntry>): Long

    suspend fun updateLorebook(lorebook: Lorebook, entries: List<LorebookEntry>): Boolean

    suspend fun getAllLorebooks(): List<Lorebook>

    suspend fun deleteLorebook(lorebookId: Long): Boolean

    suspend fun upsertEntry(entry: LorebookEntry): Long

    suspend fun deleteEntry(entryId: Long): Boolean

    suspend fun setLorebookEnabled(lorebookId: Long, enabled: Boolean): Boolean

    suspend fun setEntryEnabled(entryId: Long, enabled: Boolean): Boolean

    /**
     * 查询某角色绑定的全局世界书 ID 集合（JSON 数组字符串，空数组 = 未做绑定选择）
     */
    suspend fun getBoundLorebookIds(companionId: Long): List<Long>

    /**
     * 保存某角色绑定的全局世界书 ID 集合（空列表 = 恢复旧行为：所有全局书自动生效）
     */
    suspend fun setBoundLorebookIds(companionId: Long, ids: List<Long>): Boolean
}

@Serializable
data class ContextMessage(
    val role: String,
    val content: String,
    val timestamp: Long
)

@Serializable
data class LorebookWithEntries(
    val lorebook: Lorebook,
    val entries: List<LorebookEntry>
)
