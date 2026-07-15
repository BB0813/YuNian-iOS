package com.lianyu.ai.database.model

import androidx.room.Entity
import androidx.room.Index

/**
 * 会话摘要表 — 三级存储架构 L2 层。
 *
 * 缓存每个会话（单聊/群聊）的最后一条消息摘要，避免联查消息表。
 * 首页列表展示「最后消息预览」时直接读此表，O(1) 查询。
 *
 * @param sessionId 单聊 = companionId，群聊 = groupId
 * @param sessionType "chat" 或 "group"
 */
@Entity(
    tableName = "conversation_summary",
    primaryKeys = ["sessionId", "sessionType"],
    indices = [
        Index(value = ["sessionType", "lastMessageTimestamp"], name = "idx_summary_type_time")
    ]
)
data class ConversationSummary(
    val sessionId: Long,
    val sessionType: String,
    val lastMessageId: Long? = null,
    val lastMessagePreview: String,
    val lastMessageTimestamp: Long,
    val lastMessageIsFromUser: Boolean,
    val readThroughMessageTimestamp: Long? = null,
    val readThroughMessageId: Long? = null,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false
)
