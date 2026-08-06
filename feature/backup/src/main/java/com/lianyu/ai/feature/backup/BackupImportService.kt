package com.lianyu.ai.feature.backup

import android.content.Context
import androidx.room.withTransaction
import com.lianyu.ai.common.DeviceIdProvider
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.cache.MessageCache
import com.lianyu.ai.database.model.*
import com.lianyu.ai.database.repository.ChatMessageCrypto
import com.lianyu.ai.database.repository.MemoryCrypto
import com.lianyu.ai.feature.backup.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 数据导入服务 — 合并模式。
 *
 * 导入策略：以「联系人名称」作为唯一匹配字段，同名联系人合并，不覆盖现有数据。
 * - 联系人在本地已存在同名 → 复用本地 id，仅合并聊天数据
 * - 联系人在本地不存在 → 新建联系人，并重映射备份内所有相关数据
 * - 聊天消息按原时间戳插入，并依据 (会话, 时间戳, 方向, 内容) 去重
 * - 群聊按名称合并；记忆/临时记忆/Token 统计按重映射后的联系人 id 追加
 * - 受影响会话的摘要 (ConversationSummary) 重建，保留置顶/静音/已读状态
 */
class BackupImportService(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val deviceId = DeviceIdProvider.getDeviceId(context)

    /** 去重键：单聊消息 */
    private data class ChatKey(val timestamp: Long, val isFromUser: Boolean, val content: String)

    /** 去重键：群聊消息 */
    private data class GroupKey(val timestamp: Long, val senderId: Long, val content: String)

    suspend fun import(data: BackupData): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 整个合并序列包在单一 Room 事务里：中途失败整体回滚，保证原子性
            db.withTransaction {
                merge(data)
            }
            MessageCache.clearAll()
        }
    }

    private suspend fun merge(data: BackupData) {
        // ── 1. 联系人合并（按名称匹配）──
        val existingCompanions = db.companionDao().getAllCompanionsSync()
        val existingByName = existingCompanions.associateBy { it.name }
        // snapshot companionId → 本地实际 companionId
        val companionIdMap = mutableMapOf<Long, Long>()

        data.companions.forEach { s ->
            val existing = existingByName[s.name]
            if (existing != null) {
                companionIdMap[s.id] = existing.id
            } else {
                val newId = db.companionDao().insertCompanion(
                    CompanionEntity(
                        id = 0, name = s.name, avatarUrl = s.avatarUrl, age = s.age,
                        personality = s.personality, backstory = s.backstory,
                        speakingStyle = s.speakingStyle, tags = s.tags,
                        rawPrompt = s.rawPrompt, systemPrompt = s.systemPrompt,
                        intimacy = s.intimacy, createdAt = s.createdAt,
                        updatedAt = s.updatedAt
                    )
                )
                companionIdMap[s.id] = newId
            }
        }

        // ── 2. 群组合并（按名称匹配，成员 id 重映射）──
        val existingGroups = db.chatGroupDao().getAllGroupsSync()
        val existingGroupsByName = existingGroups.associateBy { it.name }
        // snapshot groupId → 本地实际 groupId
        val groupIdMap = mutableMapOf<Long, Long>()

        data.chatGroups.forEach { s ->
            val existing = existingGroupsByName[s.name]
            val backupMembers = s.companionIds.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .map { companionIdMap[it] ?: it }
            if (existing != null) {
                groupIdMap[s.id] = existing.id
                // 同名群组合并成员（本地 ∪ 备份映射后）
                val merged = (existing.getCompanionIdList().toMutableSet() + backupMembers).joinToString(",")
                if (merged != existing.companionIds) {
                    db.chatGroupDao().updateGroup(existing.copy(companionIds = merged))
                }
            } else {
                val newId = db.chatGroupDao().insertGroup(
                    ChatGroup(
                        id = 0, name = s.name, avatarUrl = s.avatarUrl,
                        companionIds = backupMembers.distinct().joinToString(","),
                        createdAt = s.createdAt, updatedAt = s.updatedAt
                    )
                )
                groupIdMap[s.id] = newId
            }
        }

        // ── 3. 单聊消息合并（按时间戳插入 + 去重）──
        // snapshot messageId → 本地 messageId（用于 anchor 重映射）
        val messageIdMap = mutableMapOf<Long, Long>()

        data.chatMessages.groupBy { it.companionId }.forEach { (snapshotCompanionId, messages) ->
            val localCompanionId = companionIdMap[snapshotCompanionId] ?: return@forEach
            // 读取本地该会话消息，解密后构建去重键
            val localStored = db.messageDao().getAllMessagesSync(localCompanionId, "chat")
            val localMessages = if (localStored.isEmpty()) emptyList()
            else ChatMessageCrypto.decryptFromStorage(localStored.map { it.toChatMessage() })
            val existingByKey = localMessages.associateBy {
                ChatKey(it.timestamp, it.isFromUser, it.content)
            }
            val insertedKeys = existingByKey.keys.toMutableSet()

            messages.sortedBy { it.timestamp }.forEach { s ->
                val key = ChatKey(s.timestamp, s.isFromUser, s.content)
                if (key !in insertedKeys) {
                    val chatMessage = ChatMessage(
                        id = 0, companionId = localCompanionId, content = s.content,
                        isFromUser = s.isFromUser, timestamp = s.timestamp,
                        type = safeEnum<MessageType>(s.type),
                        searchContent = s.searchContent.ifEmpty { s.content },
                        fileFormat = safeEnum<FileFormat>(s.fileFormat),
                        linkString = s.linkString,
                        turnId = s.turnId, eventIndex = s.eventIndex,
                        durationMs = s.durationMs, anchorMessageId = null
                    )
                    val encrypted = ChatMessageCrypto.encryptForStorage(listOf(chatMessage)).first()
                    val (metadata, body) = StoredMessage.fromChatMessage(encrypted)
                    val newId = db.messageDao().insertStoredMessage(metadata, body)
                    messageIdMap[s.id] = newId
                    insertedKeys.add(key)
                } else {
                    // 去重命中：映射到本地已存在消息 id（供 anchor 引用）
                    existingByKey[key]?.let { messageIdMap[s.id] = it.id }
                }
            }
        }

        // ── 4. 回复引用 (anchorMessageId) 重映射 ──
        data.chatMessages.forEach { s ->
            val newId = messageIdMap[s.id] ?: return@forEach
            val anchorNewId = s.anchorMessageId?.let { messageIdMap[it] }
            if (anchorNewId != null) {
                db.messageDao().updateAnchorMessageId(newId, anchorNewId)
            }
        }

        // ── 5. 群聊消息合并（groupId / 发送者 id 重映射 + 去重）──
        data.groupMessages.groupBy { it.groupId }.forEach { (snapshotGroupId, messages) ->
            val localGroupId = groupIdMap[snapshotGroupId] ?: return@forEach
            val localStored = db.messageDao().getAllMessagesSync(localGroupId, "group")
            val localMessages = if (localStored.isEmpty()) emptyList()
            else ChatMessageCrypto.decryptFromStorageGroup(localStored.map { it.toGroupMessage() })
            val existingByKey = localMessages.associateBy {
                GroupKey(it.timestamp, it.companionId, it.content)
            }
            val insertedKeys = existingByKey.keys.toMutableSet()

            messages.sortedBy { it.timestamp }.forEach { s ->
                val senderLocalId = if (s.companionId == -1L) -1L
                else companionIdMap[s.companionId] ?: s.companionId
                val key = GroupKey(s.timestamp, senderLocalId, s.content)
                if (key !in insertedKeys) {
                    val groupMessage = GroupMessage(
                        id = 0, groupId = localGroupId, companionId = senderLocalId,
                        content = s.content, timestamp = s.timestamp,
                        searchContent = s.searchContent.ifEmpty { s.content },
                        fileFormat = safeEnum<FileFormat>(s.fileFormat),
                        linkString = s.linkString
                    )
                    val encrypted = ChatMessageCrypto.encryptForStorageGroup(listOf(groupMessage)).first()
                    val (metadata, body) = StoredMessage.fromGroupMessage(encrypted)
                    db.messageDao().insertStoredMessage(metadata, body)
                    insertedKeys.add(key)
                }
            }
        }

        // ── 6. 重建受影响会话摘要（保留置顶/静音/已读状态）──
        val affectedChatSessions = data.chatMessages.mapNotNull { companionIdMap[it.companionId] }.distinct()
        val affectedGroupSessions = data.groupMessages.mapNotNull { groupIdMap[it.groupId] }.distinct()
        affectedChatSessions.forEach { rebuildChatSummary(it) }
        affectedGroupSessions.forEach { rebuildGroupSummary(it) }

        // ── 7. 记忆合并（联系人 id 重映射，context 重新加密）──
        data.memoryEntries.forEach { s ->
            val localCompanionId = companionIdMap[s.companionId] ?: s.companionId
            val encryptedContext = if (s.context.isNotBlank()) {
                try { MemoryCrypto.encrypt(s.context) } catch (_: Exception) { s.context }
            } else ""
            db.memoryDao().insertMemory(
                MemoryEntry(
                    id = 0, companionId = localCompanionId, content = s.content,
                    category = safeEnum<MemoryCategory>(s.category),
                    importance = s.importance, context = encryptedContext,
                    accessCount = s.accessCount, timestamp = s.timestamp,
                    lastAccessed = s.lastAccessed, deviceId = deviceId
                )
            )
        }

        // ── 8. 临时记忆合并 ──
        data.tempMemories.forEach { s ->
            db.memoryDao().insertTempMemory(
                TempMemory(
                    id = 0, companionId = companionIdMap[s.companionId] ?: s.companionId,
                    userInput = s.userInput, botResponse = s.botResponse,
                    timestamp = s.timestamp, deviceId = deviceId
                )
            )
        }

        // ── 9. Token 统计合并 ──
        data.tokenUsages.forEach { s ->
            db.tokenUsageDao().insert(
                TokenUsage(
                    id = 0, companionId = companionIdMap[s.companionId] ?: s.companionId,
                    date = s.date, inputTokens = s.inputTokens, outputTokens = s.outputTokens,
                    totalTokens = s.totalTokens, requestCount = s.requestCount,
                    timestamp = s.timestamp, deviceId = deviceId
                )
            )
        }
    }

    private suspend fun rebuildChatSummary(companionId: Long) {
        val last = db.messageDao().getLastMessageSync(companionId, "chat") ?: return
        val decrypted = ChatMessageCrypto.decryptFromStorage(last.toChatMessage())
        val existing = db.conversationSummaryDao().getSummarySync(companionId, "chat")
        db.conversationSummaryDao().upsertSummary(
            ConversationSummary(
                sessionId = companionId,
                sessionType = "chat",
                lastMessageId = last.metadata.id,
                lastMessagePreview = decrypted.content.take(100),
                lastMessageTimestamp = decrypted.timestamp,
                lastMessageIsFromUser = decrypted.isFromUser,
                readThroughMessageTimestamp = existing?.readThroughMessageTimestamp,
                readThroughMessageId = existing?.readThroughMessageId,
                unreadCount = existing?.unreadCount ?: 0,
                isPinned = existing?.isPinned ?: false,
                isMuted = existing?.isMuted ?: false
            )
        )
    }

    private suspend fun rebuildGroupSummary(groupId: Long) {
        val last = db.messageDao().getLastMessageSync(groupId, "group") ?: return
        val decrypted = ChatMessageCrypto.decryptFromStorage(last.toGroupMessage())
        val existing = db.conversationSummaryDao().getSummarySync(groupId, "group")
        db.conversationSummaryDao().upsertSummary(
            ConversationSummary(
                sessionId = groupId,
                sessionType = "group",
                lastMessageId = last.metadata.id,
                lastMessagePreview = decrypted.content.take(100),
                lastMessageTimestamp = decrypted.timestamp,
                lastMessageIsFromUser = decrypted.companionId == -1L,
                readThroughMessageTimestamp = existing?.readThroughMessageTimestamp,
                readThroughMessageId = existing?.readThroughMessageId,
                unreadCount = existing?.unreadCount ?: 0,
                isPinned = existing?.isPinned ?: false,
                isMuted = existing?.isMuted ?: false
            )
        )
    }

    /** 安全枚举解析：无效值回退到默认 */
    private inline fun <reified T : Enum<T>> safeEnum(name: String): T {
        return try { enumValueOf<T>(name) }
        catch (_: Exception) { enumValues<T>().first() }
    }
}
