package com.lianyu.ai.feature.automation

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.feature.automation.data.Automation
import com.lianyu.ai.feature.automation.data.AutomationSchedulePolicy
import com.lianyu.ai.feature.automation.data.AutomationStore
import com.lianyu.ai.feature.automation.data.AutomationType
import java.util.concurrent.TimeUnit

object AutomationScheduler {

    private fun workName(id: String) = "automation_$id"

    fun reschedule(context: Context, automation: Automation) {
        val next = AutomationSchedulePolicy.nextTriggerAtMillis(automation, System.currentTimeMillis())
            ?: return
        val delayMs = (next - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<AutomationFireWorker>()
            .setInputData(androidx.work.Data.Builder().putString(AutomationFireWorker.KEY_AUTOMATION_ID, automation.id).build())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(automation.id),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    /**
     * 到点触发：执行一次并（循环类）重建下一次调度。
     * 供 WorkManager worker 与保活服务 tick 兜底共用，保证行为一致。
     */
    suspend fun fireDue(context: Context, automation: Automation) {
        // idempotentCheck=true：锁内复查，防 tick / WorkManager 双路径竞态重复发消息
        AutomationExecutor(context).execute(automation, idempotentCheck = true)
        if (automation.type != AutomationType.ONCE) {
            runCatching {
                val store = AutomationStore(context)
                val latest = store.list().firstOrNull { it.id == automation.id } ?: return
                val next = AutomationSchedulePolicy.nextTriggerAtMillis(latest, System.currentTimeMillis())
                    ?: return
                val updated = latest.copy(triggerAtMillis = next)
                store.upsert(updated)
                reschedule(context, updated)
            }.onFailure { SecureLog.e("AutomationScheduler", "reschedule after fire failed", it) }
        }
    }

    fun rescheduleAll(context: Context, automations: List<Automation>) {
        automations.filter { it.enabled }.forEach { reschedule(context, it) }
    }
}
