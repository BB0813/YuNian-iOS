package com.lianyu.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.domain.AutomationTickProvider
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.feature.notification.CompanionKeepAliveService
import com.lianyu.ai.feature.notification.CompanionMessageWorker
import com.lianyu.ai.feature.qqbot.service.QQBotForegroundService
import com.lianyu.ai.feature.qqbot.service.QQBotServiceLocator
import com.lianyu.ai.feature.wechat.service.WeChatChannelKeeper
import com.lianyu.ai.feature.wechat.service.WeChatChannelRuntime
import com.lianyu.ai.feature.wechat.service.WeChatServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AlarmManager 定时保活心跳（Doze 下唯一可靠的进程外唤醒通道）。
 *
 * 为什么需要：
 * 1. WorkManager / JobScheduler 在 Doze 下会延迟到维护窗口（可能数小时）；
 *    allowWhileIdle 闹钟在深度 Doze 下每 ~9 分钟仍会准时送达。
 * 2. Android 12+ 后台启动 FGS 受限：FGS 被杀后 onDestroy/onTimeout 的自重启
 *    大多抛 ForegroundServiceStartNotAllowedException 被吞掉，通道静默死亡。
 *    本心跳周期性重试拉起 FGS（厂商白名单 / 预算可用时能成功）。
 * 3. Android 15 dataSync 6h 预算耗尽期间（此时 FGS 无论如何都起不来），
 *    心跳仍可在窗口内直接执行兜底轮询：收消息、发通知、排空出站，
 *    让微信保持"活着"直到用户重新打开 App 重置预算。
 *
 * 安全约束：
 * - 每次心跳整体在 goAsync 窗口内（TimeoutBudgets.BROADCAST_GOASYNC_MS 预留余量），
 *   超时即放弃本轮，绝不让系统因广播超时杀掉进程（微信/QQ 同进程同死）。
 * - 主轮询存活且非 stale 时跳过兜底轮询（shouldSkipFallbackPoll），
 *   避免与 FGS 双路 getUpdates 抢占。
 * - 自身持有短时 PARTIAL_WAKE_LOCK，保证窗口内 CPU 不睡、网络可用。
 */
class KeepAliveAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != KeepAliveAlarmScheduler.ACTION_KEEP_ALIVE) return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            var wakeLock: PowerManager.WakeLock? = null
            try {
                wakeLock = acquireWakeLock(context)
                // 先重排下一次心跳：即使本轮窗口内进程被杀/超时，通道仍有下一次恢复机会
                KeepAliveAlarmScheduler.scheduleNext(context)
                withTimeoutOrNull(TimeoutBudgets.BROADCAST_GOASYNC_MS - 500L) {
                    performKeepAliveCheck(context.applicationContext)
                }
            } catch (e: Exception) {
                SecureLog.w(TAG, "keep-alive tick failed: ${e.message}")
            } finally {
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                runCatching { pendingResult.finish() }
                scope.cancel()
            }
        }
    }

    private suspend fun performKeepAliveCheck(app: Context) {
        // 1. 微信通道：看门狗自愈（清僵死 lease）→ 兜底轮询 → 尽力拉起 FGS
        runCatching { WeChatChannelKeeper.healIfNeeded(app) }
            .onFailure { SecureLog.w(TAG, "wechat heal failed: ${it.message}") }

        val repo = WeChatServiceLocator.messageRepository(app)
        if (repo.isLoggedIn() && !WeChatChannelRuntime.shouldSkipFallbackPoll()) {
            val result = withTimeoutOrNull(PROBE_POLL_MS) {
                repo.pollMessages()
            }
            when {
                result == null -> SecureLog.d(TAG, "probe poll timed out (channel idle)")
                result.isFailure -> {
                    WeChatChannelRuntime.onPollFailure(result.exceptionOrNull()?.message)
                    SecureLog.w(TAG, "probe poll failed: ${result.exceptionOrNull()?.message}")
                }
                else -> {
                    WeChatChannelRuntime.onPollSuccess()
                    val count = result.getOrNull()?.messages?.size ?: 0
                    if (count > 0) SecureLog.d(TAG, "probe poll delivered $count messages")
                }
            }
        }

        runCatching { WeChatChannelKeeper.ensureRunning(app) }
            .onFailure { SecureLog.w(TAG, "wechat ensureRunning failed: ${it.message}") }

        // 2. 保活 FGS + 定时消息 Worker（保活 FGS 因 6h 预算死亡时的接力）
        runCatching { CompanionKeepAliveService.safeStart(app) }
            .onFailure { SecureLog.w(TAG, "keepalive restart failed: ${it.message}") }
        runCatching { CompanionMessageWorker.schedule(app) }
            .onFailure { SecureLog.w(TAG, "companion worker schedule failed: ${it.message}") }

        // 3. QQ Bot：已配置账号才拉起 FGS（避免幽灵通知）
        runCatching {
            if (QQBotServiceLocator.tokenStore(app).isLoggedIn()) {
                QQBotForegroundService.start(app)
            }
        }.onFailure { SecureLog.w(TAG, "qqbot restart failed: ${it.message}") }

        // 4. 自动化到点检查（原保活 FGS 每 60s 驱动，服务死亡时由本心跳兜底）
        runCatching {
            ServiceRegistry.get(AutomationTickProvider::class.java)?.onTick()
        }.onFailure { SecureLog.w(TAG, "automation tick failed: ${it.message}") }
    }

    private fun acquireWakeLock(context: Context): PowerManager.WakeLock? {
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "${context.packageName}:keepalive-alarm",
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MS)
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "KeepAliveAlarm"
        /** 兜底轮询上限：SDK 长轮询可达 35s，窗口内只能部分等待，超时即弃（消息下轮再取）。 */
        private const val PROBE_POLL_MS = 5_000L
        private const val WAKE_LOCK_MS = 30_000L
    }
}

/**
 * 心跳闹钟调度：每 8 分钟一次，自重复。
 *
 * - 精确闹钟（setExactAndAllowWhileIdle）：Doze 下准时送达，深度 Doze 每应用限 ~9 分钟一次，
 *   与 8 分钟间隔匹配。SCHEDULE_EXACT_ALARM 被拒/撤销时降级 setAndAllowWhileIdle。
 * - 闹钟不可跨重启保留：由启动路径重排（MainActivity）、周期 Job（IqooKeepAliveJobService）
 *   与每次心跳自身共同保证链条不断。
 */
object KeepAliveAlarmScheduler {

    const val ACTION_KEEP_ALIVE = "com.lianyu.ai.action.KEEP_ALIVE_TICK"

    /** 心跳间隔：深度 Doze 允许 while-idle 闹钟约 9 分钟一次，取 8 分钟留余量。 */
    private const val INTERVAL_MS = 8 * 60 * 1000L
    private const val REQUEST_CODE = 0x5A11
    private const val TAG = "KeepAliveAlarm"

    fun scheduleNext(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            app,
            REQUEST_CODE,
            Intent(app, KeepAliveAlarmReceiver::class.java).setAction(ACTION_KEEP_ALIVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        val exactAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

        runCatching {
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }.onFailure { e ->
            // 精确闹钟权限被撤销 / 特殊 ROM 拒绝：降级 while-idle（Doze 下仍每 ~9 分钟送达）
            SecureLog.w(TAG, "exact alarm rejected (${e.message}), fallback to allowWhileIdle")
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }.onFailure { inner ->
                SecureLog.w(TAG, "allowWhileIdle alarm failed: ${inner.message}")
            }
        }
        SecureLog.d(TAG, "heartbeat scheduled in ${INTERVAL_MS}ms (exact=$exactAllowed)")
    }
}
