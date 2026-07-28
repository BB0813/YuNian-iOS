package com.lianyu.ai.feature.wechat.service

import android.content.Context
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.feature.wechat.data.WeChatTokenStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 微信通道保活入口：登录态下统一拉起 FGS 主轮询 + WorkManager 兜底。
 *
 * 调用方：Application 冷启动、BootReceiver、登录成功、前台 onResume、
 * FGS onTimeout、FGS/Worker 看门狗。
 */
object WeChatChannelKeeper {

    private const val TAG = "WeChatChannelKeeper"
    private val mutex = Mutex()

    /**
     * 若已登录则确保主通道与兜底任务都在跑。
     * @return true 表示已登录并完成拉起尝试
     */
    suspend fun ensureRunning(context: Context): Boolean = mutex.withLock {
        ensureRunningLocked(context.applicationContext)
    }

    /**
     * 看门狗自愈：lastPoll 超时或主租约僵死时，释放 lease 并重新拉起通道。
     * 带 [WeChatChannelRuntime.HEAL_COOLDOWN_MS] 冷却，避免 FGS/Worker 互踢。
     *
     * @return true 表示执行了一次自愈拉起
     */
    suspend fun healIfNeeded(context: Context): Boolean = mutex.withLock {
        val app = context.applicationContext
        val loggedIn = runCatching {
            WeChatTokenStore(app).isLoggedIn()
        }.getOrDefault(false)
        if (!loggedIn) {
            return false
        }

        val decision = WeChatChannelRuntime.evaluateWatchdog()
        if (!decision.needsAction) {
            return false
        }
        if (!WeChatChannelRuntime.tryBeginHeal()) {
            SecureLog.d(TAG, "heal skipped cooldown: ${decision.reason}")
            return false
        }

        if (decision.forceReleasePrimary) {
            WeChatChannelRuntime.releasePrimaryPoller()
            SecureLog.w(TAG, "watchdog released stale primary: ${decision.reason}")
        } else {
            SecureLog.w(TAG, "watchdog heal: ${decision.reason}")
        }
        ensureRunningLocked(app)
        true
    }

    /** 登出时停止 FGS 与全部轮询 Worker。 */
    fun stop(context: Context) {
        val app = context.applicationContext
        runCatching { WeChatPollingService.stop(app) }
        runCatching { WeChatPollingWorker.cancel(app) }
        WeChatChannelRuntime.reset()
        SecureLog.i(TAG, "channel stopped")
    }

    private suspend fun ensureRunningLocked(app: Context): Boolean {
        val loggedIn = runCatching {
            WeChatTokenStore(app).isLoggedIn()
        }.getOrDefault(false)
        if (!loggedIn) {
            SecureLog.d(TAG, "ensureRunning skipped: not logged in")
            return false
        }
        runCatching { WeChatPollingService.start(app) }
            .onFailure { SecureLog.w(TAG, "start FGS failed: ${it.message}") }
        runCatching { WeChatPollingWorker.schedule(app) }
            .onFailure { SecureLog.w(TAG, "schedule periodic worker failed: ${it.message}") }
        runCatching { WeChatPollingWorker.scheduleImmediate(app) }
            .onFailure { SecureLog.w(TAG, "schedule immediate worker failed: ${it.message}") }
        SecureLog.i(TAG, "ensureRunning: FGS + WM requested")
        return true
    }
}
