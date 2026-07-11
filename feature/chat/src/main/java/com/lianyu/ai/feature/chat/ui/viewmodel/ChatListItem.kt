package com.lianyu.ai.feature.chat.ui.viewmodel

import androidx.compose.runtime.Stable
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.MessageType

@Stable
sealed interface ChatListItem {
    val stableId: String
    val messageOrNull: ChatMessage?

    data class TextMessage(val message: ChatMessage) : ChatListItem {
        override val messageOrNull: ChatMessage = message
        override val stableId: String = message.stableMessageKey("text")
    }

    data class ImageMessage(val message: ChatMessage) : ChatListItem {
        override val messageOrNull: ChatMessage = message
        override val stableId: String = message.stableMessageKey("image")
    }

    data class VoiceMessage(val message: ChatMessage) : ChatListItem {
        override val messageOrNull: ChatMessage = message
        override val stableId: String = message.stableMessageKey("voice")
    }

    data class VideoMessage(val message: ChatMessage) : ChatListItem {
        override val messageOrNull: ChatMessage = message
        override val stableId: String = message.stableMessageKey("video")
    }

    data class FileMessage(val message: ChatMessage) : ChatListItem {
        override val messageOrNull: ChatMessage = message
        override val stableId: String = message.stableMessageKey("file")
    }

    data class StickerMessage(val message: ChatMessage, val stickerName: String) : ChatListItem {
        override val messageOrNull: ChatMessage = message
        override val stableId: String = message.stableMessageKey("sticker")
    }

    data class TimeDivider(val timestamp: Long) : ChatListItem {
        override val messageOrNull: ChatMessage? = null
        override val stableId: String = "time-divider-$timestamp"
    }

    data class SystemTip(
        override val stableId: String,
        val content: String,
        val timestamp: Long? = null
    ) : ChatListItem {
        override val messageOrNull: ChatMessage? = null
    }
}

private const val TIME_DIVIDER_INTERVAL_MILLIS = 5 * 60 * 1000L

internal fun List<ChatMessage>.toChatListItems(): List<ChatListItem> {
    val items = mutableListOf<ChatListItem>()
    var previousVisibleTimestamp: Long? = null

    for (message in this) {
        if (message.content.isBlank() && message.type != MessageType.IMAGE) continue

        val previousTimestamp = previousVisibleTimestamp
        if (previousTimestamp == null || message.timestamp - previousTimestamp >= TIME_DIVIDER_INTERVAL_MILLIS) {
            items += ChatListItem.TimeDivider(message.timestamp)
        }

        items += message.toSystemTipOrNull() ?: message.toChatListItem()
        previousVisibleTimestamp = message.timestamp
    }

    return items
}

private fun ChatMessage.toChatListItem(): ChatListItem {
    val stickerName = stickerNameOrNull()
    return when {
        stickerName != null -> ChatListItem.StickerMessage(this, stickerName)
        type == MessageType.IMAGE -> ChatListItem.ImageMessage(this)
        type == MessageType.VOICE || content.startsWith("[语音]") -> ChatListItem.VoiceMessage(this)
        type == MessageType.VIDEO -> ChatListItem.VideoMessage(this)
        type == MessageType.FILE -> ChatListItem.FileMessage(this)
        else -> ChatListItem.TextMessage(this)
    }
}

private fun ChatMessage.toSystemTipOrNull(): ChatListItem.SystemTip? {
    if (type != MessageType.TEXT) return null
    val tipContent = content.trim()
    if (!tipContent.isSystemTipContent()) return null

    return ChatListItem.SystemTip(
        stableId = stableMessageKey("system-tip"),
        content = tipContent,
        timestamp = timestamp
    )
}

private fun ChatMessage.stableMessageKey(kind: String): String {
    return if (id > 0) "$kind-$id" else "$kind-local-$timestamp-${content.hashCode()}"
}

private fun ChatMessage.stickerNameOrNull(): String? {
    val systemTags = setOf("语音", "图片", "视频", "文件", "位置", "红包", "转账")
    if (!content.startsWith("[") || !content.endsWith("]")) return null
    val name = content.removeSurrounding("[", "]")
    return name.takeIf { it.isNotBlank() && it !in systemTags }
}

private fun String.isSystemTipContent(): Boolean {
    if (isBlank() || length > 80) return false

    return contains("加入群聊") ||
        contains("退出群聊") ||
        contains("移出群聊") ||
        contains("已被撤回") ||
        contains("消息已撤回") ||
        contains("撤回了一条消息")
}
