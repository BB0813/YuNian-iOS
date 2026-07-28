package com.lianyu.ai.domain.timeline

/**
 * 思考过程展示相关偏好（由 UI/设置层注入，domain 不读 DataStore）。
 */
data class ReasoningDisplayPrefs(
    val showReasoning: Boolean = false,
    val autoCollapse: Boolean = true,
    /** 是否把思考送入模型上下文（默认 false；与 UI 展示解耦） */
    val sendReasoningToModel: Boolean = false,
)

/**
 * 是否进入模型上下文 —— 与 UI 是否展示解耦。
 *
 * 性能：应在解密前/SQL 元数据层尽量先过滤 kind，避免先 decrypt 再丢弃。
 */
fun interface ModelContextInclusionPolicy {
    fun include(event: TimelineEvent, prefs: ReasoningDisplayPrefs): Boolean
}

/** 便于调用方省略 prefs 的扩展 */
fun ModelContextInclusionPolicy.include(event: TimelineEvent): Boolean =
    include(event, ReasoningDisplayPrefs())

/**
 * 默认策略：正文进上下文；思考默认不进；工具进出（Agent 协议需要）。
 */
object DefaultModelContextPolicy : ModelContextInclusionPolicy {
    override fun include(event: TimelineEvent, prefs: ReasoningDisplayPrefs): Boolean {
        if (event.status != TimelineEventStatus.COMPLETE) return false
        return when (event.kind) {
            TimelineEventKind.ASSISTANT_TEXT -> true
            TimelineEventKind.REASONING -> prefs.sendReasoningToModel
            TimelineEventKind.TOOL_CALL, TimelineEventKind.TOOL_RESULT -> true
            TimelineEventKind.SYSTEM_EVENT -> false
        }
    }
}

/**
 * 用户是否可见该事件（设置开关注入）。
 */
fun interface UserVisibilityPolicy {
    fun visible(event: TimelineEvent, prefs: ReasoningDisplayPrefs): Boolean
}

object DefaultUserVisibilityPolicy : UserVisibilityPolicy {
    override fun visible(event: TimelineEvent, prefs: ReasoningDisplayPrefs): Boolean {
        if (event.visibility == TimelineVisibility.INTERNAL) return false
        if (event.visibility == TimelineVisibility.DEBUG) return false
        return when (event.kind) {
            TimelineEventKind.REASONING -> prefs.showReasoning &&
                (event.status == TimelineEventStatus.STREAMING ||
                    event.status == TimelineEventStatus.COMPLETE)
            TimelineEventKind.ASSISTANT_TEXT ->
                event.status == TimelineEventStatus.STREAMING ||
                    event.status == TimelineEventStatus.COMPLETE
            TimelineEventKind.TOOL_CALL, TimelineEventKind.TOOL_RESULT ->
                event.status == TimelineEventStatus.COMPLETE
            TimelineEventKind.SYSTEM_EVENT ->
                event.status == TimelineEventStatus.COMPLETE
        }
    }
}

/**
 * 将 durationMs 格式化为「已思考{n}秒」的秒数。
 * 使用向上取整到秒，最少 1 秒（有内容时）；空思考返回 0。
 */
object ReasoningDurationFormatter {
    fun secondsForDisplay(durationMs: Long?, hasText: Boolean): Int {
        if (!hasText) return 0
        val ms = durationMs ?: return 1
        if (ms <= 0L) return 1
        return ((ms + 999L) / 1000L).toInt().coerceAtLeast(1)
    }

    fun collapsedLabel(durationMs: Long?, hasText: Boolean): String {
        val n = secondsForDisplay(durationMs, hasText)
        return if (n <= 0) "已思考" else "已思考${n}秒"
    }

    const val STREAMING_LABEL = "思考中…"
}
