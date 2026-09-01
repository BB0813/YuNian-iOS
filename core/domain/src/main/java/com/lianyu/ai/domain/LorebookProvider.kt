package com.lianyu.ai.domain

import kotlinx.serialization.Serializable

/**
 * 世界书/知识书领域模型。
 *
 * 对应数据库中的 LorebookEntity 和 LorebookEntryEntity，
 * 但属于 core:domain 零依赖模块，不引入 Room 实体。
 */

/** 世界书 */
@Serializable
data class Lorebook(
    val id: Long,
    val name: String,
    val description: String,
    val companionId: Long?,  // null = 全局
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/** 注入位置 */
@Serializable
enum class InjectionPosition {
    BEFORE_SYSTEM_PROMPT,  // 系统提示词之前
    AFTER_SYSTEM_PROMPT,   // 系统提示词之后
    TOP_OF_CHAT,           // 对话顶部（最旧消息上方）
    BOTTOM_OF_CHAT,        // 对话底部（最新消息下方）
    AT_DEPTH               // 指定深度（从最新往上 injectDepth 条）
}

/** 条目角色 */
@Serializable
enum class EntryRole {
    SYSTEM,
    USER,
    ASSISTANT
}

/** 世界书条目 */
@Serializable
data class LorebookEntry(
    val id: Long,
    val lorebookId: Long,
    val keywords: List<String>,
    val content: String,
    val injectionPosition: InjectionPosition,
    val priority: Int,
    val injectDepth: Int?,      // AT_DEPTH 时使用
    val role: EntryRole,
    val caseSensitive: Boolean,
    val scanDepth: Int,         // 向上扫描多少条消息
    val constantActive: Boolean, // 常驻激活（不依赖关键词）
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/** 命中触发的条目（含匹配信息） */
@Serializable
data class TriggeredEntry(
    val entry: LorebookEntry,
    val matchedKeyword: String,
    val matchIndex: Int,  // 在消息历史中的索引位置
)

/**
 * 世界书提供者接口。
 *
 * Feature 模块实现此接口，core:network 通过 ServiceRegistry 获取并调用。
 * 避免 feature -> feature 直接依赖。
 */
interface LorebookProvider {
    /**
     * 获取指定伴侣的所有启用世界书及其启用条目。
     * 用于注入检查时的批量查询。
     */
    suspend fun getEnabledEntriesForCompanion(companionId: Long): List<LorebookEntry>

    /**
     * 检查关键词触发：给定最近的对话上下文，返回命中的条目。
     *
     * @param companionId 伴侣 ID
     * @param recentMessages 最近的消息列表（最新在前），每项包含 role + content
     * @return 命中的条目列表，按 priority 降序、createdAt 升序
     */
    suspend fun getTriggeredEntries(
        companionId: Long,
        recentMessages: List<ContextMessage>
    ): List<TriggeredEntry>

    /** 获取单个世界书详情（含条目） */
    suspend fun getLorebookWithEntries(lorebookId: Long): LorebookWithEntries?

    /** 创建世界书 */
    suspend fun createLorebook(lorebook: Lorebook, entries: List<LorebookEntry>): Long

    /** 更新世界书 */
    suspend fun updateLorebook(lorebook: Lorebook, entries: List<LorebookEntry>): Boolean

    /** 获取所有世界书 */
    suspend fun getAllLorebooks(): List<Lorebook>

    /** 删除世界书 */
    suspend fun deleteLorebook(lorebookId: Long): Boolean

    /** 创建/更新单个条目 */
    suspend fun upsertEntry(entry: LorebookEntry): Long

    /** 删除条目 */
    suspend fun deleteEntry(entryId: Long): Boolean

    /** 切换世界书启用状态 */
    suspend fun setLorebookEnabled(lorebookId: Long, enabled: Boolean): Boolean

    /** 切换条目启用状态 */
    suspend fun setEntryEnabled(entryId: Long, enabled: Boolean): Boolean
}

/** 上下文消息（用于关键词匹配） */
@Serializable
data class ContextMessage(
    val role: String,      // "user" | "assistant" | "system"
    val content: String,
    val timestamp: Long
)

/** 世界书 + 条目聚合 */
@Serializable
data class LorebookWithEntries(
    val lorebook: Lorebook,
    val entries: List<LorebookEntry>
)