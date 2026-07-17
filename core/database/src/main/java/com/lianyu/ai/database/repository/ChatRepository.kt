package com.lianyu.ai.database.repository

import androidx.room.withTransaction
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.cache.MessageCache
import com.lianyu.ai.database.dao.ConversationSummaryDao
import com.lianyu.ai.database.dao.MessageDao
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.ConversationSummary
import com.lianyu.ai.database.model.FileFormat
import com.lianyu.ai.database.model.Message
import com.lianyu.ai.database.model.StoredMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * 聊天消息仓库 — 三级存储架构集成层。
 *
 * L1: MessageCache (LruCache 内存缓存) — 进入会话时先读缓存，避免 loading 闪烁
 * L2: 统一 MessageDao → messages 表 (WAL 模式 + 复合索引)
 * L3: 文件系统 — 图片/视频等大文件，SQLite 仅存路径
 *
 * 对外 API 保持 ChatMessage 类型兼容，内部分 MessageDao + Message→ChatMessage 转换。
 */
class ChatRepository(
    private val messageDao: MessageDao,
    private val summaryDao: ConversationSummaryDao,
    private val database: AppDatabase
) {

    /** 缓存预热 */
    fun warmCache(companionId: Long, messages: List<ChatMessage>) {
        MessageCache.putChatMessages(companionId, messages)
    }

    fun getCachedRecent(companionId: Long): List<ChatMessage>? =
        MessageCache.getChatMessages(companionId)

    fun observeCachedRecent(companionId: Long): StateFlow<List<ChatMessage>> =
        MessageCache.observeChatMessages(companionId)

    fun observeRecentMetadata(companionId: Long, limit: Int): Flow<List<Message>> =
        messageDao.getRecentMessageMetadata(companionId, "chat", limit)

    suspend fun getRecentMetadata(companionId: Long, limit: Int): List<Message> =
        mergeMetadata(
            messageDao.getRecentMessageMetadataSync(companionId, "chat", limit),
            messageDao.getRecentArchivedMessageMetadata(companionId, "chat", limit),
            limit
        )

    suspend fun getMetadataBefore(
        companionId: Long,
        beforeTimestamp: Long,
        beforeId: Long,
        limit: Int
    ): List<Message> = mergeMetadata(
        messageDao.getMessageMetadataBeforeSync(companionId, "chat", beforeTimestamp, beforeId, limit),
        messageDao.getArchivedMessageMetadataBefore(companionId, "chat", beforeTimestamp, beforeId, limit),
        limit
    )

    suspend fun loadMessages(metadata: List<Message>): Map<Long, ChatMessage> {
        val companionId = metadata.firstOrNull()?.conversationId
        val cachedById = companionId?.let(MessageCache::getChatMessagesById).orEmpty()
        val missing = ArrayList<Message>()
        metadata.forEach { item ->
            if (item.id !in cachedById) missing += item
        }
        if (missing.isEmpty()) {
            return buildMap(metadata.size) {
                metadata.forEach { item -> cachedById[item.id]?.let { put(item.id, it) } }
            }
        }
        val loadedById = fromMessages(loadStoredMessages(missing)).associateBy { it.id }
        return buildMap(metadata.size) {
            metadata.forEach { item ->
                (cachedById[item.id] ?: loadedById[item.id])?.let { put(item.id, it) }
            }
        }
    }

    suspend fun hydrateRecent(companionId: Long, limit: Int) {
        if (MessageCache.getChatMessages(companionId) == null) {
            getRecentMessagesSync(companionId, limit)
        }
    }

    // --- 游标分页 ---
    fun getMessagesForCompanion(companionId: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        messageDao.getRecentMessageMetadata(companionId, "chat", limit)
            .map { fromMessages(loadStoredMessages(getRecentMetadata(companionId, limit))).reversed() }
            .onEach { decrypted -> MessageCache.putChatMessages(companionId, decrypted) }

    fun getMessagesBefore(companionId: Long, beforeTimestamp: Long, beforeId: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        messageDao.getMessageMetadataBefore(companionId, "chat", beforeTimestamp, beforeId, limit)
            .map {
                fromMessages(loadStoredMessages(getMetadataBefore(companionId, beforeTimestamp, beforeId, limit)))
                    .reversed()
            }

    fun getMessagesAfter(companionId: Long, afterTimestamp: Long, afterId: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        messageDao.getMessageMetadataAfter(companionId, "chat", afterTimestamp, afterId, limit)
            .map { getMessagesAfterSync(companionId, afterTimestamp, afterId, limit) }

    fun getLastMessageForCompanion(companionId: Long): Flow<ChatMessage?> =
        messageDao.getLastMessage(companionId, "chat")
            .map { hot ->
                val archived = messageDao.getLastArchivedMessageMetadata(companionId, "chat")
                val latestMetadata = mergeMetadata(
                    hot?.let { listOf(it.metadata) }.orEmpty(),
                    archived?.let(::listOf).orEmpty(),
                    limit = 1
                ).firstOrNull()
                latestMetadata?.let { loadStoredMessages(listOf(it)).firstOrNull() }?.let { fromMessage(it) }
            }

    /**
     * 发送消息 — 写入 L2 + 更新 L1 缓存 + 更新会话摘要。
     */
    internal suspend fun sendMessage(message: ChatMessage): Long {
        val encrypted = ChatMessageCrypto.encryptForStorage(message)
        val (metadata, body) = StoredMessage.fromChatMessage(encrypted)
        val id = database.withTransaction {
            val insertedId = messageDao.insertStoredMessage(metadata, body)
            updateSummaryForChat(message.companionId, message.copy(id = insertedId), updateCache = false)
            insertedId
        }
        MessageCache.appendChatMessage(message.companionId, message.copy(id = id))
        summaryDao.getSummarySync(message.companionId, "chat")?.let { putSummaryInCache(it) }
        return id
    }

    suspend fun deleteMessage(message: ChatMessage) {
        val summary = database.withTransaction {
            messageDao.getMessageById(message.id)?.let { messageDao.deleteMessage(it.metadata) }
                ?: messageDao.deleteArchivedMessage(message.id)
            rebuildSummaryForChat(message.companionId)
        }
        MessageCache.removeChatMessage(message.companionId, message.id)
        summary?.let { putSummaryInCache(it) } ?: MessageCache.evictChat(message.companionId)
    }

    suspend fun clearChatHistory(companionId: Long) {
        database.withTransaction {
            messageDao.deleteAllMessagesForConversation(companionId, "chat")
            summaryDao.deleteSummary(companionId, "chat")
        }
        MessageCache.evictChat(companionId)
    }

    suspend fun markReadThroughLatest(companionId: Long) {
        summaryDao.markReadThroughLatest(companionId, "chat")
        summaryDao.getSummarySync(companionId, "chat")?.let { putSummaryInCache(it) }
    }

    suspend fun getAiMessageCount(companionId: Long): Int =
        messageDao.getAiMessageCount(companionId, "chat") +
            messageDao.getArchivedAiMessageCount(companionId, "chat")

    suspend fun getMessageCount(companionId: Long): Int =
        messageDao.getMessageCount(companionId, "chat") +
            messageDao.getArchivedMessageCount(companionId, "chat")

    suspend fun getRecentMessagesSync(companionId: Long, limit: Int): List<ChatMessage> =
        fromMessages(loadStoredMessages(getRecentMetadata(companionId, limit)))
            .reversed()
            .also { MessageCache.putChatMessages(companionId, it) }

    suspend fun getMessagesBeforeSync(companionId: Long, beforeTimestamp: Long, beforeId: Long, limit: Int): List<ChatMessage> =
        fromMessages(loadStoredMessages(getMetadataBefore(companionId, beforeTimestamp, beforeId, limit)))

    suspend fun getMessagesAfterSync(companionId: Long, afterTimestamp: Long, afterId: Long, limit: Int): List<ChatMessage> =
        fromMessages(
            loadStoredMessages(
                mergeMetadata(
                    messageDao.getMessageMetadataAfterSync(companionId, "chat", afterTimestamp, afterId, limit),
                    messageDao.getArchivedMessageMetadataAfter(companionId, "chat", afterTimestamp, afterId, limit),
                    limit,
                    descending = false
                )
            )
        )

    suspend fun getMessageById(messageId: Long): ChatMessage? =
        messageDao.getMessageById(messageId)?.let { fromMessage(it) }
            ?: messageDao.getArchivedMessageMetadataById(messageId)?.let { metadata ->
                loadStoredMessages(listOf(metadata)).firstOrNull()?.let { fromMessage(it) }
            }

    suspend fun getMessagesForCompanionSync(companionId: Long): List<ChatMessage> =
        fromMessages(
            loadStoredMessages(
                (messageDao.getAllMessagesSync(companionId, "chat").map { it.metadata } +
                    messageDao.getAllArchivedMessageMetadata(companionId, "chat"))
                    .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
            )
        )

    suspend fun archiveOldMessages(companionId: Long, retainCount: Int): Int =
        messageDao.archiveOldMessages(companionId, "chat", retainCount)

    suspend fun restoreArchivedMessages(companionId: Long): Int =
        messageDao.restoreArchivedMessages(companionId, "chat")

    suspend fun searchMessages(companionId: Long, query: String, limit: Int = 50): List<ChatMessage> =
        fromMessages(
            loadStoredMessages(
                mergeMetadata(
                    messageDao.searchMessages(companionId, "chat", query, limit).map { it.metadata },
                    messageDao.searchArchivedMessageMetadata(companionId, "chat", query, limit),
                    limit
                )
            )
        )

    suspend fun getMessagesByFileFormat(companionId: Long, fileFormat: FileFormat, limit: Int = 50): List<ChatMessage> =
        fromMessages(
            loadStoredMessages(
                mergeMetadata(
                    messageDao.getMessagesByFileFormat(companionId, "chat", fileFormat, limit).map { it.metadata },
                    messageDao.getArchivedMessageMetadataByFileFormat(companionId, "chat", fileFormat, limit),
                    limit
                )
            )
        )

    suspend fun getMessagesByFileFormatBefore(
        companionId: Long,
        fileFormat: FileFormat,
        beforeTimestamp: Long,
        beforeId: Long,
        limit: Int = 50
    ): List<ChatMessage> =
        fromMessages(
            loadStoredMessages(
                mergeMetadata(
                    messageDao.getMessagesByFileFormatBefore(
                        companionId, "chat", fileFormat, beforeTimestamp, beforeId, limit
                    ).map { it.metadata },
                    messageDao.getArchivedMessageMetadataByFileFormatBefore(
                        companionId, "chat", fileFormat, beforeTimestamp, beforeId, limit
                    ),
                    limit
                )
            )
        )

    suspend fun updateMessageContent(messageId: Long, content: String) {
        val updated = database.withTransaction {
            val encryptedContent = ChatMessageCrypto.encrypt(content)
            if (messageDao.updateMessageContent(messageId, encryptedContent, content) == 0) {
                messageDao.updateArchivedMessageContent(messageId, encryptedContent, content)
            }
            getStoredMessageById(messageId)?.also {
                val summary = summaryDao.getSummarySync(it.metadata.conversationId, "chat")
                if (summary?.lastMessageId == messageId) {
                    summaryDao.upsertSummary(summary.copy(lastMessagePreview = content.take(100)))
                }
            }
        }
        if (updated != null) {
            val decrypted = fromMessage(updated)
            MessageCache.updateChatMessage(decrypted.companionId, messageId) { decrypted }
            summaryDao.getSummarySync(decrypted.companionId, "chat")?.let { putSummaryInCache(it) }
        }
    }

    private suspend fun getStoredMessageById(messageId: Long): StoredMessage? =
        messageDao.getMessageById(messageId)
            ?: messageDao.getArchivedMessageMetadataById(messageId)?.let { metadata ->
                loadStoredMessages(listOf(metadata)).firstOrNull()
            }

    private suspend fun loadStoredMessages(metadata: List<Message>): List<StoredMessage> {
        if (metadata.isEmpty()) return emptyList()
        return database.withTransaction {
            val messageIds = metadata.map { it.id }
            val bodiesById = (
                messageDao.getMessageBodies(messageIds) +
                    messageDao.getArchivedMessageBodies(messageIds)
                ).associateBy { it.messageId }
            metadata.map { message ->
                StoredMessage(message, requireNotNull(bodiesById[message.id]) { "Missing body for message ${message.id}" })
            }
        }
    }

    private fun mergeMetadata(
        hot: List<Message>,
        archived: List<Message>,
        limit: Int,
        descending: Boolean = true
    ): List<Message> =
        (hot + archived)
            .distinctBy { it.id }
            .sortedWith(
                if (descending) {
                    compareByDescending<Message> { it.timestamp }.thenByDescending { it.id }
                } else {
                    compareBy<Message> { it.timestamp }.thenBy { it.id }
                }
            )
            .take(limit)

    /**
     * 批量插入消息 — 事务写入，减少 I/O 开销。
     */
    internal suspend fun batchInsertMessages(messages: List<ChatMessage>): List<Long> {
        if (messages.isEmpty()) return emptyList()
        val encrypted = ChatMessageCrypto.encryptForStorage(messages)
            .map { StoredMessage.fromChatMessage(it) }
        val persisted = database.withTransaction {
            val ids = messageDao.insertStoredMessages(encrypted)
            messages.zip(ids).map { (message, id) -> message.copy(id = id) }.also { inserted ->
                inserted.groupBy { it.companionId }.forEach { (companionId, grouped) ->
                    val lastMessage = grouped.maxWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
                    updateSummaryForChat(
                        companionId,
                        lastMessage,
                        incomingMessages = grouped,
                        updateCache = false
                    )
                }
            }
        }
        persisted.groupBy { it.companionId }.forEach { (companionId, inserted) ->
            inserted.forEach { MessageCache.appendChatMessage(companionId, it) }
            summaryDao.getSummarySync(companionId, "chat")?.let { putSummaryInCache(it) }
        }
        return persisted.map { it.id }
    }

    private suspend fun updateSummaryForChat(
        companionId: Long,
        message: ChatMessage,
        incomingMessages: List<ChatMessage> = listOf(message),
        updateCache: Boolean = true
    ) {
        val preview = message.content.take(100)
        val existing = summaryDao.getSummarySync(companionId, "chat")
        val unreadIncrement = incomingMessages.count {
            !it.isFromUser && isAfterReadCursor(it.timestamp, it.id, existing)
        }
        val advancesLatest = existing == null ||
            message.timestamp > existing.lastMessageTimestamp ||
            (message.timestamp == existing.lastMessageTimestamp && message.id > (existing.lastMessageId ?: 0L))
        val summary = ConversationSummary(
            sessionId = companionId,
            sessionType = "chat",
            lastMessageId = if (advancesLatest) message.id.takeIf { it > 0 } else existing.lastMessageId,
            lastMessagePreview = if (advancesLatest) preview else existing.lastMessagePreview,
            lastMessageTimestamp = if (advancesLatest) message.timestamp else existing.lastMessageTimestamp,
            lastMessageIsFromUser = if (advancesLatest) message.isFromUser else existing.lastMessageIsFromUser,
            readThroughMessageTimestamp = existing?.readThroughMessageTimestamp,
            readThroughMessageId = existing?.readThroughMessageId,
            unreadCount = (existing?.unreadCount ?: 0) + unreadIncrement,
            isPinned = existing?.isPinned ?: false,
            isMuted = existing?.isMuted ?: false
        )
        summaryDao.upsertSummary(summary)
        if (updateCache) putSummaryInCache(summary)
    }

    private fun isAfterReadCursor(timestamp: Long, id: Long, summary: ConversationSummary?): Boolean {
        val readTimestamp = summary?.readThroughMessageTimestamp ?: return true
        return timestamp > readTimestamp ||
            (timestamp == readTimestamp && id > (summary.readThroughMessageId ?: 0L))
    }

    private suspend fun rebuildSummaryForChat(companionId: Long): ConversationSummary? {
        val existing = summaryDao.getSummarySync(companionId, "chat")
        val latest = messageDao.getLastMessageSync(companionId, "chat")
        if (latest == null) {
            summaryDao.deleteSummary(companionId, "chat")
            return null
        }
        val message = fromMessage(latest)
        return ConversationSummary(
            sessionId = companionId,
            sessionType = "chat",
            lastMessageId = message.id,
            lastMessagePreview = message.content.take(100),
            lastMessageTimestamp = message.timestamp,
            lastMessageIsFromUser = message.isFromUser,
            readThroughMessageTimestamp = existing?.readThroughMessageTimestamp,
            readThroughMessageId = existing?.readThroughMessageId,
            unreadCount = messageDao.getUnreadMessageCount(
                companionId,
                "chat",
                existing?.readThroughMessageTimestamp,
                existing?.readThroughMessageId
            ),
            isPinned = existing?.isPinned ?: false,
            isMuted = existing?.isMuted ?: false
        ).also { summaryDao.upsertSummary(it) }
    }

    private fun putSummaryInCache(summary: ConversationSummary) {
        MessageCache.putChatSummary(summary.sessionId, MessageCache.SessionSummary(
            lastMessagePreview = summary.lastMessagePreview,
            lastMessageTimestamp = summary.lastMessageTimestamp,
            lastMessageIsFromUser = summary.lastMessageIsFromUser,
            unreadCount = summary.unreadCount
        ))
    }

    // ── 内部转换 ──

    private suspend fun fromMessage(message: StoredMessage): ChatMessage =
        ChatMessageCrypto.decryptFromStorage(message.toChatMessage())

    private suspend fun fromMessages(messages: List<StoredMessage>): List<ChatMessage> =
        ChatMessageCrypto.decryptFromStorage(messages.map { it.toChatMessage() })
}
