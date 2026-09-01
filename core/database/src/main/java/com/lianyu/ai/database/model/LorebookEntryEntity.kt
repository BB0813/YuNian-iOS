package com.lianyu.ai.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 世界书条目实体。
 *
 * 每个条目包含关键词列表和注入内容，支持多种注入位置和优先级。
 * 关键词匹配支持大小写敏感/不敏感、扫描深度、常驻激活等配置。
 */
@Entity(
    tableName = "lorebook_entries",
    indices = [
        Index(value = ["lorebookId"]),
        Index(value = ["enabled"]),
        Index(value = ["priority"]),
        Index(value = ["injectionPosition"])
    ]
)
@Serializable
data class LorebookEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // 所属世界书 ID
    val lorebookId: Long,

    // 关键词列表 JSON 数组字符串，如 ["关键词1", "关键词2"]
    val keywordsJson: String,

    // 注入内容
    val content: String,

    // 注入位置
    val injectionPosition: InjectionPosition = InjectionPosition.BEFORE_SYSTEM_PROMPT,

    // 优先级（越大越优先注入）
    @ColumnInfo(defaultValue = "0")
    val priority: Int = 0,

    // 注入深度（用于 AT_DEPTH 位置，从最新消息往上数）
    val injectDepth: Int? = null,

    // 消息角色（注入时伪装的角色）
    val role: EntryRole = EntryRole.SYSTEM,

    // 是否大小写敏感匹配
    @ColumnInfo(defaultValue = "0")
    val caseSensitive: Int = 0,

    // 扫描深度（向上检查最近 N 条消息，默认 10）
    @ColumnInfo(defaultValue = "10")
    val scanDepth: Int = 10,

    // 常驻激活（不依赖关键词，始终注入）
    @ColumnInfo(defaultValue = "0")
    val constantActive: Int = 0,

    @ColumnInfo(defaultValue = "1")
    val enabled: Int = 1,

    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = 0,

    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0,
) {
    fun isEnabled(): Boolean = enabled == 1
    fun isConstantActive(): Boolean = constantActive == 1
    fun isCaseSensitive(): Boolean = caseSensitive == 1
}

/** 注入位置枚举 */
@Serializable
enum class InjectionPosition {
    /** 系统提示词之前 */
    BEFORE_SYSTEM_PROMPT,
    /** 系统提示词之后 */
    AFTER_SYSTEM_PROMPT,
    /** 对话历史顶部（最旧消息上方） */
    TOP_OF_CHAT,
    /** 对话历史底部（最新消息下方） */
    BOTTOM_OF_CHAT,
    /** 指定深度（从最新消息往上数 injectDepth 条） */
    AT_DEPTH
}

/** 条目角色枚举 */
@Serializable
enum class EntryRole {
    SYSTEM,
    USER,
    ASSISTANT
}