package com.lianyu.ai.feature.qqbot.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatAckTrackerTest {

    @Test
    fun `healthy connection with prompt ack never reconnects`() {
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        // 第 1 轮：无历史心跳，跳过判定
        assertFalse(tracker.onBeforeSend(t0, 0L))
        // ack 在周期内到达
        val ack1 = t0 + 100L
        // 第 2 轮：ack1 晚于第 1 轮发送时间 → 健康
        assertFalse(tracker.onBeforeSend(t0 + period, ack1))
        // 第 3 轮：ack 持续正常
        val ack2 = t0 + period + 100L
        assertFalse(tracker.onBeforeSend(t0 + 2 * period, ack2))
        // 第 4 轮：仍健康
        val ack3 = t0 + 2 * period + 100L
        assertFalse(tracker.onBeforeSend(t0 + 3 * period, ack3))
    }

    @Test
    fun `single missed ack then recovery does not reconnect`() {
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        assertFalse(tracker.onBeforeSend(t0, 0L))
        // 第 1 轮心跳的 ack 一直没到
        // 第 2 轮：lastAck(0) < 第 1 轮发送时间 → 记为 1 次 missed
        assertFalse(tracker.onBeforeSend(t0 + period, 0L))
        // 第 2 轮的 ack 到了
        val ack2 = t0 + period + 100L
        // 第 3 轮：lastAck 晚于第 2 轮发送时间 → 健康，missed 归零
        assertFalse(tracker.onBeforeSend(t0 + 2 * period, ack2))
        // 第 4 轮：持续健康
        val ack3 = t0 + 2 * period + 100L
        assertFalse(tracker.onBeforeSend(t0 + 3 * period, ack3))
    }

    @Test
    fun `two consecutive missed acks triggers reconnect`() {
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        assertFalse(tracker.onBeforeSend(t0, 0L))
        // 第 1、2 轮心跳的 ack 都未到达
        assertFalse(tracker.onBeforeSend(t0 + period, 0L))
        assertTrue(tracker.onBeforeSend(t0 + 2 * period, 0L))
    }

    @Test
    fun `first heartbeat never triggers reconnect even without ack`() {
        val tracker = HeartbeatAckTracker()
        assertFalse(tracker.onBeforeSend(1_000L, 0L))
    }

    @Test
    fun `reset clears pending state`() {
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        assertFalse(tracker.onBeforeSend(t0, 0L))
        assertFalse(tracker.onBeforeSend(t0 + period, 0L))
        tracker.reset()
        // 重置后重新计数，不会立即触发
        assertFalse(tracker.onBeforeSend(t0 + 2 * period, 0L))
        assertFalse(tracker.onBeforeSend(t0 + 3 * period, 0L))
        assertTrue(tracker.onBeforeSend(t0 + 4 * period, 0L))
    }

    @Test
    fun `stale old ack does not mask new missed heartbeats`() {
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        assertFalse(tracker.onBeforeSend(t0, 0L))
        // 第 1 轮 ack 正常到达
        val ack1 = t0 + 50L
        assertFalse(tracker.onBeforeSend(t0 + period, ack1))
        // 之后服务器不再 ack（lastAck 停留在 ack1）
        assertFalse(tracker.onBeforeSend(t0 + 2 * period, ack1)) // missed=1
        assertTrue(tracker.onBeforeSend(t0 + 3 * period, ack1)) // missed=2 → 重连
    }

    @Test
    fun `ack after next send is treated as healthy current activity`() {
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        assertFalse(tracker.onBeforeSend(t0, 0L))
        // 第 1 轮心跳从未 ack
        assertFalse(tracker.onBeforeSend(t0 + period, 0L)) // missed=1
        // 第 2 轮发送后才收到 ack（比上次发送新）→ 连接仍活着，missed 归零
        val lateAck = t0 + period + 200L
        assertFalse(tracker.onBeforeSend(t0 + 2 * period, lateAck))
        val ack3 = t0 + 2 * period + 100L
        assertFalse(tracker.onBeforeSend(t0 + 3 * period, ack3))
    }
}
