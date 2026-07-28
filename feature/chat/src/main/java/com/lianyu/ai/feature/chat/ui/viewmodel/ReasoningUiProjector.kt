package com.lianyu.ai.feature.chat.ui.viewmodel

import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.domain.timeline.ReasoningDurationFormatter

/**
 * 将 REASONING 消息投影为列表项（纯函数，无 Compose / I/O）。
 *
 * 历史与流式均走消息链路：Room 终态 / L1 临时负 id → [ChatListItem.ReasoningMessage]。
 * 不再使用 ephemeral StateFlow 旁路。
 */
object ReasoningUiProjector {

    fun project(message: ChatMessage): ChatListItem.ReasoningMessage? {
        if (message.type != MessageType.REASONING) return null
        return ChatListItem.ReasoningMessage(
            message = message,
            durationMs = message.durationMs,
            isStreaming = message.id < 0L,
        )
    }

    fun collapsedLabel(message: ChatMessage): String =
        collapsedLabel(durationMs = message.durationMs, text = message.content)

    fun collapsedLabel(durationMs: Long?, text: String): String =
        ReasoningDurationFormatter.collapsedLabel(
            durationMs = durationMs,
            hasText = text.isNotBlank() || durationMs != null,
        )

    fun streamingLabel(): String = ReasoningDurationFormatter.STREAMING_LABEL
}
