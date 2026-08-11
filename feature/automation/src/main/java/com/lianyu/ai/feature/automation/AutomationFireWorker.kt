package com.lianyu.ai.feature.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lianyu.ai.feature.automation.data.AutomationSchedulePolicy
import com.lianyu.ai.feature.automation.data.AutomationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 定时触发 Worker：到点后经 [AutomationScheduler.fireDue] 执行并重建下一次调度。
 *
 * 注意：手动触发（列表页/AI）不走这里，直接同步调用 [AutomationExecutor]，
 * 避免 WorkManager 后台调度延迟导致"点了没反应"；
 * Doze 下 WorkManager 延迟由保活服务的 [com.lianyu.ai.domain.AutomationTickProvider] 兜底。
 */
class AutomationFireWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_AUTOMATION_ID) ?: return@withContext Result.success()
        val store = AutomationStore(context)
        val automation = store.list().firstOrNull { it.id == id }
            ?: return@withContext Result.success()
        if (!automation.enabled) return@withContext Result.success()

        // 幂等保护：若保活服务兜底已执行过本次触发（lastFiredAt 已推进），跳过
        if (!AutomationSchedulePolicy.shouldFire(automation, System.currentTimeMillis())) {
            return@withContext Result.success()
        }

        AutomationScheduler.fireDue(context, automation)
        Result.success()
    }

    companion object {
        const val KEY_AUTOMATION_ID = "automation_id"
    }
}
