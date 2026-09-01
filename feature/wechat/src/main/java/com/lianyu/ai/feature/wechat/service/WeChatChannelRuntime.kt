package com.lianyu.ai.feature.wechat.service

import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.domain.wechat.WeChatChannelHealthSnapshot
import com.lianyu.ai.domain.wechat.WeChatFailureReason
import com.lianyu.ai.domain.wechat.WeChatOutboxFailure
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.random.Random

/**
 * S4：微信通道运行时协调（进程内）。
 * S5：健康快照 + 失败原因码 + SecureLog。
 * S6：看门狗 — lastPoll 超时判定、僵死 lease 释放、heal 冷却。
 *
 * - 主轮询租约：FGS 持有且**非 stale** 时 Worker 跳过 getUpdates，避免双路抢 cursor
 * - 失败退避：指数退避 + 抖动，上限封顶
 * - 僵死检测：超过 [STALE_POLL_MS] 无 poll 活动则允许兜底 poll / 强制自愈
 */
object WeChatChannelRuntime {

    private val primaryPollerActive = AtomicBoolean(false)
    private val consecutiveFailures = AtomicInteger(0)
    private val lastPollAtMs = AtomicLong(0L)
    private val lastErrorAtMs = AtomicLong(0L)
    private val lastError = AtomicReference<String?>(null)
    private val primaryClaimedAtMs = AtomicLong(0L)
    private val lastHealAtMs = AtomicLong(0L)

    /** 看门狗巡检停摆检测：两次巡检间隔超过阈值视为进程被冻结 / FGS 被杀停摆 */
    private val lastWatchdogTickAtMs = AtomicLong(0L)
    private val watchdogStallCount = AtomicInteger(0)
    private val lastWatchdogStallMs = AtomicLong(0L)

    /** 会话轮换：定期强制重建 SDK 会话，兜底服务端静默停投（getUpdates 空响应无法区分"没消息"与"会话脱离"） */
    private val lastSessionRebuildAtMs = AtomicLong(0L)

    const val BASE_BACKOFF_MS = 2_000L
    const val MAX_BACKOFF_MS = 60_000L
    const val SUCCESS_IDLE_MS = 0L

    /**
     * 无 poll 成功/失败心跳则视为通道僵死。
     * 预算：长轮询 15s + 最大退避 60s + 余量 15s ≈ 90s。
     */
    const val STALE_POLL_MS = 90_000L

    /** 看门狗巡检间隔（FGS 内循环）。 */
    const val WATCHDOG_INTERVAL_MS = 60_000L

    /** 两次看门狗巡检超过该间隔即判定"停摆"（冻结/被杀），约 2.5 个周期。 */
    const val WATCHDOG_STALL_THRESHOLD_MS = 150_000L

    /** 会话轮换间隔：约 20 分钟强制重建一次 SDK 会话，防服务端静默停投。 */
    const val SESSION_REBUILD_INTERVAL_MS = 20 * 60 * 1000L

    /** 两次强制自愈最小间隔，防止 FGS/Worker 互踢。 */
    const val HEAL_COOLDOWN_MS = 30_000L

    private const val TAG = "WeChatRuntime"

    fun claimPrimaryPoller(): Boolean {
        val claimed = primaryPollerActive.compareAndSet(false, true)
        if (claimed) {
            primaryClaimedAtMs.set(System.currentTimeMillis())
            SecureLog.i(TAG, "primary_poller claimed")
        }
        return claimed
    }

    fun releasePrimaryPoller() {
        if (primaryPollerActive.getAndSet(false)) {
            primaryClaimedAtMs.set(0L)
            SecureLog.i(TAG, "primary_poller released")
        }
    }

    fun isPrimaryPollerActive(): Boolean = primaryPollerActive.get()

    /**
     * Worker 兜底是否应跳过本轮 getUpdates。
     * 主轮询存活且最近有 poll 活动时跳过；stale lease 不跳过，避免僵尸 FGS 堵死兜底。
     */
    fun shouldSkipFallbackPoll(nowMs: Long = System.currentTimeMillis()): Boolean {
        return isPrimaryPollerActive() && !isPollActivityStale(nowMs)
    }

    fun onPollSuccess() {
        consecutiveFailures.set(0)
        lastPollAtMs.set(System.currentTimeMillis())
        lastError.set(null)
    }

    fun onPollFailure(message: String? = null) {
        consecutiveFailures.incrementAndGet()
        lastErrorAtMs.set(System.currentTimeMillis())
        lastPollAtMs.set(System.currentTimeMillis())
        val reason = WeChatFailureReason.fromPollMessage(message)
        val summary = "${reason.wireName}: ${message.orEmpty().take(120)}"
        lastError.set(summary)
        SecureLog.w(TAG, "poll_failure failures=${consecutiveFailures.get()} $summary")
    }

    fun consecutiveFailures(): Int = consecutiveFailures.get()

    fun lastError(): String? = lastError.get()

    fun lastPollAtMs(): Long = lastPollAtMs.get()

    fun lastErrorAtMs(): Long = lastErrorAtMs.get()

    fun primaryClaimedAtMs(): Long = primaryClaimedAtMs.get()

    fun lastHealAtMs(): Long = lastHealAtMs.get()

    /**
     * 看门狗每次巡检调用：记录时间戳并检测停摆。
     * 正常巡检间隔为 [WATCHDOG_INTERVAL_MS]；间隔远超阈值说明进程被冻结（vivo/iQOO 后台冻结）
     * 或 FGS 被杀后长时间无人巡检——这正是"熄屏 2 分钟掉线"的可观测信号。
     *
     * @return 距上次巡检的间隔（毫秒）；首次巡检返回 0
     */
    fun onWatchdogTick(nowMs: Long = System.currentTimeMillis()): Long {
        val prev = lastWatchdogTickAtMs.getAndSet(nowMs)
        if (prev > 0L) {
            val gap = nowMs - prev
            if (gap > WATCHDOG_STALL_THRESHOLD_MS) {
                watchdogStallCount.incrementAndGet()
                lastWatchdogStallMs.set(gap)
                SecureLog.w(TAG, "watchdog_stall gapMs=$gap count=${watchdogStallCount.get()}")
            }
            return gap
        }
        return 0L
    }

    fun watchdogStallCount(): Int = watchdogStallCount.get()

    fun lastWatchdogStallMs(): Long = lastWatchdogStallMs.get()

    /**
     * 是否该轮换会话：距上次强制重建超过 [SESSION_REBUILD_INTERVAL_MS]。
     * 服务端可能在无消息期静默停投（getUpdates 返回空而非报错），
     * 定期重建以重新挂上投递通道。
     */
    fun shouldRotateSession(nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = lastSessionRebuildAtMs.get()
        return last == 0L || nowMs - last >= SESSION_REBUILD_INTERVAL_MS
    }

    fun markSessionRebuilt(nowMs: Long = System.currentTimeMillis()) {
        lastSessionRebuildAtMs.set(nowMs)
    }

    /**
     * 是否长时间没有 poll 心跳。
     * - 有 lastPoll：按 lastPoll 计
     * - 无 lastPoll 但已 claim 主租约：按 claim 时间计（启动后一直没跑完首轮）
     * - 无主租约且从未 poll：不算 stale（由 ensureRunning 负责拉起，避免误伤冷启动）
     */
    fun isPollActivityStale(nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = lastPollAtMs.get()
        if (last > 0L) {
            return nowMs - last >= STALE_POLL_MS
        }
        val claimedAt = primaryClaimedAtMs.get()
        if (claimedAt > 0L && primaryPollerActive.get()) {
            return nowMs - claimedAt >= STALE_POLL_MS
        }
        return false
    }

    /**
     * 看门狗决策：是否需要释放僵死 lease / 重新 ensureRunning。
     */
    fun evaluateWatchdog(nowMs: Long = System.currentTimeMillis()): WatchdogDecision {
        val primary = isPrimaryPollerActive()
        val stale = isPollActivityStale(nowMs)
        return when {
            primary && stale -> WatchdogDecision(
                needsAction = true,
                forceReleasePrimary = true,
                reason = "primary_stale ageMs=${nowMs - (lastPollAtMs.get().takeIf { it > 0 } ?: primaryClaimedAtMs.get())}",
            )
            !primary && stale -> WatchdogDecision(
                needsAction = true,
                forceReleasePrimary = false,
                reason = "fallback_stale ageMs=${nowMs - lastPollAtMs.get()}",
            )
            else -> WatchdogDecision(
                needsAction = false,
                forceReleasePrimary = false,
                reason = if (primary) "healthy_primary" else "healthy_or_idle",
            )
        }
    }

    /**
     * 尝试进入自愈冷却窗口。
     * @return true 表示允许执行本次 heal
     */
    fun tryBeginHeal(nowMs: Long = System.currentTimeMillis()): Boolean {
        while (true) {
            val last = lastHealAtMs.get()
            if (last > 0L && nowMs - last < HEAL_COOLDOWN_MS) {
                return false
            }
            if (lastHealAtMs.compareAndSet(last, nowMs)) {
                return true
            }
        }
    }

    /**
     * 计算下一次轮询前等待。成功路径返回 [SUCCESS_IDLE_MS]（长轮询本身已阻塞）。
     */
    fun nextBackoffMs(isTimeout: Boolean = false, isConnection: Boolean = false): Long {
        val failures = consecutiveFailures.get().coerceAtLeast(1)
        val base = when {
            isTimeout -> BASE_BACKOFF_MS
            isConnection -> BASE_BACKOFF_MS * 2
            else -> BASE_BACKOFF_MS
        }
        val exp = min(MAX_BACKOFF_MS, base * (1L shl (failures - 1).coerceAtMost(5)))
        val jitter = Random.nextLong(0, (exp / 5).coerceAtLeast(1))
        return min(MAX_BACKOFF_MS, exp + jitter)
    }

    /**
     * S5：聚合 Runtime + Outbox 计数为健康快照（供设置页）。
     */
    fun healthSnapshot(
        openOutboxCount: Int = 0,
        pendingOutboxCount: Int = 0,
        failedOutboxCount: Int = 0,
        sendingOutboxCount: Int = 0,
        recentFailures: List<WeChatOutboxFailure> = emptyList(),
    ): WeChatChannelHealthSnapshot {
        return WeChatChannelHealthSnapshot(
            primaryPollerActive = isPrimaryPollerActive(),
            consecutiveFailures = consecutiveFailures(),
            lastPollAtMs = lastPollAtMs(),
            lastErrorAtMs = lastErrorAtMs(),
            lastError = lastError(),
            openOutboxCount = openOutboxCount,
            pendingOutboxCount = pendingOutboxCount,
            failedOutboxCount = failedOutboxCount,
            sendingOutboxCount = sendingOutboxCount,
            recentFailures = recentFailures,
            updatedAtMs = System.currentTimeMillis(),
            watchdogStallCount = watchdogStallCount(),
            lastWatchdogStallMs = lastWatchdogStallMs(),
        )
    }

    /** 测试 / 登出时重置。 */
    fun reset() {
        primaryPollerActive.set(false)
        consecutiveFailures.set(0)
        lastPollAtMs.set(0L)
        lastErrorAtMs.set(0L)
        lastError.set(null)
        primaryClaimedAtMs.set(0L)
        lastHealAtMs.set(0L)
        lastWatchdogTickAtMs.set(0L)
        watchdogStallCount.set(0)
        lastWatchdogStallMs.set(0L)
        lastSessionRebuildAtMs.set(0L)
    }

    data class WatchdogDecision(
        val needsAction: Boolean,
        val forceReleasePrimary: Boolean,
        val reason: String,
    )
}
