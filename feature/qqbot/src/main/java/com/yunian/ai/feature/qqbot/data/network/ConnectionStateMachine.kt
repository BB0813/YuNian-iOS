package com.yunian.ai.feature.qqbot.data.network

/**
 * QQ 机器人连接状态机（纯逻辑，无 Android / OkHttp / 协程依赖，可 JVM 单测）。
 *
 * 存在意义：修复「QQ 通道永久卡在连接中」。
 *
 * 旧实现把状态散落在 [QQBotWebSocketClient] 的 `isConnected` / `isConnecting` 两个
 * AtomicBoolean 上，没有任何一处记录「当前状态是什么时候进入的」，于是两个致命缺陷：
 *
 * 1. `cleanupConnectionState()` 只在「曾连上」时才发状态回调（`if (wasConnected)`）。
 *    服务端发起关闭 → `onClosing` → 状态仍是 CONNECTING（wasConnected=false）
 *    → **连状态回调都不发**，UI 永久停在「连接中」。
 * 2. FGS 看门狗无条件跳过 CONNECTING，没有任何人知道「已经卡了多久」。
 *
 * 本类把「当前状态 + 进入该状态的时间戳」收敛成唯一事实来源，并给出
 * [shouldForceRecovery] 判定，使 CONNECTING / RECONNECTING **不可能**成为终态。
 *
 * 不变量：从任何状态出发，若不再有外部事件，客户端必须在有界时间内到达
 * [ConnectionState.CONNECTED] 或 [ConnectionState.AUTH_FAILED]。
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    AUTH_FAILED
}

/**
 * 连接状态机。所有迁移都通过 [onXxx] 方法驱动，每次迁移都会（在状态真正变化时）
 * 回调 [onStateChange]，因此调用方无需再单独发状态。
 */
class ConnectionStateMachine(
    private val onStateChange: ((ConnectionState) -> Unit)? = null,
) {

    /** CONNECTING / RECONNECTING 都是「中间态」：绝不允许无限期保持。 */
    private val transientStates = setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING)

    @Volatile
    var current: ConnectionState = ConnectionState.DISCONNECTED
        private set

    /** 进入 [current] 的时刻（毫秒）。卡死判定的唯一依据。 */
    @Volatile
    var enteredAtMs: Long = 0L
        private set

    /** 当前状态已经保持了多久。 */
    fun timeInStateMs(nowMs: Long): Long = (nowMs - enteredAtMs).coerceAtLeast(0L)

    fun isConnected(): Boolean = current == ConnectionState.CONNECTED

    fun isConnecting(): Boolean = current == ConnectionState.CONNECTING

    /** 处于中间态——即「尚未尘埃落定」。 */
    fun isTransient(): Boolean = current in transientStates

    /** 已到达终态：CONNECTED 或 AUTH_FAILED。 */
    fun isSettled(): Boolean = !isTransient()

    /** 发起一次连接尝试：进入 CONNECTING。 */
    fun onConnectStarted(nowMs: Long) = publish(ConnectionState.CONNECTING, nowMs)

    /**
     * 传输层断开（onClosing / onClosed / onFailure / 握手看门狗 / 心跳失联）。
     *
     * 旧实现在这里「只在曾连上时才通知」，是卡在 CONNECTING 的直接原因。
     * 新语义：CONNECTED → DISCONNECTED（让 UI 立刻看到掉线）；
     * CONNECTING / RECONNECTING 保持原状态（不假报 DISCONNECTED），
     * 由随后的 [onReconnectScheduled] 负责离开中间态——中间态永远不会是终点。
     */
    fun onTransportLost(nowMs: Long) {
        if (current == ConnectionState.CONNECTED) {
            publish(ConnectionState.DISCONNECTED, nowMs)
        }
    }

    /** 已排定一次重连（含「重连 job 已活跃、本次复用」的情况）。离开中间态、刷新卡死计时。 */
    fun onReconnectScheduled(nowMs: Long) {
        if (current == ConnectionState.AUTH_FAILED) return
        publish(ConnectionState.RECONNECTING, nowMs)
    }

    /** 收到 READY：握手完成，进入终态 CONNECTED。 */
    fun onReady(nowMs: Long) = publish(ConnectionState.CONNECTED, nowMs)

    /** 用户主动断开。 */
    fun onDisconnected(nowMs: Long) = publish(ConnectionState.DISCONNECTED, nowMs)

    /** 重连次数耗尽：进入终态 AUTH_FAILED。 */
    fun onAuthFailed(nowMs: Long) = publish(ConnectionState.AUTH_FAILED, nowMs)

    /**
     * FGS 看门狗 / 任何外部兜底是否应当介入强制恢复。
     *
     * @param retryPending 是否已有重连在途（重连 job 活跃）。有在途重试意味着状态机
     *   正在自行推进——退避延迟可能长达 [MAX_RECONNECT_HORIZON_MS]，此时强行介入
     *   只会打断正常重连、并且重置已累计的退避计数。因此只有「没有在途重试」
     *   才判定卡死；有在途重试时只在超过最大重连窗口后兜底（防重连 job 自身卡死）。
     *
     * 关键：CONNECTING 在旧实现里被无条件跳过，导致 [onClosing] 取消握手看门狗后
     * 无人可救。现在只要 CONNECTING 超时且无人在推进，就会返回 true。
     */
    fun shouldForceRecovery(nowMs: Long, retryPending: Boolean): Boolean {
        if (!isTransient()) return false
        val elapsed = timeInStateMs(nowMs)
        if (retryPending) return elapsed > MAX_RECONNECT_HORIZON_MS
        return elapsed > STUCK_IN_TRANSIENT_STATE_TIMEOUT_MS
    }

    private fun publish(next: ConnectionState, nowMs: Long) {
        val previous = current
        if (previous == next) {
            // 同状态重复迁移：不重复回调，但**不刷新** enteredAtMs。
            // 否则「卡在 CONNECTING」会被反复重置计时，兜底永远不触发。
            return
        }
        current = next
        enteredAtMs = nowMs
        onStateChange?.invoke(next)
    }

    companion object {
        /** 无在途重试时，中间态停留超过此时长即判定卡死（握手看门狗同为 30s）。 */
        const val STUCK_IN_TRANSIENT_STATE_TIMEOUT_MS = 30_000L

        /** 有在途重试时允许的最大重连窗口（覆盖退避上限 + 握手超时 + 余量）。 */
        const val MAX_RECONNECT_HORIZON_MS = 90_000L
    }
}

/**
 * 连接失败 / 卡死时的恢复决策（纯逻辑）。
 *
 * 单独抽出来的原因：`scheduleReconnect()` 在「重连 job 已活跃」时会早退并且
 * **不发出任何状态**，旧实现因此让 `connect()` 失败路径在 attempt 0..4 期间
 * 完全没有状态迁移，UI 停在 CONNECTING。把决策抽成纯函数后，可以在无协程、
 * 无 Android 的 JVM 单测里覆盖这条早退路径。
 */
object ConnectionRecoveryPolicy {

    /**
     * `connect()` 抛异常后的处理决策。
     *
     * @param attempt 已排定的重连次数（含本次将排定的）。
     * @param retryPending 是否已有重连 job 活跃。
     */
    fun onConnectFailed(
        state: ConnectionState,
        attempt: Int,
        retryPending: Boolean,
        maxAttempts: Int,
    ): ConnectFailureOutcome = when {
        attempt > maxAttempts -> ConnectFailureOutcome.GiveUp
        retryPending -> ConnectFailureOutcome.WaitForScheduledRetry
        else -> ConnectFailureOutcome.ScheduleRetry
    }

    /**
     * 传输层关闭后的处理决策。
     *
     * onClosing / onClosed / onFailure 三个回调必须走**同一条**决策路径。
     * 旧实现让 [onClosing] 少了 scheduleReconnect()，这是「永久连接中」的起点。
     */
    fun onTransportClosed(): TransportCloseOutcome = TransportCloseOutcome.ScheduleReconnect

    /** 排定重连时使用的退避时长（第 attempt 次）。 */
    fun backoffMs(attempt: Int, baseMs: Long, maxDelayMs: Long): Long =
        (attempt * baseMs).coerceAtLeast(0L).coerceAtMost(maxDelayMs)
}

/** [ConnectionRecoveryPolicy.onConnectFailed] 的结论。 */
sealed interface ConnectFailureOutcome {
    /** 排定一次新的重连（会发出 RECONNECTING）。 */
    data object ScheduleRetry : ConnectFailureOutcome

    /** 重连 job 已活跃，本次复用：仍须确保状态已离开 CONNECTING。 */
    data object WaitForScheduledRetry : ConnectFailureOutcome

    /** 重连次数耗尽：进入终态 AUTH_FAILED。 */
    data object GiveUp : ConnectFailureOutcome
}

/** [ConnectionRecoveryPolicy.onTransportClosed] 的结论。 */
sealed interface TransportCloseOutcome {
    /** 必须排定重连（幂等，重复调用安全）。 */
    data object ScheduleReconnect : TransportCloseOutcome
}
