package com.lianyu.ai.feature.automation

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lianyu.ai.feature.automation.data.Automation
import com.lianyu.ai.feature.automation.data.AutomationSchedulePolicy
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

    fun rescheduleAll(context: Context, automations: List<Automation>) {
        automations.filter { it.enabled }.forEach { reschedule(context, it) }
    }
}
