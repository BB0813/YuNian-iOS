package com.yunian.ai.feature.qqbot.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「QQ 通道永久卡在连接中」缺陷的回归测试。
 *
 * 每条测试都对应旧实现里的一个具体缺陷；注释里写明了「旧实现为什么会让它失败」，
 * 以避免同义反复（断言只是把实现重写一遍）。
 *
 * 测试载体是纯逻辑的 [ConnectionStateMachine] + [ConnectionRecoveryPolicy]，
 * 与 HeartbeatAckTrackerTest 同一风格：无 Android、无 OkHttp、无协程。
 */
class ConnectionStateMachineTest {

    private val t0 = 1_000_000L

    /** 复刻 QQBotWebSocketClient 的接线：状态机 + 「重连是否在途」标志。 */
    private class Harness {
        val published = mutableListOf<ConnectionState>()
        var reconnectPending = false
        val machine = ConnectionStateMachine { published += it }
    }

    // ---------------------------------------------------------------- 缺陷 1
    // onClosing 是三个回调里唯一不排重连的。服务端发起关闭时，
    // 旧实现：cleanupConnectionState() → wasConnected=false → 连 DISCONNECTED 都不发，
    // 且没人排重连 → 状态永久停在 CONNECTING。
    @Test
    fun `server initiated close mid handshake still schedules a reconnect`() {
        val h = Harness()
        h.machine.onConnectStarted(t0)
        assertEquals(ConnectionState.CONNECTING, h.machine.current)

        // onClosing 的接线：cleanupConnectionState() + scheduleReconnect()
        h.machine.onTransportLost(t0 + 500L)
        val outcome = ConnectionRecoveryPolicy.onTransportClosed()
        assertEquals(TransportCloseOutcome.ScheduleReconnect, outcome)

        // 旧实现：onClosing 根本不调 scheduleReconnect()，走到这里状态仍是 CONNECTING。
        // 这里复刻修好之后的接线：排重连 → 状态机进入 RECONNECTING。
        h.machine.onReconnectScheduled(t0 + 600L)

        // 旧实现：cleanupConnectionState() 因 wasConnected=false 一条状态都不发，
        // 于是 published 只剩 [CONNECTING]，断言立即失败。
        assertEquals(
            listOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING),
            h.published,
        )
        assertTrue("must leave CONNECTING", h.machine.current != ConnectionState.CONNECTING)
        assertEquals(ConnectionState.RECONNECTING, h.machine.current)

        // 退避结束后发起下一次连接：状态必须继续推进（RECONNECTING 不是终点）。
        h.machine.onConnectStarted(t0 + 1_500L)
        assertEquals(ConnectionState.CONNECTING, h.machine.current)
        assertTrue(h.machine.isTransient())
    }

    // ---------------------------------------------------------------- 缺陷 2
    // connect() 失败路径。旧实现在 reconnectAttempt 0..4 期间只调用 scheduleReconnect()，
    // 而 scheduleReconnect() 在「重连 job 已活跃」时静默 return（不发状态）。
    // 关键在于：旧的 reconnectJob 成功后**从不置空**，所以第一次重连之后
    // 每一次失败都被静默吞掉，状态就永久停在 CONNECTING。
    @Test
    fun `connect failure before attempt limit keeps leaving CONNECTING and reschedules`() {
        val h = Harness()
        val maxAttempts = 10
        h.machine.onConnectStarted(t0)
        var now = t0

        // 连做两次失败，模拟真实重连循环（重连 job 每次执行完即释放，
        // 所以下一次失败必须重新排程——旧实现第二次起就再也排不上了）。
        repeat(2) { index ->
            val attempt = index + 1
            now += 100L
            val outcome = ConnectionRecoveryPolicy.onConnectFailed(
                state = h.machine.current,
                attempt = attempt,
                retryPending = h.reconnectPending,
                maxAttempts = maxAttempts,
            )
            assertEquals(
                "failure #$attempt must schedule a fresh retry (attempt < maxAttempts)",
                ConnectFailureOutcome.ScheduleRetry,
                outcome,
            )

            // 旧实现：第 2 次起 reconnectJob 仍被当作活跃，scheduleReconnect() 直接
            // return，状态留在 CONNECTING —— 断言在这里失败。
            h.reconnectPending = true
            h.machine.onReconnectScheduled(now)
            h.reconnectPending = false          // job 执行完毕（旧实现从不做这一步）

            assertEquals(ConnectionState.RECONNECTING, h.machine.current)
            assertFalse("must have left CONNECTING", h.machine.isConnecting())
            assertTrue("RECONNECTING is not a terminal state", h.machine.isTransient())

            now += 100L
            h.machine.onConnectStarted(now)
            assertEquals(ConnectionState.CONNECTING, h.machine.current)
        }

        assertEquals(
            listOf(
                ConnectionState.CONNECTING,
                ConnectionState.RECONNECTING,
                ConnectionState.CONNECTING,
                ConnectionState.RECONNECTING,
                ConnectionState.CONNECTING,
            ),
            h.published,
        )
    }

    // ---------------------------------------------------------------- 缺陷 3
    // 覆盖 scheduleReconnect() 的早退分支（原 :438 if (reconnectJob?.isActive == true) return）。
    @Test
    fun `connect failure while a reconnect is already pending must still leave CONNECTING`() {
        val h = Harness()
        h.reconnectPending = true                      // 重连 job 已活跃
        h.machine.onConnectStarted(t0)

        val outcome = ConnectionRecoveryPolicy.onConnectFailed(
            state = h.machine.current,
            attempt = 1,
            retryPending = h.reconnectPending,
            maxAttempts = 10,
        )
        // 不排新 job（复用已有的），但**绝不能**因此不发状态。
        assertEquals(ConnectFailureOutcome.WaitForScheduledRetry, outcome)

        h.machine.onReconnectScheduled(t0 + 100L)

        // 旧实现：scheduleReconnect() 直接 return，状态留在 CONNECTING → 失败。
        assertEquals(ConnectionState.RECONNECTING, h.machine.current)
        assertEquals(
            listOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING),
            h.published,
        )
    }

    // ---------------------------------------------------------------- 缺陷 4
    // 卡在 CONNECTING 的兜底。旧实现没有任何「进入状态的时间戳」概念，
    // FGS 看门狗又无条件跳过 CONNECTING（原 :83-85），无人可救。
    @Test
    fun `CONNECTING held past threshold with no retry pending is detected as stuck`() {
        val h = Harness()
        h.machine.onConnectStarted(t0)

        assertFalse(
            "not stuck yet",
            h.machine.shouldForceRecovery(t0 + ConnectionStateMachine.STUCK_IN_TRANSIENT_STATE_TIMEOUT_MS, retryPending = false),
        )
        assertTrue(
            "must be detected as stuck",
            h.machine.shouldForceRecovery(t0 + ConnectionStateMachine.STUCK_IN_TRANSIENT_STATE_TIMEOUT_MS + 1L, retryPending = false),
        )
    }

    // 有重连在途时不得打断：退避上限 + 握手超时可能远超 30s，
    // 此时强行恢复只会重置已累计的退避计数。
    @Test
    fun `pending retry suppresses the stuck timeout until the reconnect horizon`() {
        val h = Harness()
        h.machine.onConnectStarted(t0)

        assertFalse(
            h.machine.shouldForceRecovery(t0 + 60_000L, retryPending = true),
        )
        assertTrue(
            h.machine.shouldForceRecovery(t0 + ConnectionStateMachine.MAX_RECONNECT_HORIZON_MS + 1L, retryPending = true),
        )
    }

    // ---------------------------------------------------------------- 正常路径
    @Test
    fun `healthy READY reaches CONNECTED and is never judged stuck`() {
        val h = Harness()
        h.machine.onConnectStarted(t0)
        h.machine.onReady(t0 + 300L)

        assertEquals(ConnectionState.CONNECTED, h.machine.current)
        assertEquals(listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED), h.published)
        assertTrue(h.machine.isConnected())
        assertFalse(h.machine.isTransient())
        assertTrue("CONNECTED is settled", h.machine.isSettled())

        // 长时间在线不得被兜底误判——这正是「被误判」会造成的伤害：
        // 健康的长连接被 FGS 每 30s 踢一次。
        assertFalse(h.machine.shouldForceRecovery(t0 + 10 * 60_000L, retryPending = false))
        assertFalse(h.machine.shouldForceRecovery(t0 + 10 * 60_000L, retryPending = true))

        // CONNECTED 掉线必须立刻可见（旧实现在这条路径上是对的，属于回归保护）。
        h.machine.onTransportLost(t0 + 10 * 60_000L)
        assertEquals(ConnectionState.DISCONNECTED, h.machine.current)
    }

    @Test
    fun `retry budget exhaustion reaches the AUTH_FAILED terminal state`() {
        val h = Harness()
        h.machine.onConnectStarted(t0)

        val outcome = ConnectionRecoveryPolicy.onConnectFailed(
            state = h.machine.current,
            attempt = 11,          // MAX_RECONNECT_ATTEMPTS(10) + 1
            retryPending = false,
            maxAttempts = 10,
        )
        assertEquals(ConnectFailureOutcome.GiveUp, outcome)
        h.machine.onAuthFailed(t0 + 100L)

        assertEquals(ConnectionState.AUTH_FAILED, h.machine.current)
        assertTrue(h.machine.isSettled())
        // AUTH_FAILED 是终态：兜底不得反复踢它，否则重试计数会被无限重置。
        assertFalse(h.machine.shouldForceRecovery(t0 + 10 * 60_000L, retryPending = false))
        assertFalse(h.machine.shouldForceRecovery(t0 + 10 * 60_000L, retryPending = true))
    }

    // ---------------------------------------------------------------- 缺陷 5
    // 不变量：整条重连链路走完，除 CONNECTED / AUTH_FAILED 外不得有终态。
    @Test
    fun `every failure state is transient until CONNECTED or AUTH_FAILED`() {
        val h = Harness()
        val maxAttempts = 10

        h.machine.onConnectStarted(t0)
        var now = t0

        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            now += 1_000L
            val outcome = ConnectionRecoveryPolicy.onConnectFailed(
                state = h.machine.current,
                attempt = attempts,
                retryPending = false,
                maxAttempts = maxAttempts,
            )
            assertEquals(ConnectFailureOutcome.ScheduleRetry, outcome)
            h.machine.onReconnectScheduled(now)

            // RECONNECTING 绝不能是终点：必须有在途重连把它推走，
            // 否则就等价于「卡在重连中」。旧实现里 reconnectJob 成功后从不置空，
            // 导致第二次 scheduleReconnect() 起永久早退 —— 正是这种死法。
            assertTrue("RECONNECTING must be transient", h.machine.isTransient())
            assertFalse(h.machine.shouldForceRecovery(now, retryPending = true))

            now += 1_000L
            h.machine.onConnectStarted(now)
            assertEquals(ConnectionState.CONNECTING, h.machine.current)
        }

        // 预算耗尽 → 终态。
        now += 1_000L
        val giveUp = ConnectionRecoveryPolicy.onConnectFailed(
            state = h.machine.current,
            attempt = attempts + 1,
            retryPending = false,
            maxAttempts = maxAttempts,
        )
        assertEquals(ConnectFailureOutcome.GiveUp, giveUp)
        h.machine.onAuthFailed(now)
        assertEquals(ConnectionState.AUTH_FAILED, h.machine.current)
        assertTrue(h.machine.isSettled())
    }

    @Test
    fun `repeated same state does not reset the stuck timer`() {
        val h = Harness()
        h.machine.onConnectStarted(t0)

        // 反复「重新进入 CONNECTING」不得刷新计时，否则兜底永远不会触发。
        h.machine.onConnectStarted(t0 + 20_000L)
        h.machine.onConnectStarted(t0 + 40_000L)

        assertEquals(t0, h.machine.enteredAtMs)
        assertTrue(
            h.machine.shouldForceRecovery(t0 + ConnectionStateMachine.STUCK_IN_TRANSIENT_STATE_TIMEOUT_MS + 1L, retryPending = false),
        )
        assertEquals(1, h.published.size)
    }

    @Test
    fun `backoff grows with attempts and is capped`() {
        val base = 1_000L
        val max = 5_000L
        assertEquals(1_000L, ConnectionRecoveryPolicy.backoffMs(1, base, max))
        assertEquals(3_000L, ConnectionRecoveryPolicy.backoffMs(3, base, max))
        assertEquals(5_000L, ConnectionRecoveryPolicy.backoffMs(9, base, max))
        assertEquals(5_000L, ConnectionRecoveryPolicy.backoffMs(10, base, max))
    }
}
