package com.lianyu.ai.domain.timeline

/**
 * 事件载荷契约：每种 [TimelineEventKind] 一个实现，互不引用，便于原子扩展。
 *
 * 不在此接口上堆业务方法；序列化由 [TimelinePayloadCodec] 负责。
 */
interface TimelinePayload {
    val kind: TimelineEventKind
}

/**
 * 思考过程载荷。
 *
 * @param text 完整或当前累计文本
 * @param durationMs 完成态耗时；STREAMING 时为 null。UI「已思考{n}秒」只读此字段。
 */
data class ReasoningPayload(
    val text: String,
    val durationMs: Long? = null,
) : TimelinePayload {
    override val kind: TimelineEventKind = TimelineEventKind.REASONING
}

/**
 * 助手正文载荷（可分段）。
 *
 * @param segmentIndex 同一 turn 内正文分段序号，从 0 起
 */
data class AssistantTextPayload(
    val text: String,
    val segmentIndex: Int = 0,
) : TimelinePayload {
    override val kind: TimelineEventKind = TimelineEventKind.ASSISTANT_TEXT
}

/**
 * 工具调用载荷（预留，不在本切片实现业务）。
 */
data class ToolCallPayload(
    val callId: String,
    val name: String,
    val argumentsJson: String,
) : TimelinePayload {
    override val kind: TimelineEventKind = TimelineEventKind.TOOL_CALL
}

/**
 * 工具结果载荷（预留）。
 */
data class ToolResultPayload(
    val callId: String,
    val name: String,
    val resultJson: String,
) : TimelinePayload {
    override val kind: TimelineEventKind = TimelineEventKind.TOOL_RESULT
}

/**
 * 系统/游戏事件载荷（预留）。
 * 高频 tick 禁止无聚合写入消息表——由 feature 侧 CommitRule 降采样。
 */
data class SystemEventPayload(
    val code: String,
    val dataJson: String = "",
) : TimelinePayload {
    override val kind: TimelineEventKind = TimelineEventKind.SYSTEM_EVENT
}
