package com.lianyu.ai.feature.wechat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.feature.wechat.R
import com.lianyu.ai.feature.wechat.WeChatDebugLog
import com.lianyu.ai.feature.wechat.data.WeChatMessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微信 ilink 长轮询前台服务（S4：主监督循环 + S6 看门狗）。
 *
 * 职责：
 * 1. 登录后以 ForegroundService 持有主轮询租约，实时 pollMessages()
 * 2. 消息到达后由 Repository 处理（热更新 contextToken、Inbox、DialoguePort）
 * 3. 进程内看门狗：lastPoll 超时则释放僵死 lease 并 ensureRunning
 * 4. WorkManager 在 FGS 死亡 / stale 时兜底（见 [WeChatChannelRuntime]）
 *
 * 启动时机：WeChatChannelKeeper / 登录成功；注销时停止。
 */
open class WeChatPollingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var watchdogJob: Job? = null
    private var holdsPrimaryLease = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val watchdogStarted = AtomicBoolean(false)
    private val pollingStarted = AtomicBoolean(false)

    /** 防抖：onTimeout/onDestroy 自重启最小间隔，避免系统反复杀→重启→杀的循环 */
    @Volatile
    private var lastRestartAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        WeChatDebugLog.log("[PollingService] onCreate pid=${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        renewWakeLock()

        if (pollJob == null || pollJob?.isActive != true) {
            startPolling()
        }
        startWatchdog()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户划掉任务后立即自重启：部分厂商 ROM 无视 stopWithTask=false 会顺带杀掉服务
        runCatching {
            if (WeChatServiceLocator.tokenStore(applicationContext).isLoggedInSync()) {
                scheduleRestartWithDebounce()
            }
        }
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        Log.w(TAG, "Foreground service timeout reached, restarting immediately")
        releasePrimaryLease()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // ignore cleanup errors
        }
        stopSelf(startId)
        // 立即自重启（照搬 QQBotForegroundService.onTimeout 模式），
        // 而非仅靠 Worker 兜底——Worker 在 Doze 下不可靠，会导致熄屏 2 分钟掉线
        scheduleRestartWithDebounce()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        pollJob?.cancel()
        watchdogJob?.cancel()
        releasePrimaryLease()
        releaseWakeLock()
        // 仍登录时立即自重启（恢复进程内常驻 poll，Worker 在 Doze 下不可靠）
        runCatching {
            val loggedIn = WeChatServiceLocator.tokenStore(applicationContext).isLoggedInSync()
            if (loggedIn) {
                scheduleRestartWithDebounce()
                WeChatPollingWorker.schedule(applicationContext)
                WeChatPollingWorker.scheduleImmediate(applicationContext)
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 带超时的 PARTIAL_WAKE_LOCK：每轮 poll / 看门狗续租，避免无限持锁激怒厂商策略。
     * 超时 ≈ 长轮询 + 最大退避 + 余量。
     *
     * 续租采用「释放旧锁 + 申请新锁」而非复用同一锁对象重复 acquire(timeout)：
     * 部分 Android 版本对已持有的非引用计数锁重复 acquire(timeout) 不重置超时，
     * 首个 timeout runnable 到点仍会释放锁 → 锁在 135s（≈熄屏"两分钟"）后过期，
     * CPU 休眠 → 微信+QQ 同进程同停，且与设备/ROM 无关（AOSP 层行为）。
     */
    private fun renewWakeLock() {
        releaseWakeLock()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:wechat-polling",
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            wakeLock = lock
            Log.d(TAG, "Partial wake lock acquired for ${WAKE_LOCK_TIMEOUT_MS}ms")
        } catch (error: Exception) {
            // acquire 失败（罕见）：wakeLock 保持 null，下一轮 poll/看门狗续租时重试
            Log.w(TAG, "Wake lock acquire failed: ${error.message}")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            try {
                if (lock.isHeld) lock.release()
            } catch (_: Exception) {
            }
        }
        wakeLock = null
        Log.i(TAG, "Partial wake lock released")
    }

    private fun startWatchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) return
        watchdogJob = serviceScope.launch {
            try {
                while (isActive) {
                    delay(WeChatChannelRuntime.WATCHDOG_INTERVAL_MS)
                    // 巡检心跳：返回距上次巡检的间隔，用于检测进程冻结 / FGS 停摆（熄屏掉线取证）
                    val gapMs = WeChatChannelRuntime.onWatchdogTick()
                    renewWakeLock()
                    // 会话自愈：间隙后或到期轮换时强制重建 SDK 会话。
                    // 服务端在无消息/断连期可能静默停投（getUpdates 返回空而非报错），
                    // 仅重启轮询不恢复投递；重建会话（resume）重新挂上投递通道。
                    val rebuildReason = when {
                        gapMs > WeChatChannelRuntime.WATCHDOG_STALL_THRESHOLD_MS -> "watchdog-gap gapMs=$gapMs"
                        WeChatChannelRuntime.shouldRotateSession() -> "session-rotate"
                        else -> null
                    }
                    if (rebuildReason != null) {
                        runCatching {
                            WeChatServiceLocator.sdkClientManager(applicationContext)
                                .requestForceRebuild(rebuildReason)
                            WeChatChannelRuntime.markSessionRebuilt()
                            Log.w(TAG, "Session rebuilt: $rebuildReason")
                        }.onFailure { Log.w(TAG, "Session rebuild failed: ${it.message}") }
                    }
                    val decision = WeChatChannelRuntime.evaluateWatchdog()
                    if (!decision.needsAction) {
                        continue
                    }
                    Log.w(TAG, "Watchdog action: ${decision.reason}")
                    WeChatDebugLog.log("[PollingService] Watchdog ACTION: ${decision.reason} forceRelease=${decision.forceReleasePrimary}")
                    if (decision.forceReleasePrimary && holdsPrimaryLease) {
                        // 僵死主循环：放 lease，取消旧 job，重新抢租约 poll
                        releasePrimaryLease()
                        pollJob?.cancel()
                        pollJob = null
                        startPolling()
                    }
                    runCatching { WeChatChannelKeeper.healIfNeeded(applicationContext) }
                        .onFailure { Log.w(TAG, "Watchdog heal failed: ${it.message}") }
                }
            } finally {
                watchdogStarted.set(false)
            }
        }
        Log.i(TAG, "Watchdog started interval=${WeChatChannelRuntime.WATCHDOG_INTERVAL_MS}ms")
        WeChatDebugLog.log("[PollingService] Watchdog started")
    }

    private fun startPolling() {
        if (!pollingStarted.compareAndSet(false, true)) return
        pollJob = serviceScope.launch {
            try {
                val claimed = WeChatChannelRuntime.claimPrimaryPoller()
                holdsPrimaryLease = claimed
                if (!claimed) {
                    Log.w(TAG, "Primary poller already claimed; this service instance will not poll")
                    // 若对方已 stale，看门狗/Worker 会释放；此处仍续租 CPU 等待
                    return@launch
                }
                Log.i(TAG, "Primary poller lease acquired")
                WeChatDebugLog.log("[PollingService] Primary lease acquired, starting poll loop")

                val repository = WeChatServiceLocator.messageRepository(applicationContext)
                var pollCount = 0

                while (isActive) {
                    if (!repository.isLoggedIn()) {
                        Log.d(TAG, "Not logged in, stop polling")
                        WeChatDebugLog.log("[PollingService] Not logged in, stopping")
                        stopSelf()
                        return@launch
                    }

                    renewWakeLock()
                    pollCount++

                    try {
                        Log.d(TAG, "Polling messages...")
                        val result = repository.pollMessages(timeoutMs = TimeoutBudgets.WECHAT_POLL_TIMEOUT_MS)
                        if (result.isFailure) {
                            val error = result.exceptionOrNull()
                            val msg = error?.message.orEmpty()
                            Log.w(TAG, "Poll failed: $msg")
                            WeChatDebugLog.log("[PollingService] Poll#$pollCount FAILED: $msg")
                            WeChatChannelRuntime.onPollFailure(msg)
                            // 失败时仍尝试 drain 出站，避免入站故障拖死 App→微信
                            runCatching {
                                val drained = repository.drainOutbox()
                                if (drained > 0) Log.d(TAG, "Outbox drained $drained after poll failure")
                            }
                            val delayMs = WeChatChannelRuntime.nextBackoffMs(
                                isTimeout = msg.contains("timeout", ignoreCase = true),
                                isConnection = msg.contains("connection", ignoreCase = true),
                            )
                            Log.d(TAG, "Backoff ${delayMs}ms (failures=${WeChatChannelRuntime.consecutiveFailures()})")
                            delay(delayMs)
                        } else {
                            WeChatChannelRuntime.onPollSuccess()
                            val messages = result.getOrNull()?.messages.orEmpty()
                            if (messages.isNotEmpty()) {
                                Log.d(TAG, "Received ${messages.size} messages")
                                WeChatDebugLog.log("[PollingService] Poll#$pollCount OK messages=${messages.size}")
                            } else if (pollCount % 10 == 0) {
                                WeChatDebugLog.log("[PollingService] Poll#$pollCount OK empty (heartbeat)")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Polling error", e)
                        WeChatDebugLog.log("[PollingService] Poll#$pollCount EXCEPTION: ${e.message}")
                        WeChatChannelRuntime.onPollFailure(e.message)
                        runCatching { repository.drainOutbox() }
                        delay(WeChatChannelRuntime.nextBackoffMs())
                    }
                }
            } finally {
                pollingStarted.set(false)
            }
        }
    }

    private fun releasePrimaryLease() {
        if (holdsPrimaryLease) {
            WeChatChannelRuntime.releasePrimaryPoller()
            holdsPrimaryLease = false
            Log.i(TAG, "Primary poller lease released")
        }
    }

    /**
     * 带防抖的自重启：10 秒内不重复 startForegroundService，
     * 避免厂商 ROM 反复杀 FGS → 重启 → 再杀的恶性循环。
     */
    private fun scheduleRestartWithDebounce() {
        val now = System.currentTimeMillis()
        if (now - lastRestartAtMs < RESTART_DEBOUNCE_MS) {
            Log.w(TAG, "Restart debounced (last=${now - lastRestartAtMs}ms ago)")
            return
        }
        lastRestartAtMs = now
        WeChatPollingService.start(applicationContext)
        Log.i(TAG, "Scheduled immediate restart")
    }

    private fun createNotification(): Notification {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent(Intent.ACTION_MAIN).apply {
            `package` = packageName
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("微信通道")
            .setContentText("正在实时接收微信消息...")
            .setSmallIcon(R.drawable.ic_wechat_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "WeChatPollingService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "wechat_polling"
        private const val CHANNEL_NAME = "微信消息轮询"
        private const val CHANNEL_DESCRIPTION = "保持微信消息实时接收"
        /** 长轮询 + 最大退避 + 3min 余量（看门狗每 60s 续租，留足调度抖动窗口）。覆盖 FGS 自重启窗口。 */
        private val WAKE_LOCK_TIMEOUT_MS =
            TimeoutBudgets.WECHAT_POLL_TIMEOUT_MS + WeChatChannelRuntime.MAX_BACKOFF_MS + 180_000L

        /** onTimeout/onDestroy 自重启防抖间隔，避免厂商 ROM 反复杀→重启循环。 */
        private const val RESTART_DEBOUNCE_MS = 10_000L

        fun start(context: Context) {
            val intent = Intent().setClassName(context.packageName, SHELL_SERVICE_CLASS)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: Exception) {
                // Android 12+ 后台启动 FGS 会抛 ForegroundServiceStartNotAllowedException。
                // 自重启路径（onDestroy/onTimeout）在熄屏后台运行时可能命中，
                // 若不加保护会直接崩溃整个进程（微信+QQ 同进程同死）。
                // 失败不致命：Worker / 看门狗 / onResume 兜底会再次拉起。
                Log.w(TAG, "start FGS from background rejected: ${error.message}")
            }
            // Fallback: schedule immediate Worker when FGS restart is blocked (EMUI)
            try {
                WeChatPollingWorker.scheduleImmediate(context)
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            val intent = Intent().setClassName(context.packageName, SHELL_SERVICE_CLASS)
            context.stopService(intent)
        }

        private const val SHELL_SERVICE_CLASS = "com.lianyu.ai.security.SWechatPollingService"
    }
}
