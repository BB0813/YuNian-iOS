package com.lianyu.ai.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 统一记忆记录 —— 现代化记忆系统的唯一真相源。
 *
 * 替代旧的 [MemoryEntry] + [TempMemory] + feature:memory 的 JSON 文件存储，
 * 所有记忆提取、检索、注入、管理都经过此表。
 *
 * 设计依据：[2026-07-09 现代化记忆系统设计方案]
 *
 * 关键字段说明：
 * - memoryType:      WORKING/EPISODIC/SEMANTIC/PREFERENCE/RELATIONSHIP/PROCEDURAL/FUZZY
 * - scope:           GLOBAL/COMPANION/GROUP/PRIVATE
 * - source:          CHAT/GROUP_CHAT/MANUAL/SYSTEM
 * - sourceId:        来源实体的 ID（companionId 或 groupId）
 * - observedAt:      记忆被观察/发现的时间（不同于 createdAt）
 * - validFrom/To:    记忆有效时间窗口（null = 永久有效）
 * - temporalAnchor:  时间锚点 JSON（MemoryTemporalAnchor 序列化）
 * - confidence:      置信度 [0.0, 1.0]
 * - mergedFrom:      被合并的源记忆 ID 列表（逗号分隔）
 * - isDeleted:       软删除标记（0=正常，1=已删除）
 * - version:         乐观锁版本号（用于冲突解决）
 */
@Entity(
    tableName = "unified_memories",
    indices = [
        Index(value = ["deviceId", "scope", "sourceId"]),
        Index(value = ["deviceId", "memoryType"]),
        Index(value = ["deviceId", "observedAt"]),
        Index(value = ["deviceId", "importance"]),
        Index(value = ["isDeleted"]),
        Index(value = ["expiresAt"])
    ]
)
data class MemoryRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // ── 类型与作用域 ──
    val memoryType: MemoryType = MemoryType.SEMANTIC,
    val scope: MemoryScope = MemoryScope.COMPANION,
    val source: MemorySource = MemorySource.CHAT,

    // ── 核心内容 ──
    val content: String,
    @ColumnInfo(defaultValue = "")
    val summary: String = "",

    // ── 权重 ──
    @ColumnInfo(defaultValue = "1.0")
    val confidence: Float = 1.0f,
    @ColumnInfo(defaultValue = "0.5")
    val importance: Float = 0.5f,

    // ── 来源追踪 ──
    @ColumnInfo(defaultValue = "0")
    val sourceId: Long = 0L,

    // ── 时间字段 ──
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val observedAt: Long = System.currentTimeMillis(),

    // ── 有效期 ──
    val expiresAt: Long? = null,
    val validFrom: Long? = null,
    val validTo: Long? = null,

    // ── 时间锚点 JSON (MemoryTemporalAnchor) ──
    @ColumnInfo(defaultValue = "")
    val temporalAnchor: String = "",

    // ── 访问统计 ──
    @ColumnInfo(defaultValue = "1")
    val accessCount: Int = 1,

    // ── 去重与融合 ──
    val tags: String = "",             // 逗号分隔的标签
    @ColumnInfo(defaultValue = "")
    val fuzzyHints: String = "",       // 逗号分隔的模糊提示词
    @ColumnInfo(defaultValue = "")
    val mergedFrom: String = "",       // 逗号分隔的被合并记忆 ID

    // ── 治理 ──
    @ColumnInfo(defaultValue = "0")
    val isDeleted: Int = 0,            // 软删除：0=正常，1=已删除
    @ColumnInfo(defaultValue = "1")
    val version: Int = 1,              // 乐观锁版本号

    // ── 用户隔离 ──
    @ColumnInfo(defaultValue = "''")
    val deviceId: String = "",

    // ── 语义向量（Phase 3: 本地语义检索） ──
    /** 文本嵌入向量（FloatArray 的 BLOB 序列化），null 表示尚未生成 */
    @ColumnInfo
    val embedding: ByteArray? = null,
    /** 生成 embedding 时使用的模型名称（如 "text-embedding-3-small"），用于版本兼容 */
    @ColumnInfo(defaultValue = "''")
    val embeddingModel: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryRecord) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
