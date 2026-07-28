package com.lianyu.ai.feature.chat.timeline

/**
 * 终态提交规则（纯函数，无 I/O / Compose）。
 *
 * 与 [PendingTurn] 解耦：规则只回答「是否 / 如何」提交，不持有回合状态。
 */
object EventCommitRules {

    /** 非空思考文本才落库（STREAMING 永不经此路径）。 */
    fun shouldPersistReasoning(text: String?): Boolean =
        !text.isNullOrBlank()

    /** 用户设置开启且有文本时，才投影到消息链路中的流式 REASONING 行。 */
    fun shouldProjectReasoningLive(showReasoningSetting: Boolean, text: String?): Boolean =
        showReasoningSetting && !text.isNullOrBlank()

    /**
     * 计算 durationMs。
     * - 有明确起止时间：用差值（至少 1ms，避免 0）
     * - 仅有完成时刻、无起点：返回 null（UI 用 formatter 兜底「已思考1秒」）
     */
    fun durationMs(startedAtMs: Long?, completedAtMs: Long = System.currentTimeMillis()): Long? {
        if (startedAtMs == null || startedAtMs <= 0L) return null
        val delta = completedAtMs - startedAtMs
        return if (delta <= 0L) 1L else delta
    }

    /** 正文分段是否应带 turn 元数据（有 turn 即绑定）。 */
    fun shouldAttachTurnMetadata(turn: PendingTurn?): Boolean = turn != null
}
