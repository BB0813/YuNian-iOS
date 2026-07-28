package com.lianyu.ai.feature.chat.timeline

import com.lianyu.ai.domain.timeline.ConversationRef
import com.lianyu.ai.domain.timeline.TimelineEvent
import com.lianyu.ai.domain.timeline.TimelineEventFactory
import com.lianyu.ai.domain.timeline.TurnEventIndexer
import com.lianyu.ai.domain.timeline.TurnId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 单轮助手回合的内存态（STREAMING 真相源）。
 *
 * - 不落库；终态事件由 [EventCommitRules] + 调用方写入 [com.lianyu.ai.domain.timeline.TimelineStore]
 * - 线程：单轮通常在同一 IO 协程；缓冲用同步块保护
 * - UI 流式展示经 [StreamingReasoningMessagePipeline] 写入 MessageCache，不经 ephemeral StateFlow
 */
class PendingTurn private constructor(
    val turnId: TurnId,
    val conversation: ConversationRef,
    val anchorMessageId: Long?,
    val startedAtMs: Long,
    private val indexer: TurnEventIndexer,
) {
    private val lock = Any()
    private val reasoningBuffer = StringBuilder()
    private val reasoningCompleted = AtomicBoolean(false)
    private val completedReasoningEvent = AtomicReference<TimelineEvent?>(null)
    private val reasoningCommitTaken = AtomicBoolean(false)
    private var assistantSegmentCount = 0

    val isReasoningComplete: Boolean get() = reasoningCompleted.get()

    fun appendReasoningDelta(delta: String) {
        if (delta.isEmpty() || reasoningCompleted.get()) return
        synchronized(lock) {
            if (reasoningCompleted.get()) return
            reasoningBuffer.append(delta)
        }
    }

    fun replaceReasoningText(text: String) {
        if (reasoningCompleted.get()) return
        synchronized(lock) {
            if (reasoningCompleted.get()) return
            reasoningBuffer.setLength(0)
            reasoningBuffer.append(text)
        }
    }

    fun snapshotReasoningText(): String = synchronized(lock) { reasoningBuffer.toString() }

    fun streamingReasoningEvent(): TimelineEvent? {
        if (reasoningCompleted.get()) return null
        val text = snapshotReasoningText()
        if (text.isBlank()) return null
        // STREAMING 不消耗 eventIndex；完成态再分配，避免空洞
        return TimelineEventFactory.streamingReasoning(
            turnId = turnId,
            eventIndex = indexer.peek(turnId),
            text = text,
            timestamp = System.currentTimeMillis(),
            anchorMessageId = anchorMessageId,
        )
    }

    /**
     * 将思考过程标记为 COMPLETE 并分配 eventIndex。
     * 可重复调用：第二次返回已缓存事件。
     */
    fun completeReasoning(
        finalText: String? = null,
        durationMs: Long?,
        timestamp: Long = System.currentTimeMillis(),
    ): TimelineEvent? {
        completedReasoningEvent.get()?.let { return it }
        val text = synchronized(lock) {
            if (finalText != null) {
                reasoningBuffer.setLength(0)
                reasoningBuffer.append(finalText)
            }
            reasoningBuffer.toString()
        }
        if (text.isBlank()) {
            reasoningCompleted.set(true)
            return null
        }
        val event = TimelineEventFactory.completeReasoning(
            turnId = turnId,
            eventIndex = indexer.next(turnId),
            text = text,
            durationMs = durationMs ?: EventCommitRules.durationMs(startedAtMs, timestamp) ?: 1L,
            timestamp = timestamp,
            anchorMessageId = anchorMessageId,
        )
        if (completedReasoningEvent.compareAndSet(null, event)) {
            reasoningCompleted.set(true)
            return event
        }
        return completedReasoningEvent.get()
    }

    /**
     * 取出待落库的 REASONING 终态事件（仅一次）。
     * 流适配器可能已 [completeReasoning]；提交协调器应只通过本方法消费，避免重复 insert。
     */
    fun takeReasoningEventForCommit(): TimelineEvent? {
        val event = completedReasoningEvent.get() ?: return null
        return if (reasoningCommitTaken.compareAndSet(false, true)) event else null
    }

    /**
     * 分配下一条助手正文的 eventIndex，并返回 COMPLETE 事件壳（正文仍可由 MessageWrite 落库）。
     */
    fun nextAssistantTextEvent(
        text: String,
        timestamp: Long = System.currentTimeMillis(),
    ): TimelineEvent {
        val segmentIndex = assistantSegmentCount++
        return TimelineEventFactory.completeAssistantText(
            turnId = turnId,
            eventIndex = indexer.next(turnId),
            text = text,
            segmentIndex = segmentIndex,
            timestamp = timestamp,
            anchorMessageId = anchorMessageId,
        )
    }

    fun releaseIndexer() {
        indexer.reset(turnId)
    }

    companion object {
        fun start(
            conversation: ConversationRef,
            anchorMessageId: Long? = null,
            startedAtMs: Long = System.currentTimeMillis(),
            turnId: TurnId = TurnId(UUID.randomUUID().toString()),
            indexer: TurnEventIndexer = TurnEventIndexer(),
        ): PendingTurn = PendingTurn(
            turnId = turnId,
            conversation = conversation,
            anchorMessageId = anchorMessageId,
            startedAtMs = startedAtMs,
            indexer = indexer,
        )
    }
}
