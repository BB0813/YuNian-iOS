package com.lianyu.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lianyu.ai.database.model.MemoryRecord
import com.lianyu.ai.database.model.MemoryScope
import com.lianyu.ai.database.model.MemorySource
import com.lianyu.ai.database.model.MemoryType
import kotlinx.coroutines.flow.Flow

/**
 * 统一记忆 DAO —— 现代化记忆系统的唯一数据访问层。
 *
 * 查询原则：
 * 1. 所有查询默认排除已软删除记录（isDeleted = 0）
 * 2. 所有查询默认排除已过期记录（expiresAt IS NULL OR expiresAt > now）
 * 3. 按 importance DESC, observedAt DESC 排序
 * 4. 支持分 scope、type、sourceId、时间范围等筛选
 */
@Dao
interface UnifiedMemoryDao {

    // ═══════════════════════════════════════════════════════════
    // 写入
    // ═══════════════════════════════════════════════════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: MemoryRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<MemoryRecord>): List<Long>

    // ═══════════════════════════════════════════════════════════
    // 基础查询（排除已删除 + 已过期）
    // ═══════════════════════════════════════════════════════════

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY importance DESC, observedAt DESC
    """)
    fun getAllActive(deviceId: String, now: Long = System.currentTimeMillis()): Flow<List<MemoryRecord>>

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND scope = :scope
          AND sourceId = :sourceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY importance DESC, observedAt DESC
    """)
    fun getByScope(
        deviceId: String,
        scope: MemoryScope,
        sourceId: Long,
        now: Long = System.currentTimeMillis()
    ): Flow<List<MemoryRecord>>

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND memoryType = :type
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY importance DESC, observedAt DESC
    """)
    fun getByType(
        deviceId: String,
        type: MemoryType,
        now: Long = System.currentTimeMillis()
    ): Flow<List<MemoryRecord>>

    // ═══════════════════════════════════════════════════════════
    // 同步查询（用于非 Flow 场景）
    // ═══════════════════════════════════════════════════════════

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND scope = :scope
          AND sourceId = :sourceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY importance DESC, observedAt DESC
    """)
    suspend fun getByScopeSync(
        deviceId: String,
        scope: MemoryScope,
        sourceId: Long,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY importance DESC, observedAt DESC
    """)
    suspend fun getAllActiveSync(
        deviceId: String,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    // ═══════════════════════════════════════════════════════════
    // 关键词搜索
    // ═══════════════════════════════════════════════════════════

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
          AND (content LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%')
        ORDER BY importance DESC, observedAt DESC
        LIMIT :limit
    """)
    suspend fun search(
        deviceId: String,
        query: String,
        limit: Int = 10,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND scope = :scope
          AND sourceId = :sourceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
          AND (content LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%')
        ORDER BY importance DESC, observedAt DESC
        LIMIT :limit
    """)
    suspend fun searchInScope(
        deviceId: String,
        scope: MemoryScope,
        sourceId: Long,
        query: String,
        limit: Int = 10,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    // ═══════════════════════════════════════════════════════════
    // 时间范围查询
    // ═══════════════════════════════════════════════════════════

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
          AND observedAt >= :since
        ORDER BY observedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentSince(
        deviceId: String,
        since: Long,
        limit: Int = 20,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    // ═══════════════════════════════════════════════════════════
    // 工作记忆管理（短期，按 TTL）
    // ═══════════════════════════════════════════════════════════

    @Query("""
        DELETE FROM unified_memories
        WHERE memoryType = 'WORKING'
          AND expiresAt IS NOT NULL
          AND expiresAt < :now
    """)
    suspend fun cleanupExpiredWorkingMemories(now: Long = System.currentTimeMillis()): Int

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND memoryType = 'WORKING'
          AND scope = :scope
          AND sourceId = :sourceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getWorkingMemories(
        deviceId: String,
        scope: MemoryScope,
        sourceId: Long,
        limit: Int = 50,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    @Query("""
      SELECT * FROM unified_memories
      WHERE deviceId = :deviceId
        AND memoryType = 'WORKING'
        AND scope = :scope
        AND sourceId = :sourceId
        AND isDeleted = 0
        AND (expiresAt IS NULL OR expiresAt > :now)
      ORDER BY createdAt DESC
      LIMIT :limit
    """)
    fun getWorkingMemoriesFlow(
      deviceId: String,
      scope: MemoryScope,
      sourceId: Long,
      limit: Int = 50,
      now: Long = System.currentTimeMillis()
    ): Flow<List<MemoryRecord>>

    // ═══════════════════════════════════════════════════════════
    // 软删除与更新
    // ═══════════════════════════════════════════════════════════

    @Query("UPDATE unified_memories SET isDeleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis()): Int

    @Query("""
      UPDATE unified_memories
      SET isDeleted = 1, updatedAt = :now
      WHERE deviceId = :deviceId
        AND scope = :scope
        AND sourceId = :sourceId
        AND source = :source
        AND isDeleted = 0
    """)
    suspend fun softDeleteByScopeAndSource(
      deviceId: String,
      scope: MemoryScope,
      sourceId: Long,
      source: MemorySource,
      now: Long = System.currentTimeMillis()
    ): Int

    @Query("DELETE FROM unified_memories WHERE deviceId = :deviceId AND scope = :scope AND sourceId = :sourceId")
    suspend fun hardDeleteByScope(deviceId: String, scope: MemoryScope, sourceId: Long): Int

    @Query("UPDATE unified_memories SET accessCount = accessCount + 1, lastAccessedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE unified_memories SET importance = :importance, version = version + 1, updatedAt = :now WHERE id = :id")
    suspend fun updateImportance(id: Long, importance: Float, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE unified_memories SET confidence = :confidence, version = version + 1, updatedAt = :now WHERE id = :id")
    suspend fun updateConfidence(id: Long, confidence: Float, now: Long = System.currentTimeMillis()): Int

    // ═══════════════════════════════════════════════════════════
    // 统计与治理
    // ═══════════════════════════════════════════════════════════

    @Query("SELECT COUNT(*) FROM unified_memories WHERE deviceId = :deviceId AND isDeleted = 0")
    suspend fun count(deviceId: String): Int

    @Query("SELECT COUNT(*) FROM unified_memories WHERE deviceId = :deviceId AND scope = :scope AND sourceId = :sourceId AND isDeleted = 0")
    suspend fun countByScope(deviceId: String, scope: MemoryScope, sourceId: Long): Int

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND isDeleted = 0
          AND content LIKE '%' || :content || '%'
        LIMIT 1
    """)
    suspend fun findByContent(deviceId: String, content: String): MemoryRecord?

    // ═══════════════════════════════════════════════════════════
    // 备份/导出用
    // ═══════════════════════════════════════════════════════════

    @Query("SELECT * FROM unified_memories WHERE deviceId = :deviceId ORDER BY observedAt DESC")
    suspend fun getAllSync(deviceId: String): List<MemoryRecord>

    // ═══════════════════════════════════════════════════════════
    // 语义向量（Phase 3）
    // ═══════════════════════════════════════════════════════════

    /** 更新某条记忆的 embedding 向量和模型名称 */
    @Query("UPDATE unified_memories SET embedding = :embedding, embeddingModel = :model, updatedAt = :now WHERE id = :id")
    suspend fun updateEmbedding(id: Long, embedding: ByteArray, model: String, now: Long = System.currentTimeMillis()): Int

    /** 获取指定 scope 下所有已生成 embedding 的活跃记忆（用于语义检索） */
    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND scope = :scope
          AND sourceId = :sourceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
          AND embedding IS NOT NULL
        ORDER BY importance DESC, observedAt DESC
    """)
    suspend fun getWithEmbeddings(
        deviceId: String,
        scope: MemoryScope,
        sourceId: Long,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    /** 获取全局 scope 下所有已生成 embedding 的活跃记忆 */
    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND scope = 'GLOBAL'
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
          AND embedding IS NOT NULL
        ORDER BY importance DESC, observedAt DESC
    """)
    suspend fun getGlobalWithEmbeddings(
        deviceId: String,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    /** 获取指定 scope 下尚未生成 embedding 的记忆（用于批量补全） */
    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
          AND embedding IS NULL
          AND memoryType != 'WORKING'
        ORDER BY importance DESC, observedAt DESC
        LIMIT :limit
    """)
    suspend fun getWithoutEmbeddings(
        deviceId: String,
        limit: Int = 50,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    // ═══════════════════════════════════════════════════════════
    // 摘要压缩（Phase 4）
    // ═══════════════════════════════════════════════════════════

    /** 统计指定 scope 下 WORKING 记忆数量（用于触发摘要压缩） */
    @Query("""
        SELECT COUNT(*) FROM unified_memories
        WHERE deviceId = :deviceId
          AND memoryType = 'WORKING'
          AND scope = :scope
          AND sourceId = :sourceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
    """)
    suspend fun countWorkingMemories(
        deviceId: String,
        scope: MemoryScope,
        sourceId: Long,
        now: Long = System.currentTimeMillis()
    ): Int

    /** 获取指定 scope 下最早的 N 条 WORKING 记忆（用于摘要压缩） */
    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND memoryType = 'WORKING'
          AND scope = :scope
          AND sourceId = :sourceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY createdAt ASC
        LIMIT :limit
    """)
    suspend fun getOldestWorkingMemories(
        deviceId: String,
        scope: MemoryScope,
        sourceId: Long,
        limit: Int = 10,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    /** 批量软删除指定 ID 列表的 WORKING 记忆（摘要后清理） */
    @Query("UPDATE unified_memories SET isDeleted = 1, updatedAt = :now WHERE id IN (:ids)")
    suspend fun softDeleteByIds(ids: List<Long>, now: Long = System.currentTimeMillis()): Int
}
