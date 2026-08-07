package com.lianyu.ai.feature.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * 自动化到点通知（自建渠道，feature 间禁止互相依赖）。
 */
object AutomationNotifier {
    private const val CHANNEL_ID = "automation_channel"
    // 避开 feature:notification/NotificationHelper 的 companionId * 1000 + timeInt
    // 通知 ID 区间（0 ~ N*1000+999）。同一伴侣多个自动化仍互相替换，可接受。
    private const val NOTIFICATION_ID_BASE = 2_000_000

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动化提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "AI 设置的定时自动化到点提醒" }
        manager.createNotificationChannel(channel)
    }

    fun show(context: Context, title: String, content: String, companionId: Long) {
        ensureChannel(context)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_BASE + (companionId % 1000).toInt(), notification)
    }
}
