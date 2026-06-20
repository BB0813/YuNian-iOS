package com.lianyu.ai.feature.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.WorkManager
import androidx.core.app.NotificationCompat
import com.lianyu.ai.feature.notification.R

open class CompanionKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var timedOut = false
    private val handler = Handler(Looper.getMainLooper())
    private val wakeLockRunnable = object : Runnable {
        override fun run() {
            acquireWakeLock()
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            // 仅当没有活跃的 CompanionMessageWorker 调度时才触发新调度。
            // enqueueUniqueWork(REPLACE) 虽然不会堆叠请求，但每次 REPLACE 会重置延迟，
            // 频繁 schedule 会覆盖用户在 ChatDetailScreen 里设置的大间隔（如 360 分钟→30 分钟）。
            val workMgr = WorkManager.getInstance(applicationContext)
            val hasActive = runCatching {
                workMgr.getWorkInfosForUniqueWork("companion_message_work").get()
                    .any { it.state == androidx.work.WorkInfo.State.ENQUEUED ||
                            it.state == androidx.work.WorkInfo.State.RUNNING }
            }.getOrDefault(false)
            if (!hasActive) {
                CompanionMessageWorker.schedule(applicationContext)
            }
            handler.postDelayed(this, 60 * 60 * 1000L) // 60 分钟（原 15 分钟过于频繁）
        }
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        handler.postDelayed(wakeLockRunnable, 5 * 60 * 1000L)
        handler.postDelayed(heartbeatRunnable, 60 * 60 * 1000L) // 首次延迟 60 分钟
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        timedOut = true
        // Android 15 前台服务 6 小时超时：必须先 stopForeground 再 stopSelf，
        // 否则 ColorOS / OriginOS 等激进 ROM 可能抛出异常或直接拒绝重启。
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // ignore cleanup errors
        }
        stopSelf(startId)
        // 超时后由 WorkManager 兜底重新调度，避免服务彻底停止。
        runCatching { CompanionMessageWorker.schedule(applicationContext) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(wakeLockRunnable)
        handler.removeCallbacks(heartbeatRunnable)
        releaseWakeLock()
        super.onDestroy()

        if (!timedOut) {
            start(applicationContext)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        start(applicationContext)
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LianYu::CompanionKeepAlive"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun createNotification(): Notification {
        val channelId = "keep_alive_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "后台运行服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持虚拟恋人消息推送服务运行"
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
            .setContentTitle("恋语")
            .setContentText("虚拟恋人正在守护你~")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent().setClassName(context.packageName, SHELL_SERVICE_CLASS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent().setClassName(context.packageName, SHELL_SERVICE_CLASS)
            context.stopService(intent)
        }

        private const val SHELL_SERVICE_CLASS = "com.lianyu.ai.security.SService"
    }
}
