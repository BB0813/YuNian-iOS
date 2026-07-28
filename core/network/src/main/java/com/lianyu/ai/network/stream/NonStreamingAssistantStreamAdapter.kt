package com.lianyu.ai.network.stream

import com.lianyu.ai.domain.stream.AssistantStreamEvent
import com.lianyu.ai.domain.timeline.TurnId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 非流式响应 → [AssistantStreamEvent] 管道。
 *
 * 当前 AiService 固定 `stream=false`；本适配器按序发射终态事件，
 * 供 feature 层统一走 PendingTurn / Commit 路径。
 * 真实 SSE 流式可另实现同契约适配器，feature 无需改 collect 逻辑。
 */
object NonStreamingAssistantStreamAdapter {

    /**
     * @param reasoning 完整思考文本（可空）
     * @param content 完整助手正文
     * @param startedAtMs 回合开始（用于 durationMs）
     * @param completedAtMs 网络返回时刻
     * @param anchorMessageId 可选锚点（用户消息 id）
     */
    fun fromCompleted(
        turnId: TurnId,
        reasoning: String?,
        content: String,
        startedAtMs: Long,
        completedAtMs: Long = System.currentTimeMillis(),
        anchorMessageId: Long? = null,
    ): Flow<AssistantStreamEvent> = flow {
        emit(AssistantStreamEvent.TurnStarted(turnId = turnId, anchorMessageId = anchorMessageId))

        val reasoningText = reasoning?.trim().orEmpty()
        if (reasoningText.isNotEmpty()) {
            val durationMs = (completedAtMs - startedAtMs).coerceAtLeast(1L)
            emit(
                AssistantStreamEvent.ReasoningCompleted(
                    turnId = turnId,
                    fullText = reasoningText,
                    durationMs = durationMs,
                )
            )
        }

        val text = content // 允许空串（tool_calls 场景由上层处理）
        emit(
            AssistantStreamEvent.TextCompleted(
                turnId = turnId,
                fullText = text,
                segmentIndex = 0,
            )
        )
        emit(AssistantStreamEvent.TurnCompleted(turnId = turnId))
    }

    /**
     * 将完整思考文本拆成伪 delta（可选渐进展示）。
     * 默认不启用；真实 SSE 到来前可用于本地调试节流路径。
     */
    fun fromCompletedWithReasoningChunks(
        turnId: TurnId,
        reasoning: String?,
        content: String,
        startedAtMs: Long,
        completedAtMs: Long = System.currentTimeMillis(),
        chunkSize: Int = 24,
        anchorMessageId: Long? = null,
    ): Flow<AssistantStreamEvent> = flow {
        emit(AssistantStreamEvent.TurnStarted(turnId = turnId, anchorMessageId = anchorMessageId))

        val reasoningText = reasoning?.trim().orEmpty()
        if (reasoningText.isNotEmpty()) {
            var offset = 0
            while (offset < reasoningText.length) {
                val end = (offset + chunkSize).coerceAtMost(reasoningText.length)
                emit(
                    AssistantStreamEvent.ReasoningDelta(
                        turnId = turnId,
                        delta = reasoningText.substring(offset, end),
                    )
                )
                offset = end
            }
            val durationMs = (completedAtMs - startedAtMs).coerceAtLeast(1L)
            emit(
                AssistantStreamEvent.ReasoningCompleted(
                    turnId = turnId,
                    fullText = reasoningText,
                    durationMs = durationMs,
                )
            )
        }

        emit(
            AssistantStreamEvent.TextCompleted(
                turnId = turnId,
                fullText = content,
                segmentIndex = 0,
            )
        )
        emit(AssistantStreamEvent.TurnCompleted(turnId = turnId))
    }
}
