package com.lianyu.ai.feature.qqbot.data.network

/**
 * 心跳 ack 超时判定（纯逻辑，可单测）。
 *
 * 规则：发心跳时记录 sentAt；下一轮发心跳前检查上一轮是否收到 ack。
 * 若上一轮 ack 未在 [maxMissedAcks] 个周期内到达，判定死连接，通知上层主动重连
 * （比等服务器踢线更早恢复，避免陷入"静默断开"状态）。
 *
 * 历史缺陷：旧实现在发送后用"新发送时间 > 旧 ack 时间"判超时，健康连接下该式恒为 true
 * （新发送时间总晚于旧 ack 时间），导致每 2 个周期（~66s）误判一次重连，形成重连风暴。
 */
internal class HeartbeatAckTracker(
    private val maxMissedAcks: Int = 2,
) {
    private var missedAcks = 0
    private var pendingSentAtMs = 0L

    /**
     * 在发送新一轮心跳之前调用。
     * @param nowMs 当前时间
     * @param lastAckAtMs 最近一次收到 opHeartbeatAck 的时间（0 = 从未收到）
     * @return true 表示连续 [maxMissedAcks] 个周期未收到上一轮心跳的 ack，应主动重连
     */
    fun onBeforeSend(nowMs: Long, lastAckAtMs: Long): Boolean {
        if (pendingSentAtMs > 0L && lastAckAtMs < pendingSentAtMs) {
            missedAcks++
            if (missedAcks >= maxMissedAcks) {
                reset()
                return true
            }
        } else {
            missedAcks = 0
        }
        pendingSentAtMs = nowMs
        return false
    }

    /** 连接重置 / 重连时复位。 */
    fun reset() {
        missedAcks = 0
        pendingSentAtMs = 0L
    }
}
