package com.lianyu.ai.database.cleanup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * 数据清理策略 — 三级存储架构的维护层。
 *
 * 策略：
 * - 单会话保留最近 5000 条消息，超出部分删除
 * - 清理后执行 VACUUM 回收空间（仅在闲时）
 * - 通过 WorkManager 定期执行（每日一次）
 * - App 启动时也触发一次（如果距上次清理超过 24 小时）
 *
 * 注意：VACUUM 操作耗时，仅在设备空闲时执行。
 */
object DataCleanupManager {

    private const val TAG = "DataCleanupManager"
    private const val WORK_NAME = "lianyu_data_cleanup"
    private const val MAX_MESSAGES_PER_SESSION = 5000
    private const val CLEANUP_INTERVAL_HOURS = 24L

    private const val PREF_NAME = "data_cleanup"
    private const val KEY_LAST_CLEANUP = "last_cleanup_time"

    /**
     * 注册定期清理任务（在 Application.onCreate 中调用）。
     */
    fun schedulePeriodicCleanup(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true)
            .build()

        val request = PeriodicWorkRequestBuilder<DataCleanupWorker>(
            CLEANUP_INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        SecureLog.i(TAG, "Periodic data cleanup scheduled every $CLEANUP_INTERVAL_HOURS hours")
    }

    /**
     * 检查是否需要清理（距上次超过 24 小时），如果是则立即执行。
     * 在 App 启动或进入后台时调用。
     */
    suspend fun cleanupIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lastCleanup = prefs.getLong(KEY_LAST_CLEANUP, 0L)
        val now = System.currentTimeMillis()

        if (now - lastCleanup < CLEANUP_INTERVAL_HOURS * 60 * 60 * 1000L) return

        SecureLog.i(TAG, "Starting data cleanup (last cleanup was ${now - lastCleanup}ms ago)")
        performCleanup(context)
        prefs.edit().putLong(KEY_LAST_CLEANUP, now).apply()
    }

    /**
     * 执行实际清理操作。
     */
    private suspend fun performCleanup(context: Context) {
        val db = AppDatabase.getDatabase(context)
        val chatDao = db.chatMessageDao()
        val groupDao = db.groupMessageDao()

        try {
            // ── 单聊：清理每个会话超出 5000 条的旧消息 ──
            val companionIds = chatDao.getDistinctCompanionIds()
            var totalDeleted = 0
            for (companionId in companionIds) {
                val count = chatDao.getMessageCountForCompanion(companionId)
                if (count > MAX_MESSAGES_PER_SESSION) {
                    val deleted = chatDao.deleteOldMessagesForCompanion(
                        companionId,
                        count - MAX_MESSAGES_PER_SESSION
                    )
                    totalDeleted += deleted
                    SecureLog.i(TAG, "Cleaned $deleted old messages for companion $companionId (had $count)")
                }
            }

            // ── 群聊：同理 ──
            val groupIds = groupDao.getDistinctGroupIds()
            for (groupId in groupIds) {
                val count = groupDao.getMessageCount(groupId)
                if (count > MAX_MESSAGES_PER_SESSION) {
                    val deleted = groupDao.deleteOldMessagesForGroup(
                        groupId,
                        count - MAX_MESSAGES_PER_SESSION
                    )
                    totalDeleted += deleted
                    SecureLog.i(TAG, "Cleaned $deleted old messages for group $groupId (had $count)")
                }
            }

            // ── VACUUM 回收空间（仅在删除了消息时） ──
            if (totalDeleted > 0) {
                SecureLog.i(TAG, "Total deleted: $totalDeleted messages, running VACUUM...")
                db.openHelper.writableDatabase.execSQL("VACUUM")
                SecureLog.i(TAG, "VACUUM completed")
            } else {
                SecureLog.i(TAG, "No cleanup needed")
            }

            // ── WAL checkpoint ──
            db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        } catch (e: Exception) {
            SecureLog.e(TAG, "Data cleanup failed", e)
        }
    }
}

/**
 * WorkManager Worker — 定期执行数据清理。
 */
class DataCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            DataCleanupManager.cleanupIfNeeded(applicationContext)
            Result.success()
        } catch (e: Exception) {
            SecureLog.e("DataCleanupWorker", "Cleanup failed", e)
            Result.retry()
        }
    }
}
