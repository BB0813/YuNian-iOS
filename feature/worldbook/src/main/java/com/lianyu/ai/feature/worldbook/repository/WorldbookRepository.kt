package com.lianyu.ai.feature.worldbook.repository

import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.dao.LorebookDao
import com.lianyu.ai.database.model.LorebookEntryEntity
import com.lianyu.ai.database.model.LorebookEntity
import com.lianyu.ai.domain.EntryRole
import com.lianyu.ai.domain.InjectionPosition
import com.lianyu.ai.domain.Lorebook
import com.lianyu.ai.domain.LorebookEntry
import com.lianyu.ai.domain.LorebookProvider
import com.lianyu.ai.domain.LorebookWithEntries
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 世界书仓库实现。
 *
 * 代理 LorebookDao，处理实体与领域模型的转换，
 * 以及关键词匹配、注入逻辑等业务逻辑。
 */
class WorldbookRepository(
    private val database: AppDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : LorebookProvider {

    private val dao: LorebookDao = database.lorebookDao()

    // ═══════════════════════════════════════════════════════════
    // 实体 <-> 领域模型转换
    // ═══════════════════════════════════════════════════════════

    private fun toDomain(entity: LorebookEntity): Lorebook = Lorebook(
        id = entity.id,
        name = entity.name,
        description = entity.description,
        companionId = entity.companionId,
        enabled = entity.isEnabled(),
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt
    )

    private fun toDomain(entity: LorebookEntryEntity): LorebookEntry {
        val keywords = try {
            json.decodeFromString<List<String>>(entity.keywordsJson)
        } catch (e: Exception) {
            emptyList()
        }
        return LorebookEntry(
            id = entity.id,
            lorebookId = entity.lorebookId,
            keywords = keywords,
            content = entity.content,
            injectionPosition = InjectionPosition.values()[entity.injectionPosition.ordinal],
            priority = entity.priority,
            injectDepth = entity.injectDepth,
            role = EntryRole.values()[entity.role.ordinal],
            caseSensitive = entity.isCaseSensitive(),
            scanDepth = entity.scanDepth,
            constantActive = entity.isConstantActive(),
            enabled = entity.isEnabled(),
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }

    private fun toEntity(domain: Lorebook): LorebookEntity = LorebookEntity(
        id = domain.id,
        name = domain.name,
        description = domain.description,
        companionId = domain.companionId,
        enabled = if (domain.enabled) 1 else 0,
        createdAt = domain.createdAt,
        updatedAt = domain.updatedAt
    )

    private fun toEntity(domain: LorebookEntry): LorebookEntryEntity {
        val keywordsJson = json.encodeToString(domain.keywords)
        return LorebookEntryEntity(
            id = domain.id,
            lorebookId = domain.lorebookId,
            keywordsJson = keywordsJson,
            content = domain.content,
            injectionPosition = com.lianyu.ai.database.model.InjectionPosition.values()[domain.injectionPosition.ordinal],
            priority = domain.priority,
            injectDepth = domain.injectDepth,
            role = com.lianyu.ai.database.model.EntryRole.values()[domain.role.ordinal],
            caseSensitive = if (domain.caseSensitive) 1 else 0,
            scanDepth = domain.scanDepth,
            constantActive = if (domain.constantActive) 1 else 0,
            enabled = if (domain.enabled) 1 else 0,
            createdAt = domain.createdAt,
            updatedAt = domain.updatedAt
        )
    }

    // ═══════════════════════════════════════════════════════════
    // LorebookProvider 接口实现
    // ═══════════════════════════════════════════════════════════

    override suspend fun getEnabledEntriesForCompanion(companionId: Long): List<LorebookEntry> {
        val lorebooksWithEntries = dao.getEnabledLorebooksWithEntries(companionId)
        return lorebooksWithEntries.flatMap { it.entries.filter { it.isEnabled() }.map { toDomain(it) } }
    }

    override suspend fun getTriggeredEntries(
        companionId: Long,
        recentMessages: List<com.lianyu.ai.domain.ContextMessage>
    ): List<com.lianyu.ai.domain.TriggeredEntry> {
        val allEntries = getEnabledEntriesForCompanion(companionId)
        val triggered = mutableListOf<com.lianyu.ai.domain.TriggeredEntry>()

        for (entry in allEntries) {
            if (!entry.enabled) continue

            // 常驻激活：无需关键词匹配，直接注入
            if (entry.constantActive) {
                triggered.add(com.lianyu.ai.domain.TriggeredEntry(
                    entry = entry,
                    matchedKeyword = "[CONSTANT]",
                    matchIndex = -1
                ))
                continue
            }

            // 关键词匹配：检查最近 scanDepth 条消息
            val scanCount = minOf(entry.scanDepth, recentMessages.size)
            for (i in 0 until scanCount) {
                val msg = recentMessages[i]
                for (keyword in entry.keywords) {
                    val matched = if (entry.caseSensitive) {
                        msg.content.contains(keyword)
                    } else {
                        msg.content.lowercase().contains(keyword.lowercase())
                    }
                    if (matched) {
                        triggered.add(com.lianyu.ai.domain.TriggeredEntry(
                            entry = entry,
                            matchedKeyword = keyword,
                            matchIndex = i
                        ))
                        break // 一个条目最多触发一次，按第一个匹配的关键词
                    }
                }
            }
        }

        // 按 priority 降序、createdAt 升序排序
        return triggered.sortedWith(
            compareByDescending<com.lianyu.ai.domain.TriggeredEntry> { it.entry.priority }
                .thenBy { it.entry.createdAt }
        )
    }

    override suspend fun getLorebookWithEntries(lorebookId: Long): LorebookWithEntries? {
        val lorebookEntity = dao.getLorebookById(lorebookId) ?: return null
        val entryEntities = dao.getEnabledEntriesByLorebookId(lorebookId)
        return LorebookWithEntries(
            lorebook = toDomain(lorebookEntity),
            entries = entryEntities.map { toDomain(it) }
        )
    }

    override suspend fun createLorebook(lorebook: Lorebook, entries: List<LorebookEntry>): Long {
        return database.withTransaction {
            val lorebookId = dao.insertLorebook(toEntity(lorebook.copy(id = 0)))
            val entryEntities = entries.map { toEntity(it.copy(lorebookId = lorebookId, id = 0)) }
            dao.insertEntries(entryEntities)
            lorebookId
        }
    }

    override suspend fun updateLorebook(lorebook: Lorebook, entries: List<LorebookEntry>): Boolean {
        return database.withTransaction {
            val updated = dao.updateLorebook(toEntity(lorebook)) > 0
            // 删除旧条目，插入新条目
            dao.deleteEntriesByLorebookId(lorebook.id)
            val entryEntities = entries.map { toEntity(it.copy(lorebookId = lorebook.id)) }
            dao.insertEntries(entryEntities)
            updated
        }
    }

    override suspend fun deleteLorebook(lorebookId: Long): Boolean {
        return database.withTransaction {
            dao.deleteEntriesByLorebookId(lorebookId)
            dao.deleteLorebookById(lorebookId) > 0
        }
    }

    override suspend fun upsertEntry(entry: LorebookEntry): Long {
        return database.withTransaction {
            dao.insertEntry(toEntity(entry))
        }
    }

    override suspend fun deleteEntry(entryId: Long): Boolean {
        return dao.deleteEntryById(entryId) > 0
    }

    override suspend fun setLorebookEnabled(lorebookId: Long, enabled: Boolean): Boolean {
        val entity = dao.getLorebookById(lorebookId) ?: return false
        val updated = entity.copy(enabled = if (enabled) 1 else 0, updatedAt = System.currentTimeMillis())
        return dao.updateLorebook(updated) > 0
    }

    override suspend fun setEntryEnabled(entryId: Long, enabled: Boolean): Boolean {
        val entity = dao.getEntryById(entryId) ?: return false
        val updated = entity.copy(enabled = if (enabled) 1 else 0, updatedAt = System.currentTimeMillis())
        return dao.updateEntry(updated) > 0
    }

    // ═══════════════════════════════════════════════════════════
    // 额外查询（供 UI 使用）
    // ═══════════════════════════════════════════════════════════

    fun getLorebooksByCompanionIdFlow(companionId: Long): Flow<List<Lorebook>> =
        dao.getLorebooksByCompanionIdFlow(companionId)
            .map { it.map { toDomain(it) } }

    fun getGlobalLorebooksFlow(): Flow<List<Lorebook>> =
        dao.getGlobalLorebooksFlow()
            .map { it.map { toDomain(it) } }

    fun getAllLorebooksFlow(): Flow<List<Lorebook>> =
        dao.getAllLorebooksFlow()
            .map { it.map { toDomain(it) } }

    fun getEntriesByLorebookIdFlow(lorebookId: Long): Flow<List<LorebookEntry>> =
        dao.getEntriesByLorebookIdFlow(lorebookId)
            .map { it.map { toDomain(it) } }

    suspend fun getLorebooksByCompanionId(companionId: Long): List<Lorebook> =
        dao.getLorebooksByCompanionId(companionId).map { toDomain(it) }

    suspend fun getGlobalLorebooks(): List<Lorebook> =
        dao.getGlobalLorebooks().map { toDomain(it) }

    override suspend fun getAllLorebooks(): List<Lorebook> =
        dao.getAllLorebooks().map { toDomain(it) }

    suspend fun getEntriesByLorebookId(lorebookId: Long): List<LorebookEntry> =
        dao.getEntriesByLorebookId(lorebookId).map { toDomain(it) }
}