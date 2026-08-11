package com.lianyu.ai.feature.qqbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * QQ Bot 前台服务。
 *
 * 负责在应用切到后台后维持 WebSocket 长连接，接收 QQ 消息事件。
 * Android 15 前台服务 6 小时超时后，需在 onTimeout 中重新调度。
 *
 * 持自有 PARTIAL_WAKE_LOCK（90s 续租，超时 4 分钟）：不依赖微信/保活服务的
 * 进程级锁。QQ 单独登录、其它服务被杀时，锁仍保证 CPU 不睡 → 不触发 Doze
 * 网络限制 → WebSocket 不被冻结。
 */
class QQBotForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        renewWakeLock()
        startWakeLockRenewer()

        serviceScope.launch {
            try {
                val repository = QQBotServiceLocator.messageRepository(this@QQBotForegroundService)
                Log.i(TAG, "Connecting QQ Bot...")
                repository.connect()
                Log.i(TAG, "QQ Bot WebSocket connect invoked")
                // [FIX] 在 Service 作用域内启动自动回复，避免 ViewModel 被回收后无法回复
                val bridge = QQBotServiceLocator.chatBridge(this@QQBotForegroundService)
                bridge.start()
                Log.i(TAG, "QQ Bot chat bridge started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect QQ Bot", e)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        wakeLockJob?.cancel()
        runCatching {
            QQBotServiceLocator.chatBridge(this).stop()
            QQBotServiceLocator.messageRepository(this).disconnect()
        }
        serviceScope.cancel()
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
        start(this)
    }

    /** 释放旧锁 + 申请新锁：任意 Android 版本都保证重置超时（同 WeChatPollingService）。 */
    private fun renewWakeLock() {
        releaseWakeLock()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:qqbot-websocket",
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (error: Exception) {
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
    }

    private fun startWakeLockRenewer() {
        if (wakeLockJob?.isActive == true) return
        wakeLockJob = serviceScope.launch {
            while (isActive) {
                delay(WAKE_LOCK_RENEW_INTERVAL_MS)
                renewWakeLock()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "QQ 机器人",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 QQ 机器人消息通道在线"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QQ 机器人运行中")
            .setContentText("正在接收 QQ 消息")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "QQBotFgService"
        private const val CHANNEL_ID = "qqbot_foreground"
        private const val NOTIFICATION_ID = 0x7162

        /** 锁超时 4 分钟，续租间隔 90s（留 2.6 倍余量，覆盖续租协程调度抖动）。 */
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 90 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, QQBotForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: Exception) {
                // Android 12+ 后台启动 FGS 抛 ForegroundServiceStartNotAllowedException；
                // 失败由 ViewModel 重连 / 手动打开页面兜底。
                Log.w(TAG, "start FGS from background rejected: ${error.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QQBotForegroundService::class.java))
        }
    }
}
