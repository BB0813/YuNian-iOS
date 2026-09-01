package com.lianyu.ai.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.lianyu.ai.database.model.LorebookEntity
import com.lianyu.ai.database.model.LorebookEntryEntity
import com.lianyu.ai.database.model.LorebookWithEntries
import kotlinx.coroutines.flow.Flow

/**
 * 世界书/知识书 DAO。
 *
 * 提供世界书及其条目的 CRUD 操作，以及按伴侣查询、关键词触发检查等业务查询。
 */
@Dao
interface LorebookDao {

    // ═══════════════════════════════════════════════════════════
    // Lorebook 写入
    // ═══════════════════════════════════════════════════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLorebook(lorebook: LorebookEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLorebooks(lorebooks: List<LorebookEntity>): List<Long>

    @Update
    suspend fun updateLorebook(lorebook: LorebookEntity): Int

    @Delete
    suspend fun deleteLorebook(lorebook: LorebookEntity): Int

    @Query("DELETE FROM lorebooks WHERE id = :id")
    suspend fun deleteLorebookById(id: Long): Int

    // ═══════════════════════════════════════════════════════════
    // Lorebook 查询
    // ═══════════════════════════════════════════════════════════

    @Query("SELECT * FROM lorebooks WHERE id = :id")
    suspend fun getLorebookById(id: Long): LorebookEntity?

    @Query("SELECT * FROM lorebooks WHERE id = :id")
    fun getLorebookByIdFlow(id: Long): Flow<LorebookEntity?>

    @Query("SELECT * FROM lorebooks WHERE companionId = :companionId ORDER BY createdAt DESC")
    suspend fun getLorebooksByCompanionId(companionId: Long): List<LorebookEntity>

    @Query("SELECT * FROM lorebooks WHERE companionId = :companionId ORDER BY createdAt DESC")
    fun getLorebooksByCompanionIdFlow(companionId: Long): Flow<List<LorebookEntity>>

    @Query("SELECT * FROM lorebooks WHERE companionId IS NULL ORDER BY createdAt DESC")
    suspend fun getGlobalLorebooks(): List<LorebookEntity>

    @Query("SELECT * FROM lorebooks WHERE companionId IS NULL ORDER BY createdAt DESC")
    fun getGlobalLorebooksFlow(): Flow<List<LorebookEntity>>

    @Query("SELECT * FROM lorebooks WHERE enabled = 1 AND (companionId = :companionId OR companionId IS NULL) ORDER BY createdAt DESC")
    suspend fun getEnabledLorebooksForCompanion(companionId: Long): List<LorebookEntity>

    @Query("SELECT * FROM lorebooks WHERE enabled = 1 AND (companionId = :companionId OR companionId IS NULL) ORDER BY createdAt DESC")
    fun getEnabledLorebooksForCompanionFlow(companionId: Long): Flow<List<LorebookEntity>>

    @Query("SELECT * FROM lorebooks ORDER BY createdAt DESC")
    suspend fun getAllLorebooks(): List<LorebookEntity>

    @Query("SELECT * FROM lorebooks ORDER BY createdAt DESC")
    fun getAllLorebooksFlow(): Flow<List<LorebookEntity>>

    // ═══════════════════════════════════════════════════════════
    // LorebookEntry 写入
    // ═══════════════════════════════════════════════════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: LorebookEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<LorebookEntryEntity>): List<Long>

    @Update
    suspend fun updateEntry(entry: LorebookEntryEntity): Int

    @Delete
    suspend fun deleteEntry(entry: LorebookEntryEntity): Int

    @Query("DELETE FROM lorebook_entries WHERE id = :id")
    suspend fun deleteEntryById(id: Long): Int

    @Query("DELETE FROM lorebook_entries WHERE lorebookId = :lorebookId")
    suspend fun deleteEntriesByLorebookId(lorebookId: Long): Int

    // ═══════════════════════════════════════════════════════════
    // LorebookEntry 查询
    // ═══════════════════════════════════════════════════════════

    @Query("SELECT * FROM lorebook_entries WHERE id = :id")
    suspend fun getEntryById(id: Long): LorebookEntryEntity?

    @Query("SELECT * FROM lorebook_entries WHERE lorebookId = :lorebookId ORDER BY priority DESC, createdAt ASC")
    suspend fun getEntriesByLorebookId(lorebookId: Long): List<LorebookEntryEntity>

    @Query("SELECT * FROM lorebook_entries WHERE lorebookId = :lorebookId ORDER BY priority DESC, createdAt ASC")
    fun getEntriesByLorebookIdFlow(lorebookId: Long): Flow<List<LorebookEntryEntity>>

    @Query("SELECT * FROM lorebook_entries WHERE enabled = 1 AND lorebookId = :lorebookId ORDER BY priority DESC, createdAt ASC")
    suspend fun getEnabledEntriesByLorebookId(lorebookId: Long): List<LorebookEntryEntity>

    @Query("SELECT * FROM lorebook_entries WHERE enabled = 1 AND lorebookId = :lorebookId ORDER BY priority DESC, createdAt ASC")
    fun getEnabledEntriesByLorebookIdFlow(lorebookId: Long): Flow<List<LorebookEntryEntity>>

    // 事务：获取世界书及其所有启用条目（用于注入检查）
    @Transaction
    @Query("SELECT * FROM lorebooks WHERE enabled = 1 AND (companionId = :companionId OR companionId IS NULL) ORDER BY createdAt DESC")
    suspend fun getEnabledLorebooksWithEntries(companionId: Long): List<LorebookWithEntries>

    @Transaction
    @Query("SELECT * FROM lorebooks WHERE enabled = 1 AND (companionId = :companionId OR companionId IS NULL) ORDER BY createdAt DESC")
    fun getEnabledLorebooksWithEntriesFlow(companionId: Long): Flow<List<LorebookWithEntries>>

    // 统计
    @Query("SELECT COUNT(*) FROM lorebooks WHERE companionId = :companionId")
    suspend fun countLorebooksByCompanionId(companionId: Long): Int

    @Query("SELECT COUNT(*) FROM lorebook_entries WHERE lorebookId = :lorebookId")
    suspend fun countEntriesByLorebookId(lorebookId: Long): Int

    @Query("SELECT COUNT(*) FROM lorebook_entries WHERE lorebookId = :lorebookId AND enabled = 1")
    suspend fun countEnabledEntriesByLorebookId(lorebookId: Long): Int
}