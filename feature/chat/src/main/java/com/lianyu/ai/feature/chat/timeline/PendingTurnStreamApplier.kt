package com.lianyu.ai.feature.chat.timeline

import com.lianyu.ai.domain.stream.AssistantStreamEvent
import com.lianyu.ai.domain.timeline.TurnId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * 将 [AssistantStreamEvent] 应用到 [PendingTurn]（仅内存，不落库）。
 *
 * - ReasoningDelta → append + 节流投影
 * - ReasoningCompleted → completeReasoning（分配 eventIndex，仍不写 DB）
 * - TextDelta / TextCompleted → 累计正文快照（落库仍由 TurnCommitCoordinator）
 * - TurnFailed → 标记失败文案
 *
 * DB 提交仍由 [AiResponseFinalizer] / [TurnCommitCoordinator] 在 COMPLETE 后执行。
 */
class PendingTurnStreamApplier(
    private val throttle: StreamDeltaThrottle = StreamDeltaThrottle(),
) {
    data class Result(
        val assistantText: String,
        val reasoningText: String?,
        val reasoningDurationMs: Long?,
        val failedMessage: String?,
        val completed: Boolean,
    )

    /**
     * @param projectLive 是否向 UI 投影思考过程
     * @param onReasoningSnapshot 节流后的完整思考快照（非 delta）
     * @param nowMs 可注入时钟（测试）
     */
    suspend fun apply(
        events: Flow<AssistantStreamEvent>,
        turn: PendingTurn,
        projectLive: Boolean,
        onReasoningSnapshot: (String) -> Unit = {},
        nowMs: () -> Long = { System.currentTimeMillis() },
    ): Result {
        var assistantText = ""
        var reasoningDurationMs: Long? = null
        var failedMessage: String? = null
        var completed = false
        val expectedTurn: TurnId = turn.turnId

        events.collect { event ->
            if (event.turnId != expectedTurn) return@collect
            when (event) {
                is AssistantStreamEvent.TurnStarted -> Unit

                is AssistantStreamEvent.ReasoningDelta -> {
                    if (event.delta.isEmpty() || turn.isReasoningComplete) return@collect
                    turn.appendReasoningDelta(event.delta)
                    val now = nowMs()
                    if (projectLive && throttle.shouldEmit(now, event.delta.length, force = false)) {
                        onReasoningSnapshot(turn.snapshotReasoningText())
                        throttle.markEmitted(now)
                    }
                }

                is AssistantStreamEvent.ReasoningCompleted -> {
                    reasoningDurationMs = event.durationMs
                    turn.completeReasoning(
                        finalText = event.fullText,
                        durationMs = event.durationMs,
                        timestamp = nowMs(),
                    )
                    if (projectLive && event.fullText.isNotBlank()) {
                        onReasoningSnapshot(event.fullText)
                        throttle.markEmitted(nowMs())
                    }
                }

                is AssistantStreamEvent.TextDelta -> {
                    if (event.delta.isEmpty()) return@collect
                    assistantText += event.delta
                }

                is AssistantStreamEvent.TextCompleted -> {
                    assistantText = event.fullText
                }

                is AssistantStreamEvent.TurnFailed -> {
                    failedMessage = event.message
                }

                is AssistantStreamEvent.TurnCompleted -> {
                    completed = true
                    // 强制刷一次思考投影，避免末尾卡在节流窗口
                    if (projectLive) {
                        val snap = turn.snapshotReasoningText()
                        if (snap.isNotBlank()) {
                            onReasoningSnapshot(snap)
                            throttle.markEmitted(nowMs())
                        }
                    }
                }
            }
        }

        val reasoning = turn.snapshotReasoningText().trim().ifBlank { null }
        return Result(
            assistantText = assistantText,
            reasoningText = reasoning,
            reasoningDurationMs = reasoningDurationMs,
            failedMessage = failedMessage,
            completed = completed,
        )
    }
}
