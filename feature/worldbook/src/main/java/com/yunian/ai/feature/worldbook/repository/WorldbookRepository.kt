package com.yunian.ai.feature.worldbook.repository

import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.dao.LorebookDao
import com.yunian.ai.database.model.LorebookEntryEntity
import com.yunian.ai.database.model.LorebookEntity
import com.yunian.ai.domain.EntryRole
import com.yunian.ai.domain.InjectionPosition
import com.yunian.ai.domain.Lorebook
import com.yunian.ai.domain.LorebookEntry
import com.yunian.ai.domain.LorebookProvider
import com.yunian.ai.domain.LorebookWithEntries
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class WorldbookRepository(
    private val database: AppDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : LorebookProvider {

    private val dao: LorebookDao = database.lorebookDao()
    private val companionDao = database.companionDao()

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
            useRegex = entity.isUseRegex(),
            scanDepth = entity.scanDepth,
            constantActive = entity.isConstantActive(),
            enabled = entity.isEnabled(),
            sortOrder = entity.sortOrder,
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
            injectionPosition = com.yunian.ai.database.model.InjectionPosition.values()[domain.injectionPosition.ordinal],
            priority = domain.priority,
            injectDepth = domain.injectDepth,
            role = com.yunian.ai.database.model.EntryRole.values()[domain.role.ordinal],
            caseSensitive = if (domain.caseSensitive) 1 else 0,
            useRegex = if (domain.useRegex) 1 else 0,
            sortOrder = domain.sortOrder,
            scanDepth = domain.scanDepth,
            constantActive = if (domain.constantActive) 1 else 0,
            enabled = if (domain.enabled) 1 else 0,
            createdAt = domain.createdAt,
            updatedAt = domain.updatedAt
        )
    }

    /** 读取角色绑定的全局世界书 ID 集合；未解析成功视为未绑定 */
    private suspend fun parseBoundIds(companionId: Long): List<Long> {
        return try {
            val raw = companionDao.getCompanionById(companionId)?.lorebookIdsJson ?: return emptyList()
            val ids = json.decodeFromString<List<Long>>(raw)
            ids.filter { it > 0 }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getEnabledEntriesForCompanion(companionId: Long): List<LorebookEntry> {
        val boundIds = parseBoundIds(companionId)
        val lorebooksWithEntries = if (boundIds.isEmpty()) {
            // 兼容旧行为：未做绑定选择时，专属书 + 全部全局书自动生效
            dao.getEnabledLorebooksWithEntries(companionId)
        } else {
            // 专属书始终生效；全局书仅生效勾选的
            val ownBooks = dao.getEnabledLorebooksWithEntries(companionId)
                .filter { it.lorebook.companionId != null }
            val boundBooks = dao.getEnabledLorebooksByIds(boundIds)
                .map { book -> com.yunian.ai.database.model.LorebookWithEntries(
                    lorebook = book,
                    entries = dao.getEnabledEntriesByLorebookId(book.id)
                ) }
            ownBooks + boundBooks
        }
        return lorebooksWithEntries.flatMap { it.entries.filter { it.isEnabled() }.map { toDomain(it) } }
    }

    override suspend fun getTriggeredEntries(
        companionId: Long,
        recentMessages: List<com.yunian.ai.domain.ContextMessage>
    ): List<com.yunian.ai.domain.TriggeredEntry> {
        val allEntries = getEnabledEntriesForCompanion(companionId)
        val triggered = mutableListOf<com.yunian.ai.domain.TriggeredEntry>()

        for (entry in allEntries) {
            if (!entry.enabled) continue

            // 常驻条目无需关键词匹配，直接触发
            if (entry.constantActive) {
                triggered.add(com.yunian.ai.domain.TriggeredEntry(
                    entry = entry,
                    matchedKeyword = "[CONSTANT]",
                    matchIndex = -1
                ))
                continue
            }

            if (entry.keywords.isEmpty()) continue

            // 取最近 scanDepth 条消息拼接上下文，每条条目只判断一次，避免同一条件在多条消息命中时重复注入
            // recentMessages 约定：index 0 为最新消息
            val scanCount = minOf(entry.scanDepth.coerceAtLeast(1), recentMessages.size)
            val context = recentMessages.take(scanCount).joinToString("\n") { it.content }

            val matchedKeyword = entry.keywords.firstOrNull { keyword ->
                if (keyword.isBlank()) return@firstOrNull false
                if (entry.useRegex) {
                    try {
                        val options = if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                        Regex(keyword, options).containsMatchIn(context)
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    if (entry.caseSensitive) {
                        context.contains(keyword)
                    } else {
                        context.contains(keyword, ignoreCase = true)
                    }
                }
            }

            if (matchedKeyword != null) {
                triggered.add(com.yunian.ai.domain.TriggeredEntry(
                    entry = entry,
                    matchedKeyword = matchedKeyword,
                    matchIndex = -1
                ))
            }
        }

        return triggered.sortedWith(
            compareByDescending<com.yunian.ai.domain.TriggeredEntry> { it.entry.priority }
                .thenBy { it.entry.createdAt }
        )
    }

    override suspend fun getLorebookWithEntries(lorebookId: Long): LorebookWithEntries? {
        val lorebookEntity = dao.getLorebookById(lorebookId) ?: return null
        // 返回全量条目（含禁用），否则编辑页无法回显/重新启用被禁用的条目
        val entryEntities = dao.getEntriesByLorebookId(lorebookId)
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

    override suspend fun getBoundLorebookIds(companionId: Long): List<Long> = parseBoundIds(companionId)

    override suspend fun setBoundLorebookIds(companionId: Long, ids: List<Long>): Boolean {
        val companion = companionDao.getCompanionById(companionId) ?: return false
        val normalized = ids.filter { it > 0 }.distinct()
        val updated = companion.copy(
            lorebookIdsJson = json.encodeToString(normalized),
            updatedAt = System.currentTimeMillis()
        )
        return companionDao.updateCompanion(updated) > 0
    }

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

    override suspend fun getEntries(lorebookId: Long): List<LorebookEntry> =
        dao.getEntriesByLorebookId(lorebookId).map { toDomain(it) }

    suspend fun getEntriesByLorebookId(lorebookId: Long): List<LorebookEntry> =
        dao.getEntriesByLorebookId(lorebookId).map { toDomain(it) }
}
