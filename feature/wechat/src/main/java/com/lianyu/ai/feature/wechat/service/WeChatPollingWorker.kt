package com.lianyu.ai.feature.wechat.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.TimeoutBudgets
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class WeChatPollingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = WeChatServiceLocator.messageRepository(applicationContext)

        if (!repo.isLoggedIn()) {
            return Result.success()
        }

        // 看门狗：僵死 lease / 超时无 poll → 释放并尝试拉起 FGS
        runCatching { WeChatChannelKeeper.healIfNeeded(applicationContext) }
            .onFailure { SecureLog.w(TAG, "watchdog heal failed: ${it.message}") }

        // FGS 被杀后，Worker 兜底时尝试重新拉起主轮询（前台/豁免场景下可能成功）
        if (!WeChatChannelRuntime.isPrimaryPollerActive()) {
            runCatching { WeChatPollingService.start(applicationContext) }
                .onFailure { SecureLog.w(TAG, "restart FGS from worker failed: ${it.message}") }
        }

        // S4/S6：主轮询存活且非 stale 时跳过 getUpdates，仅 drain 出站
        if (WeChatChannelRuntime.shouldSkipFallbackPoll()) {
            runCatching {
                val drained = repo.drainOutbox()
                if (drained > 0) {
                    SecureLog.d(TAG, "Primary poller active; drained outbox=$drained")
                }
            }
            return Result.success()
        }

        return try {
            val result = repo.pollMessages(timeoutMs = TimeoutBudgets.WECHAT_POLL_TIMEOUT_MS)
            if (result.isSuccess) {
                WeChatChannelRuntime.onPollSuccess()
                // 兜底轮询成功后继续 drain，覆盖 AI 刚入队的出站
                runCatching { repo.drainOutbox() }
                Result.success()
            } else {
                WeChatChannelRuntime.onPollFailure(result.exceptionOrNull()?.message)
                runCatching { repo.drainOutbox() }
                delay(WeChatChannelRuntime.nextBackoffMs())
                Result.retry()
            }
        } catch (e: Exception) {
            WeChatChannelRuntime.onPollFailure(e.message)
            runCatching { repo.drainOutbox() }
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "WeChatPollingWorker"
        private const val WORK_NAME = "wechat_polling"
        private const val IMMEDIATE_WORK_NAME = "wechat_polling_immediate"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<WeChatPollingWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag("wechat_keepalive")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /**
         * FGS 超时/进程恢复后立刻补一轮 poll+drain，不替换周期任务。
         */
        fun scheduleImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<WeChatPollingWorker>()
                .setConstraints(constraints)
                .addTag("wechat_keepalive")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            SecureLog.i(TAG, "immediate poll worker enqueued")
        }

        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK_NAME)
            wm.cancelUniqueWork(IMMEDIATE_WORK_NAME)
        }
    }
}

