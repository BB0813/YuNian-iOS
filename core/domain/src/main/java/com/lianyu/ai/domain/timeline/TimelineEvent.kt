package com.lianyu.ai.domain.timeline

/**
 * 助手时间线事件（领域真相，与 Room/Compose 解耦）。
 *
 * 同一 [turnId] 内用 [eventIndex] 保证稳定顺序：
 * 思考过程 →（工具…）→ 正文回复。
 *
 * 性能：
 * - 列表 diff 使用 turnId+eventIndex（或落库后 eventId）作稳定键
 * - STREAMING 事件不得进入 [TimelineStore.appendComplete]
 */
data class TimelineEvent(
    val eventId: EventId? = null,
    val turnId: TurnId,
    val kind: TimelineEventKind,
    val status: TimelineEventStatus,
    val eventIndex: Int,
    val timestamp: Long,
    val visibility: TimelineVisibility = TimelineVisibility.USER,
    val payload: TimelinePayload,
    /** 锚定用户消息或 turn 根；domain 不解释业务含义 */
    val anchorMessageId: Long? = null,
) {
    init {
        require(eventIndex >= 0) { "eventIndex must be >= 0" }
        require(payload.kind == kind) {
            "payload.kind (${payload.kind}) must match event.kind ($kind)"
        }
    }
}
