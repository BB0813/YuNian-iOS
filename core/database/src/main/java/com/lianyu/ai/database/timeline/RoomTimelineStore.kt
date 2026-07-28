package com.lianyu.ai.database.timeline

import androidx.room.withTransaction
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.cache.MessageCache
import com.lianyu.ai.database.dao.MessageDao
import com.lianyu.ai.database.model.MessageBody
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.database.model.StoredMessage
import com.lianyu.ai.database.repository.ChatMessageCrypto
import com.lianyu.ai.domain.timeline.ConversationRef
import com.lianyu.ai.domain.timeline.TimelineEvent
import com.lianyu.ai.domain.timeline.TimelineEventKind
import com.lianyu.ai.domain.timeline.TimelineEventStatus
import com.lianyu.ai.domain.timeline.TimelineStore
import com.lianyu.ai.domain.timeline.TurnId

/**
 * [TimelineStore] 的 Room 实现。
 *
 * 与 [com.lianyu.ai.database.repository.ChatRepository] 并存：
 * - 不更新会话摘要（思考过程不应顶掉 lastMessage 预览）
 * - REASONING 写入 MessageCache 供正文预填，但不改 summary
 * - STREAMING 拒绝落库
 */
class RoomTimelineStore(
    private val messageDao: MessageDao,
    private val database: AppDatabase,
) : TimelineStore {

    override suspend fun appendComplete(scope: ConversationRef, event: TimelineEvent): Long {
        require(event.status != TimelineEventStatus.STREAMING) {
            "STREAMING timeline events must not be persisted"
        }
        require(event.status == TimelineEventStatus.COMPLETE ||
            event.status == TimelineEventStatus.FAILED ||
            event.status == TimelineEventStatus.CANCELLED
        ) {
            "Only terminal statuses may be appended"
        }

        val plaintext = TimelineMessageMapper.serializePayload(event.payload)
        val shell = TimelineMessageMapper.toChatMessageShell(scope, event, plaintext)
        val encrypted = ChatMessageCrypto.encryptForStorage(shell)
        val metadata = TimelineMessageMapper.toMetadata(scope, event, messageId = 0L)
        // encryptForStorage 在 searchContent 为空时会回填明文 content；REASONING 必须保持空索引
        val searchContent = if (event.kind == TimelineEventKind.REASONING) {
            ""
        } else {
            encrypted.searchContent
        }
        val body = MessageBody(
            messageId = 0L,
            content = encrypted.content,
            searchContent = searchContent,
            linkString = encrypted.linkString,
        )

        val id = database.withTransaction {
            messageDao.insertStoredMessage(metadata, body)
        }
        // 单聊：缓存明文壳，避免 UI BodyLoading 闪烁；群聊后续切片再接
        if (scope.conversationType == "chat" && id > 0L) {
            MessageCache.appendChatMessage(scope.conversationId, shell.copy(id = id))
        }
        return id
    }

    override suspend fun loadTurn(turnId: TurnId): List<TimelineEvent> {
        val hot = messageDao.getMessagesByTurnId(turnId.value)
        val coldMeta = messageDao.getArchivedMessageMetadataByTurnId(turnId.value)
        val cold = attachArchivedBodies(coldMeta)
        return decryptAndMap(hot + cold)
            .sortedWith(compareBy<TimelineEvent> { it.eventIndex }.thenBy { it.eventId?.value ?: 0L })
    }

    override suspend fun loadConversationEvents(
        conversationId: Long,
        conversationType: String,
        limit: Int,
        kinds: Set<TimelineEventKind>?,
    ): List<TimelineEvent> {
        require(limit > 0) { "limit must be > 0" }
        val types = (kinds ?: TimelineEventKind.entries.toSet())
            .map { TimelineMessageMapper.toMessageType(it) }
            .distinct()
        if (types.isEmpty()) return emptyList()

        val hot = messageDao.getRecentMessagesByTypes(
            conversationId, conversationType, types, limit
        )
        val coldMeta = messageDao.getRecentArchivedMessageMetadataByTypes(
            conversationId, conversationType, types, limit
        )
        val cold = attachArchivedBodies(coldMeta)
        // 合并后按时间倒序截断，再映射；最终返回 eventIndex/时间升序便于 UI
        val merged = (hot + cold)
            .sortedWith(
                compareByDescending<StoredMessage> { it.metadata.timestamp }
                    .thenByDescending { it.metadata.id }
            )
            .take(limit)

        return decryptAndMap(merged)
            .sortedWith(
                compareBy<TimelineEvent> { it.timestamp }
                    .thenBy { it.eventIndex }
                    .thenBy { it.eventId?.value ?: 0L }
            )
    }

    private suspend fun attachArchivedBodies(metadata: List<com.lianyu.ai.database.model.Message>): List<StoredMessage> {
        if (metadata.isEmpty()) return emptyList()
        val bodies = messageDao.getArchivedMessageBodies(metadata.map { it.id })
            .associateBy { it.messageId }
        return metadata.mapNotNull { meta ->
            val body = bodies[meta.id] ?: return@mapNotNull null
            StoredMessage(meta, body)
        }
    }

    private suspend fun decryptAndMap(rows: List<StoredMessage>): List<TimelineEvent> {
        if (rows.isEmpty()) return emptyList()
        val shells = rows.map { it.toChatMessage() }
        val decrypted = ChatMessageCrypto.decryptFromStorage(shells)
        return rows.zip(decrypted).mapNotNull { (stored, plain) ->
            // 无 turnId 的普通历史消息不进入时间线投影
            if (stored.metadata.turnId.isNullOrBlank()) return@mapNotNull null
            // kind 过滤：REASONING 行 type 已区分；TEXT 行仅当有 turnId 视为 ASSISTANT_TEXT 事件
            if (stored.metadata.type != MessageType.REASONING &&
                stored.metadata.type != MessageType.TEXT
            ) {
                return@mapNotNull null
            }
            TimelineMessageMapper.fromStored(stored, plain.content)
        }
    }
}
