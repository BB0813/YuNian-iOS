package com.lianyu.ai.database.repository

import com.lianyu.ai.database.dao.ChatMessageDao
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.FileFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class ChatRepository(private val chatMessageDao: ChatMessageDao) {

    /**
     * 内存缓存：companionId -> 最近一页解密后的消息。
     * HomeViewModel 加载列表时预热，ChatViewModel 进入时先读缓存避免 loading。
     * 放在 companion object 中，所有 ChatRepository 实例共享同一份缓存。
     */
    companion object {
        private val recentCache = mutableMapOf<Long, List<ChatMessage>>()
    }

    /** 缓存预热：由 HomeViewModel 在加载列表时调用 */
    fun warmCache(companionId: Long, messages: List<ChatMessage>) {
        recentCache[companionId] = messages
    }

    /** 读取缓存（可能为 null），ChatViewModel 进入时先用它做初始数据 */
    fun getCachedRecent(companionId: Long): List<ChatMessage>? = recentCache[companionId]

    // --- 获取最近一页消息，按时间正序排列（UI直接显示） ---
    fun getMessagesForCompanion(companionId: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        chatMessageDao.getRecentMessagesForCompanion(companionId, limit)
            .map { list -> list.map { ChatMessageCrypto.decryptFromStorage(it) }.reversed() }
            .onEach { decrypted -> recentCache[companionId] = decrypted }

    fun getMessagesBefore(companionId: Long, beforeTimestamp: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        chatMessageDao.getMessagesBefore(companionId, beforeTimestamp, limit)
            .map { list -> list.map { ChatMessageCrypto.decryptFromStorage(it) }.reversed() }

    fun getLastMessageForCompanion(companionId: Long): Flow<ChatMessage?> =
        chatMessageDao.getLastMessageForCompanion(companionId)
            .map { it?.let(ChatMessageCrypto::decryptFromStorage) }

    suspend fun sendMessage(message: ChatMessage): Long =
        chatMessageDao.insertMessage(ChatMessageCrypto.encryptForStorage(message))

    suspend fun deleteMessage(message: ChatMessage) = chatMessageDao.deleteMessage(message)

    suspend fun clearChatHistory(companionId: Long) {
        chatMessageDao.deleteMessagesForCompanion(companionId)
        recentCache.remove(companionId)
    }

    suspend fun getAiMessageCount(companionId: Long): Int = chatMessageDao.getAiMessageCount(companionId)

    suspend fun getRecentMessagesSync(companionId: Long, limit: Int): List<ChatMessage> =
        chatMessageDao.getRecentMessagesSync(companionId, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }
            .also { recentCache[companionId] = it.reversed() }

    suspend fun getMessagesBeforeSync(companionId: Long, beforeTimestamp: Long, limit: Int): List<ChatMessage> =
        chatMessageDao.getMessagesBeforeSync(companionId, beforeTimestamp, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

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
        return chatMessageDao.insertMessage(ChatMessageCrypto.encryptForStorage(message))
    }

    suspend fun updateMessageContent(messageId: Long, content: String) {
        chatMessageDao.updateMessageContent(messageId, content)
    }
}
