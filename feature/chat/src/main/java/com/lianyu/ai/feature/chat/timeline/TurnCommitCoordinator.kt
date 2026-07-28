package com.lianyu.ai.feature.chat.timeline

import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.database.repository.MessageWriteCoordinator
import com.lianyu.ai.domain.timeline.ConversationRef
import com.lianyu.ai.domain.timeline.TimelineEvent
import com.lianyu.ai.domain.timeline.TimelineEventKind
import com.lianyu.ai.domain.timeline.TimelineStore

/**
 * 回合终态提交协调器（薄编排，无业务分支膨胀）。
 *
 * - REASONING → [TimelineStore.appendComplete]（不污染会话摘要）
 * - ASSISTANT_TEXT → [MessageWriteCoordinator]（保留 lastMessage / 缓存路径）
 * - 两者共享 [PendingTurn] 的 turnId / eventIndex
 */
class TurnCommitCoordinator(
    private val timelineStore: TimelineStore,
    private val messageWriter: MessageWriteCoordinator,
) {

    suspend fun commitReasoning(scope: ConversationRef, event: TimelineEvent): Long {
        require(event.kind == TimelineEventKind.REASONING) {
            "commitReasoning expects REASONING, got ${event.kind}"
        }
        return timelineStore.appendComplete(scope, event).also { id ->
            SecureLog.d(TAG, "REASONING committed id=$id turn=${event.turnId} idx=${event.eventIndex}")
        }
    }

    /**
     * 将助手正文写入聊天消息表，并附带 turn 元数据。
     * @return 持久化 messageId
     */
    suspend fun commitAssistantText(
        companionId: Long,
        text: String,
        turn: PendingTurn,
        timestamp: Long = System.currentTimeMillis(),
    ): Long {
        val event = turn.nextAssistantTextEvent(text = text, timestamp = timestamp)
        val message = ChatMessage(
            companionId = companionId,
            content = text,
            isFromUser = false,
            timestamp = event.timestamp,
            type = MessageType.TEXT,
            turnId = event.turnId.value,
            eventIndex = event.eventIndex,
            anchorMessageId = event.anchorMessageId,
        )
        return messageWriter.enqueueChat(message).also { id ->
            SecureLog.d(TAG, "ASSISTANT_TEXT committed id=$id turn=${event.turnId} idx=${event.eventIndex}")
        }
    }

    /**
     * 无 PendingTurn 时的兼容路径（旧调用方 / 追问等）。
     */
    suspend fun commitPlainAssistantText(
        companionId: Long,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
    ): Long {
        val message = ChatMessage(
            companionId = companionId,
            content = text,
            isFromUser = false,
            timestamp = timestamp,
            type = MessageType.TEXT,
        )
        return messageWriter.enqueueChat(message)
    }

    companion object {
        private const val TAG = "TurnCommitCoordinator"
    }
}
