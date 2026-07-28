package com.lianyu.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 统一消息实体 — 单表存储所有单聊和群聊消息。
 *
 * 设计原则：
 * - [conversationId] 区分会话（单聊=companionId, 群聊=groupId）
 * - [conversationType] 区分类型（"chat" / "group"）
 * - [isFromUser] 方向标识（true=用户, false=AI），兼容 ChatMessage API
 * - [senderId] 群聊中具体发言人（-1=用户, N=某位 AI 伴侣），单聊中为 0
 * - 唯一消息索引: (conversationType, conversationId, timestamp DESC, id DESC)
 *
 * 从 ChatMessage + GroupMessage 合并而来 (v27)。
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(
            value = ["conversationType", "conversationId", "timestamp", "id"],
            name = "idx_messages_conv",
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC, Index.Order.DESC]
        ),
        Index(
            value = ["turnId", "eventIndex", "id"],
            name = "idx_messages_turn",
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.ASC]
        )
    ]
)
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 会话ID：单聊=companionId, 群聊=groupId */
    val conversationId: Long,

    /** "chat" 或 "group" */
    val conversationType: String,

    /** 方向：true=用户发送, false=AI 发送 */
    val isFromUser: Boolean = true,

    /** 群聊中具体发言人（-1=用户, N=AI 伴侣 ID），单聊中为 0 */
    val senderId: Long = 0,

    /** 毫秒时间戳，用作游标分页锚点 */
    val timestamp: Long = System.currentTimeMillis(),

    /** 消息类型 */
    val type: MessageType = MessageType.TEXT,

    /** 文件分类 */
    val fileFormat: FileFormat = FileFormat.TEXT,

    /**
     * 助手回合 id（时间线事件）；普通用户消息为 null。
     * 与 [eventIndex] 一起保证同一 turn 内稳定顺序。
     */
    val turnId: String? = null,

    /** 同一 turn 内事件序号，从 0 起；非时间线消息为 null */
    val eventIndex: Int? = null,

    /** 思考过程耗时（毫秒）；仅 REASONING 使用 */
    val durationMs: Long? = null,

    /** 锚定用户消息 id（可选） */
    val anchorMessageId: Long? = null,
)

// ── 向后兼容扩展属性（兼容 ChatMessage / GroupMessage API） ──

/** 兼容 ChatMessage.companionId：单聊返回 conversationId，群聊返回 senderId */
val Message.companionId: Long
    get() = if (conversationType == "chat") conversationId else senderId

/** 兼容 GroupMessage.groupId：群聊返回 conversationId，单聊返回 0 */
val Message.groupId: Long
    get() = if (conversationType == "group") conversationId else 0

/** 兼容 ChatMessage API */
val Message.isFromAssistant: Boolean get() = !isFromUser
val Message.isSystem: Boolean get() = false
val Message.role: String get() = if (isFromUser) "user" else "assistant"
