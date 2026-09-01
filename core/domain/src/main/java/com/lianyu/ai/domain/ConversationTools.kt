package com.lianyu.ai.domain

/**
 * 对话工具领域模型。
 */

/** 会话摘要（用于 recent_chats） */
data class ConversationSummary(
    val sessionId: Long,
    val sessionType: String,           // "companion" | "group"
    val title: String,                 // 伴侣名或群名
    val lastMessagePreview: String,    // 最后一条消息预览
    val lastMessageTimestamp: Long,    // 最后消息时间
    val unreadCount: Int = 0,
    val companionId: Long? = null,     // 单聊时的伴侣 ID
    val groupId: Long? = null          // 群聊时的群 ID
)

/** 搜索结果项 */
data class ConversationSearchResult(
    val sessionId: Long,
    val sessionType: String,
    val title: String,
    val matchedContent: String,        // 命中的内容片段
    val timestamp: Long,               // 消息时间
    val role: String                   // "user" | "assistant"
)

/** recent_chats 参数 */
data class RecentChatsArgs(
    val limit: Int = 20,
    val includeGroups: Boolean = true
)

/** conversation_search 参数 */
data class ConversationSearchArgs(
    val query: String,
    val limit: Int = 10,
    val sessionType: String? = null,   // "companion" | "group" | null=全部
    val companionId: Long? = null,     // 限定伴侣
    val groupId: Long? = null          // 限定群组
)