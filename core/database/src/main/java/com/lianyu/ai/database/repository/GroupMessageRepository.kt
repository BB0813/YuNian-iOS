package com.lianyu.ai.database.repository

import com.lianyu.ai.database.cache.MessageCache
import com.lianyu.ai.database.dao.ConversationSummaryDao
import com.lianyu.ai.database.dao.GroupMessageDao
import com.lianyu.ai.database.model.ConversationSummary
import com.lianyu.ai.database.model.FileFormat
import com.lianyu.ai.database.model.GroupMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * 群聊消息仓库 — 三级存储架构集成层。
 *
 * L1: MessageCache (LruCache 内存缓存) — 进入群聊时先读缓存
 * L2: Room SQLite (WAL 模式 + 复合索引) — 持久化存储
 * L3: 文件系统 — 图片/视频等大文件
 */
class GroupMessageRepository(
    private val groupMessageDao: GroupMessageDao,
    private val summaryDao: ConversationSummaryDao
) {

    fun warmCache(groupId: Long, messages: List<GroupMessage>) {
        MessageCache.putGroupMessages(groupId, messages)
    }

    fun getCachedRecent(groupId: Long): List<GroupMessage>? =
        MessageCache.getGroupMessages(groupId)

    // --- 获取最近的消息，按时间正序排列（UI直接显示） ---
    fun getMessagesForGroup(groupId: Long, limit: Int = 50): Flow<List<GroupMessage>> =
        groupMessageDao.getRecentMessagesForGroup(groupId, limit)
            .map { list -> list.map { ChatMessageCrypto.decryptFromStorage(it) }.reversed() }
            .onEach { decrypted -> MessageCache.putGroupMessages(groupId, decrypted) }

    fun getMessagesBefore(groupId: Long, beforeTimestamp: Long, limit: Int = 30): Flow<List<GroupMessage>> =
        groupMessageDao.getMessagesBefore(groupId, beforeTimestamp, limit)
            .map { list -> list.map { ChatMessageCrypto.decryptFromStorage(it) }.reversed() }

    fun getLastMessageForGroup(groupId: Long): Flow<GroupMessage?> =
        groupMessageDao.getLastMessageForGroup(groupId)
            .map { it?.let(ChatMessageCrypto::decryptFromStorage) }

    suspend fun sendMessage(message: GroupMessage): Long {
        val encrypted = ChatMessageCrypto.encryptForStorage(message)
        val id = groupMessageDao.insertMessage(encrypted)
        MessageCache.appendGroupMessage(message.groupId, message.copy(id = id))
        updateSummaryForGroup(message.groupId, message)
        return id
    }

    suspend fun deleteMessage(message: GroupMessage) {
        groupMessageDao.deleteMessage(message)
        MessageCache.removeGroupMessage(message.groupId, message.id)
    }

    suspend fun clearGroupHistory(groupId: Long) {
        groupMessageDao.deleteMessagesForGroup(groupId)
        MessageCache.evictGroup(groupId)
        summaryDao.deleteSummary(groupId, "group")
    }

    suspend fun getMessagesBeforeSync(groupId: Long, beforeTimestamp: Long, limit: Int): List<GroupMessage> =
        groupMessageDao.getMessagesBeforeSync(groupId, beforeTimestamp, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun searchMessages(groupId: Long, query: String, limit: Int = 50): List<GroupMessage> =
        groupMessageDao.searchMessages(groupId, query, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun getMessagesByFileFormat(groupId: Long, fileFormat: FileFormat, limit: Int = 50): List<GroupMessage> =
        groupMessageDao.getMessagesByFileFormat(groupId, fileFormat, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun getMessagesByFileFormatBefore(
        groupId: Long,
        fileFormat: FileFormat,
        beforeTimestamp: Long,
        limit: Int = 50
    ): List<GroupMessage> =
        groupMessageDao.getMessagesByFileFormatBefore(groupId, fileFormat, beforeTimestamp, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun getMessageCount(groupId: Long): Int =
        groupMessageDao.getMessageCount(groupId)

    /**
     * 批量插入消息 — 事务写入，减少 I/O 开销。
     */
    suspend fun batchInsertMessages(messages: List<GroupMessage>) {
        if (messages.isEmpty()) return
        val encrypted = messages.map { ChatMessageCrypto.encryptForStorage(it) }
        val ids = groupMessageDao.insertMessages(encrypted)
        messages.groupBy { it.groupId }.forEach { (groupId, msgs) ->
            msgs.zip(ids).forEach { (msg, id) ->
                MessageCache.appendGroupMessage(groupId, msg.copy(id = id))
            }
        }
        messages.groupBy { it.groupId }.forEach { (groupId, msgs) ->
            val lastMsg = msgs.maxByOrNull { it.timestamp }
            if (lastMsg != null) updateSummaryForGroup(groupId, lastMsg)
        }
    }

    /**
     * 更新会话摘要 — 发送/接收消息时调用。
     */
    private suspend fun updateSummaryForGroup(groupId: Long, message: GroupMessage) {
        val preview = message.content.take(100)
        val isFromUser = message.companionId == -1L
        val existing = summaryDao.getSummarySync(groupId, "group")
        val summary = ConversationSummary(
            sessionId = groupId,
            sessionType = "group",
            lastMessagePreview = preview,
            lastMessageTimestamp = message.timestamp,
            lastMessageIsFromUser = isFromUser,
            unreadCount = existing?.unreadCount ?: 0,
            isPinned = existing?.isPinned ?: false,
            isMuted = existing?.isMuted ?: false
        )
        summaryDao.upsertSummary(summary)
        MessageCache.putGroupSummary(groupId, MessageCache.SessionSummary(
            lastMessagePreview = preview,
            lastMessageTimestamp = message.timestamp,
            lastMessageIsFromUser = isFromUser,
            unreadCount = existing?.unreadCount ?: 0
        ))
    }
}
