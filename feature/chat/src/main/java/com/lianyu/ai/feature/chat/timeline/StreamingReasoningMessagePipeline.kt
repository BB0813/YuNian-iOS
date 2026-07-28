package com.lianyu.ai.feature.chat.timeline

import com.lianyu.ai.database.cache.MessageCache
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.FileFormat
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.domain.timeline.TurnId

/**
 * 流式 REASONING 消息管道（仅 L1 MessageCache，不落库）。
 *
 * 与终态 [TurnCommitCoordinator.commitReasoning] 衔接：
 * - 流式阶段：稳定负 id 写入/更新缓存，走与普通消息相同的 cache → UI 观察链路
 * - 终态落库后：移除临时行，由 Room 真实 REASONING 行接管
 *
 * 硬约束：STREAMING 永不写 DB（见 TimelineArchitecture）。
 */
object StreamingReasoningMessagePipeline {

    /** 流式临时消息 id 均为负；Room autoGenerate 为正。 */
    fun isStreamingMessageId(messageId: Long): Boolean = messageId < 0L

    /**
     * 由 turnId 派生稳定负 id，保证同轮 delta 更新同一列表项（LazyColumn key 稳定）。
     */
    fun streamingMessageId(turnId: TurnId): Long {
        val h = turnId.value.hashCode().toLong() and 0x7fff_ffffL
        return -(1_000_000_000L + h)
    }

    fun streamingMessageId(turnId: String): Long =
        streamingMessageId(TurnId(turnId))

    /**
     * 将思考快照投影为独立 REASONING 消息并写入 MessageCache。
     * @return 写入的临时消息；文本空白时返回 null 且不写缓存
     */
    fun upsertStreaming(
        companionId: Long,
        turnId: TurnId,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
        eventIndex: Int? = null,
        anchorMessageId: Long? = null,
    ): ChatMessage? {
        val content = text.trim()
        if (content.isEmpty()) return null
        val id = streamingMessageId(turnId)
        // 同 turn 更新时保留首次时间戳，避免 metadata 排序/TimeDivider 抖动
        val existing = MessageCache.getChatMessages(companionId)?.firstOrNull { it.id == id }
        val stableTs = existing?.timestamp ?: timestamp
        val message = ChatMessage(
            id = id,
            companionId = companionId,
            content = content,
            isFromUser = false,
            timestamp = stableTs,
            type = MessageType.REASONING,
            searchContent = "",
            fileFormat = FileFormat.TEXT,
            linkString = "",
            turnId = turnId.value,
            eventIndex = eventIndex,
            durationMs = null,
            anchorMessageId = anchorMessageId,
        )
        // append 对同 id 会替换，形成流式更新
        MessageCache.appendChatMessage(companionId, message)
        // 缓存被 LRU 淘汰时 append 为空操作：强制 put 保证当前会话可见
        val cached = MessageCache.getChatMessages(companionId)
        if (cached == null || cached.none { it.id == id }) {
            val seed = (cached.orEmpty() + message).distinctBy { it.id }
            MessageCache.putChatMessages(companionId, seed)
        }
        return message
    }

    fun removeStreaming(companionId: Long, turnId: TurnId) {
        MessageCache.removeChatMessage(companionId, streamingMessageId(turnId))
    }

    fun removeStreaming(companionId: Long, turnId: String) {
        removeStreaming(companionId, TurnId(turnId))
    }

    /** 清除该会话下全部流式临时 REASONING（新回合开始 / 失败兜底）。 */
    fun clearAllStreaming(companionId: Long) {
        val cached = MessageCache.getChatMessages(companionId) ?: return
        cached.filter { isStreamingMessageId(it.id) && it.type == MessageType.REASONING }
            .forEach { MessageCache.removeChatMessage(companionId, it.id) }
    }
}
