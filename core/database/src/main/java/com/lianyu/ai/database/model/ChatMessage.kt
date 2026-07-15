package com.lianyu.ai.database.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: Long = 0,
    val companionId: Long,
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.TEXT,
    val searchContent: String = "",
    val fileFormat: FileFormat = FileFormat.TEXT,
    val linkString: String = ""
) {
    val role: String get() = if (isFromUser) "user" else "assistant"
    val isFromAssistant: Boolean get() = !isFromUser
    val isSystem: Boolean get() = false
}
