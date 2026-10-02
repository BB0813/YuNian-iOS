package com.yunian.ai.domain.plugin

/** 标准工具生命周期事件名与脱敏载荷。 */
object ToolLifecycleEvents {
    val STARTED: EventKey<ToolStarted> = EventKey("tool.started")
    val FINISHED: EventKey<ToolFinished> = EventKey("tool.finished")
}

/**
 * 工具开始事件。
 *
 * 隐私契约：只含关联标识、工具名和时间；禁止加入 arguments/context 等模型或用户数据。
 */
data class ToolStarted(
    val streamId: String,
    val callId: String,
    val toolName: String,
    val startedAtMs: Long,
)

/** 工具终态；每个已发布的 [ToolStarted] 必须恰好对应一个 finished。 */
enum class ToolFinishStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
}

/**
 * 工具结束事件。
 *
 * 隐私契约：不含 arguments、context、result、异常文本或堆栈；[status] 是稳定分类，
 * [elapsedMs] 仅用于过程展示/诊断。
 */
data class ToolFinished(
    val streamId: String,
    val callId: String,
    val toolName: String,
    val status: ToolFinishStatus,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val elapsedMs: Long,
)
