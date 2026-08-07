package com.lianyu.ai.feature.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lianyu.ai.common.ContentFilter
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.repository.MessageWriteCoordinator
import com.lianyu.ai.feature.automation.data.AutomationSchedulePolicy
import com.lianyu.ai.feature.automation.data.AutomationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        // 系统通知（title 为用户自己的任务名）
        AutomationNotifier.show(context, automation.title, automation.message, automation.companionId)

        // 伴侣聊天消息：写前过输出安全检查，违规则跳过消息（不累计封禁）
        val outputSafety = ContentFilter.checkOutputSafety(automation.message)
        if (outputSafety.isSafe) {
            runCatching {
                com.lianyu.ai.domain.ServiceRegistry
                    .getOrThrow(MessageWriteCoordinator::class.java)
                    .enqueueChat(
                        ChatMessage(
                            companionId = automation.companionId,
                            content = automation.message,
                            isFromUser = false
                        )
                    )
            }.onFailure { SecureLog.e("AutomationFireWorker", "write chat message failed", it) }
        } else {
            SecureLog.w("AutomationFireWorker", "Automation message blocked by safety: ${outputSafety.reason}")
        }

        // 循环类在触发动作之后才重排（ONCE 不重排）。若在触发前重排，
        // enqueueUniqueWork(REPLACE) 会取消同名运行中的 work，与当前 worker 自身
        // 形成自取消竞态，可能把本次触发标记为 CANCELLED 而丢失消息腿。
        if (automation.type != com.lianyu.ai.feature.automation.data.AutomationType.ONCE) {
            runCatching {
                val next = AutomationSchedulePolicy.nextTriggerAtMillis(automation, System.currentTimeMillis())
                if (next != null) {
                    val updated = automation.copy(triggerAtMillis = next)
                    store.upsert(updated)
                    AutomationScheduler.reschedule(context, updated)
                }
            }.onFailure { SecureLog.e("AutomationFireWorker", "reschedule automation failed", it) }
        }

        Result.success()
    }

    companion object {
        const val KEY_AUTOMATION_ID = "automation_id"
    }
}
