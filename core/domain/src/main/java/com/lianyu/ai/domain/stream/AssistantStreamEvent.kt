package com.lianyu.ai.domain.stream

import com.lianyu.ai.domain.timeline.TurnId

/**
 * 网络/生成层 → 运行时 的唯一增量管道。
 *
 * 约束（原子化 + 性能）：
 * - 不含 DB id、不含 Compose state、不写库
 * - Provider 差异收敛在 core:network 适配器；feature 只 collect 本流
 * - ReasoningDelta / TextDelta 必须在 UI 侧节流合并后再推列表
 *   （建议沿用 STREAM_BUFFER_INTERVAL_MS / 字数阈值）
 * - 非流式模型：适配器按序发射 Completed 事件即可
 */
sealed interface AssistantStreamEvent {
    val turnId: TurnId

    data class TurnStarted(
        override val turnId: TurnId,
        val anchorMessageId: Long? = null,
    ) : AssistantStreamEvent

    data class ReasoningDelta(
        override val turnId: TurnId,
        val delta: String,
    ) : AssistantStreamEvent

    data class ReasoningCompleted(
        override val turnId: TurnId,
        val fullText: String,
        val durationMs: Long,
    ) : AssistantStreamEvent {
        init {
            require(durationMs >= 0L) { "durationMs must be >= 0" }
        }
    }

    data class TextDelta(
        override val turnId: TurnId,
        val delta: String,
    ) : AssistantStreamEvent

    data class TextCompleted(
        override val turnId: TurnId,
        val fullText: String,
        val segmentIndex: Int = 0,
    ) : AssistantStreamEvent {
        init {
            require(segmentIndex >= 0) { "segmentIndex must be >= 0" }
        }
    }

    /** 预留：工具调用请求增量/完成由后续 Agent 切片扩展，避免本切片耦合 */

    data class TurnFailed(
        override val turnId: TurnId,
        val message: String,
    ) : AssistantStreamEvent

    data class TurnCompleted(
        override val turnId: TurnId,
    ) : AssistantStreamEvent
}
