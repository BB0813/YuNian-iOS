package com.lianyu.ai.database.repository

import androidx.room.withTransaction
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.cache.MessageCache
import com.lianyu.ai.database.dao.ChatMessageDao
import com.lianyu.ai.database.dao.ConversationSummaryDao
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.ConversationSummary
import com.lianyu.ai.database.model.FileFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * 聊天消息仓库 — 三级存储架构集成层。
 *
 * L1: MessageCache (LruCache 内存缓存) — 进入会话时先读缓存，避免 loading 闪烁
 * L2: Room SQLite (WAL 模式 + 复合索引) — 持久化存储
 * L3: 文件系统 — 图片/视频等大文件，SQLite 仅存路径
 *
 * 写入路径: sendMessage → 加密 → DAO insert → 更新 L1 缓存 → 更新会话摘要
 * 读取路径: getMessagesForCompanion → L1 缓存命中? → 否则 L2 查询 → 解密 → 回填 L1
 */
class ChatRepository(
    private val chatMessageDao: ChatMessageDao,
    private val summaryDao: ConversationSummaryDao,
    private val database: AppDatabase
) {

    /**
     * 缓存预热：由 HomeViewModel 在加载列表时调用。
     * 将解密后的消息列表写入 L1 缓存。
     */
    fun warmCache(companionId: Long, messages: List<ChatMessage>) {
        MessageCache.putChatMessages(companionId, messages)
    }

    /**
     * 读取 L1 缓存（可能为 null），ChatViewModel 进入时先用它做初始数据。
     */
    fun getCachedRecent(companionId: Long): List<ChatMessage>? =
        MessageCache.getChatMessages(companionId)

    // --- 获取最近一页消息，按时间正序排列（UI直接显示） ---
    fun getMessagesForCompanion(companionId: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        chatMessageDao.getRecentMessagesForCompanion(companionId, limit)
            .map { list -> list.map { ChatMessageCrypto.decryptFromStorage(it) }.reversed() }
            .onEach { decrypted -> MessageCache.putChatMessages(companionId, decrypted) }

    fun getMessagesBefore(companionId: Long, beforeTimestamp: Long, beforeId: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        chatMessageDao.getMessagesBefore(companionId, beforeTimestamp, beforeId, limit)
            .map { list -> list.map { ChatMessageCrypto.decryptFromStorage(it) }.reversed() }

    fun getLastMessageForCompanion(companionId: Long): Flow<ChatMessage?> =
        chatMessageDao.getLastMessageForCompanion(companionId)
            .map { it?.let(ChatMessageCrypto::decryptFromStorage) }

    /**
     * 发送消息 — 写入 L2 + 更新 L1 缓存 + 更新会话摘要。
     */
    suspend fun sendMessage(message: ChatMessage): Long {
        val encrypted = ChatMessageCrypto.encryptForStorage(message)
        val id = chatMessageDao.insertMessage(encrypted)
        // 回填 L1 缓存
        MessageCache.appendChatMessage(message.companionId, message.copy(id = id))
        // 更新会话摘要
        updateSummaryForChat(message.companionId, message)
        return id
    }

    suspend fun deleteMessage(message: ChatMessage) {
        chatMessageDao.deleteMessage(message)
        MessageCache.removeChatMessage(message.companionId, message.id)
    }

    suspend fun clearChatHistory(companionId: Long) {
        chatMessageDao.deleteMessagesForCompanion(companionId)
        MessageCache.evictChat(companionId)
        summaryDao.deleteSummary(companionId, "chat")
    }

    suspend fun getAiMessageCount(companionId: Long): Int = chatMessageDao.getAiMessageCount(companionId)

    suspend fun getRecentMessagesSync(companionId: Long, limit: Int): List<ChatMessage> =
        chatMessageDao.getRecentMessagesSync(companionId, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }
            .reversed()
            .also { MessageCache.putChatMessages(companionId, it) }

    suspend fun getMessagesBeforeSync(companionId: Long, beforeTimestamp: Long, beforeId: Long, limit: Int): List<ChatMessage> =
        chatMessageDao.getMessagesBeforeSync(companionId, beforeTimestamp, beforeId, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun getMessageById(messageId: Long): ChatMessage? =
        chatMessageDao.getMessageById(messageId)?.let { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun getMessagesForCompanionSync(companionId: Long): List<ChatMessage> =
        chatMessageDao.getMessagesForCompanionSync(companionId)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun searchMessages(companionId: Long, query: String, limit: Int = 50): List<ChatMessage> =
        chatMessageDao.searchMessages(companionId, query, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun getMessagesByFileFormat(companionId: Long, fileFormat: FileFormat, limit: Int = 50): List<ChatMessage> =
        chatMessageDao.getMessagesByFileFormat(companionId, fileFormat, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun getMessagesByFileFormatBefore(
        companionId: Long,
        fileFormat: FileFormat,
        beforeTimestamp: Long,
        limit: Int = 50
    ): List<ChatMessage> =
        chatMessageDao.getMessagesByFileFormatBefore(companionId, fileFormat, beforeTimestamp, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun sendMessageAndGetId(message: ChatMessage): Long {
        val encrypted = ChatMessageCrypto.encryptForStorage(message)
        val id = chatMessageDao.insertMessage(encrypted)
        MessageCache.appendChatMessage(message.companionId, message.copy(id = id))
        updateSummaryForChat(message.companionId, message)
        return id
    }

    suspend fun updateMessageContent(messageId: Long, content: String) {
        // [C3 FIX] 必须加密 content 列：sendMessage / sendMessageAndGetId 都会经过 ChatMessageCrypto.encryptForStorage，
        // 但此处的直接 UPDATE 之前传明文，导致 content 列在流式更新路径上绕过加密层。
        // searchContent 保持明文以支持 LIKE 查询（与 encryptForStorage 中 searchContent 的处理一致）。
        chatMessageDao.updateMessageContent(messageId, ChatMessageCrypto.encrypt(content), content)
        // 同步更新 L1 缓存中的消息内容
        val updated = chatMessageDao.getMessageById(messageId)
        if (updated != null) {
            val decrypted = ChatMessageCrypto.decryptFromStorage(updated)
            MessageCache.updateChatMessage(decrypted.companionId, messageId) { decrypted }
        }
    }

    /**
     * 批量插入消息 — 事务写入，减少 I/O 开销。
     * 用于消息同步、批量恢复等场景。
     */
    suspend fun batchInsertMessages(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val encrypted = messages.map { ChatMessageCrypto.encryptForStorage(it) }
        val ids = database.withTransaction {
            chatMessageDao.insertMessages(encrypted)
        }
        // 回填 L1 缓存
        messages.groupBy { it.companionId }.forEach { (companionId, msgs) ->
            msgs.zip(ids).forEach { (msg, id) ->
                MessageCache.appendChatMessage(companionId, msg.copy(id = id))
            }
        }
        // 更新最后一条消息的摘要
        messages.groupBy { it.companionId }.forEach { (companionId, msgs) ->
            val lastMsg = msgs.maxByOrNull { it.timestamp }
            if (lastMsg != null) updateSummaryForChat(companionId, lastMsg)
        }
    }

    /**
     * 更新会话摘要 — 发送/接收消息时调用。
     */
    private suspend fun updateSummaryForChat(companionId: Long, message: ChatMessage) {
        val preview = message.content.take(100)
        val existing = summaryDao.getSummarySync(companionId, "chat")
        val summary = ConversationSummary(
            sessionId = companionId,
            sessionType = "chat",
            lastMessagePreview = preview,
            lastMessageTimestamp = message.timestamp,
            lastMessageIsFromUser = message.isFromUser,
            unreadCount = existing?.unreadCount ?: 0,
            isPinned = existing?.isPinned ?: false,
            isMuted = existing?.isMuted ?: false
        )
        summaryDao.upsertSummary(summary)
        // 同步更新 L1 摘要缓存
        MessageCache.putChatSummary(companionId, MessageCache.SessionSummary(
            lastMessagePreview = preview,
            lastMessageTimestamp = message.timestamp,
            lastMessageIsFromUser = message.isFromUser,
            unreadCount = existing?.unreadCount ?: 0
        ))
    }
}
