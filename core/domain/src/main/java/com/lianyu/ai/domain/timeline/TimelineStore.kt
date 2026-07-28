package com.lianyu.ai.domain.timeline

/**
 * 会话定位（与 messages.conversationId / conversationType 对齐）。
 * 与 TimelineEvent 解耦，避免事件载荷携带存储路由细节。
 */
data class ConversationRef(
    val conversationId: Long,
    val conversationType: String,
    /** 群聊发言人；单聊默认 0 */
    val senderId: Long = 0L,
) {
    init {
        require(conversationType == "chat" || conversationType == "group") {
            "conversationType must be chat|group"
        }
    }
}

/**
 * 时间线持久化端口（实现在 core:database，经 ServiceRegistry 注入）。
 *
 * 职责边界：
 * - 只接受 COMPLETE / FAILED / CANCELLED 等终态事件
 * - 不包含 Compose、不解析 SSE
 * - 与 ChatRepository 并存：旧 ChatMessage API 不膨胀为上帝仓库
 *
 * 性能：
 * - append 单次加密 + insert；禁止在 STREAMING delta 路径调用
 * - load 供 UI/策略使用；模型历史应在实现侧支持 kind 过滤以免无谓解密
 */
interface TimelineStore {
    /**
     * 追加一条终态事件，返回持久化 messageId。
     * @throws IllegalArgumentException 若 status 为 STREAMING
     */
    suspend fun appendComplete(scope: ConversationRef, event: TimelineEvent): Long

    /** 加载同一 turn 的全部已落库事件（按 eventIndex 升序） */
    suspend fun loadTurn(turnId: TurnId): List<TimelineEvent>

    /**
     * 加载会话事件窗口。
     * @param kinds null 表示全部 kind；非 null 时仅加载给定种类（SQL 层过滤）
     */
    suspend fun loadConversationEvents(
        conversationId: Long,
        conversationType: String,
        limit: Int,
        kinds: Set<TimelineEventKind>? = null,
    ): List<TimelineEvent>
}
