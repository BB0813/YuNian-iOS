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
import com.lianyu.ai.database.cache.MessageCache
import java.util.concurrent.TimeUnit

/**
 * 数据库维护策略 — 三级存储架构的维护层。
 *
 * 策略：
 * - 每个会话在热表保留最新 5000 条，较旧消息原子移动到密文归档表
 * - 使用 SQLite optimize 更新查询规划统计
 * - 使用被动 WAL checkpoint 回收已完成的 WAL 页
 * - 通过 WorkManager 定期执行（每日一次）
 * - App 启动时也触发一次（如果距上次清理超过 24 小时）
 */
object DataCleanupManager {

    private const val TAG = "DataCleanupManager"
    private const val WORK_NAME = "lianyu_data_cleanup"
    private const val CLEANUP_INTERVAL_HOURS = 24L
    private const val HOT_MESSAGES_PER_CONVERSATION = 5_000

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

        SecureLog.i(TAG, "Starting database maintenance (last run was ${now - lastCleanup}ms ago)")
        performMaintenance(context)
        prefs.edit().putLong(KEY_LAST_CLEANUP, now).apply()
    }

    private suspend fun performMaintenance(context: Context) {
        val db = AppDatabase.getDatabase(context)

        try {
            val messageDao = db.messageDao()
            var archivedCount = 0
            listOf("chat", "group").forEach { type ->
                messageDao.getDistinctConversationIds(type).forEach { conversationId ->
                    val count = messageDao.archiveOldMessages(
                        conversationId,
                        type,
                        HOT_MESSAGES_PER_CONVERSATION
                    )
                    archivedCount += count
                    // 归档后失效 L1 缓存，确保下次读取走 L2 重新加载
                    if (count > 0) {
                        when (type) {
                            "chat" -> MessageCache.evictChat(conversationId)
                            "group" -> MessageCache.evictGroup(conversationId)
                        }
                    }
                }
            }
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("PRAGMA optimize")
            sqlite.query("PRAGMA wal_checkpoint(PASSIVE)").close()
            SecureLog.i(TAG, "Database maintenance completed; archived $archivedCount messages")
        } catch (e: Exception) {
            SecureLog.e(TAG, "Database maintenance failed", e)
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
