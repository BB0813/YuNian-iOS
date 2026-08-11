package com.lianyu.ai.feature.automation

import android.content.Context
import com.lianyu.ai.common.ContentFilter
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.repository.ChatRepository
import com.lianyu.ai.database.repository.MessageWriteCoordinator
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.feature.automation.data.Automation
import com.lianyu.ai.feature.automation.data.AutomationSchedulePolicy
import com.lianyu.ai.feature.automation.data.AutomationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 自动化统一执行器。
 * 手动触发（列表页/AI）、定时触发（WorkManager / 保活 tick）都走这里，保证行为一致：
 * 1. 工作流走 [WorkflowEngine]（带巡检上下文）；普通任务发通知 + 伴侣消息
 * 2. 每次执行后更新 [AutomationStats]（次数 / 成败 / 上次结果 / 上次消息摘要）
 *
 * 并发安全：同一自动化经 tick(60s)/WorkManager/手动 三条路径可能并发触发，
 * 用 per-id [Mutex] 串行化；[idempotentCheck]（定时路径）在锁内基于最新数据复查
 * `shouldFire`，避免竞态窗口内重复发消息。手动路径不复查，用户主动执行总是生效。
 *
 * 安全：消息发送前过 [ContentFilter.checkOutputSafety]，违规则跳过且不累计封禁。
 */
class AutomationExecutor(private val context: Context) {

    companion object {
        // 全局 per-id 锁：AutomationExecutor 每次 new，锁必须跨实例共享
        private val locks = ConcurrentHashMap<String, Mutex>()
    }

    /**
     * @param idempotentCheck true=定时路径（锁内复查本次计划是否已处理，防重复）；
     *                        false=手动路径（总是执行）
     */
    suspend fun execute(automation: Automation, idempotentCheck: Boolean = false): WorkflowEngine.Result {
        val mutex = locks.computeIfAbsent(automation.id) { Mutex() }
        return mutex.withLock {
            // 锁内重读最新状态，基于 latest 执行/写回：
            // - 已删除（latest==null）→ 跳过，防在途触发复活已删除任务
            // - 用户并发修改（开关/编辑）以 latest 为准，防过期快照整对象写回回滚
            val latest = runCatching {
                AutomationStore(context).list().firstOrNull { it.id == automation.id }
            }.getOrNull()
            if (latest == null) {
                SecureLog.w("AutomationExecutor", "automation '${automation.id}' not found or deleted, skip")
                return@withLock WorkflowEngine.Result.Success("已删除或不存在")
            }
            if (idempotentCheck && !AutomationSchedulePolicy.shouldFire(latest, System.currentTimeMillis())) {
                SecureLog.i("AutomationExecutor", "skip fired automation '${latest.title}' (idempotent)")
                return@withLock WorkflowEngine.Result.Success("本次触发已执行过")
            }
            doExecute(latest)
        }
    }

    private suspend fun doExecute(automation: Automation): WorkflowEngine.Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val aiService = ServiceRegistry.get(AiServiceProvider::class.java)
        val messageWriter = ServiceRegistry.get(MessageWriteCoordinator::class.java)
        val chatRepository = ServiceRegistry.get(ChatRepository::class.java)

        val result = if (automation.isWorkflow && aiService != null && messageWriter != null) {
            val engine = WorkflowEngine(context, aiService, messageWriter, chatRepository)
            engine.execute(automation)
        } else {
            runLegacy(automation, messageWriter)
        }

        val success = result is WorkflowEngine.Result.Success
        val baseStats = automation.stats
        val updated = automation.copy(
            stats = baseStats.copy(
                fireCount = baseStats.fireCount + 1,
                successCount = baseStats.successCount + if (success) 1 else 0,
                failCount = baseStats.failCount + if (success) 0 else 1,
                lastFiredAt = now,
                // 结算「本次计划」：now 已达计划时刻 → lastScheduledFiredAt 推进到 triggerAtMillis，
                // 手动执行在计划前不结算（min(now, triggerAt) < triggerAt，下次计划照常触发），
                // 手动执行在计划后结算 → tick/worker 不再补跑本次计划。
                lastScheduledFiredAt = maxOf(
                    baseStats.lastScheduledFiredAt,
                    minOf(now, automation.triggerAtMillis)
                ),
                lastResult = if (success) "success" else "failure",
                lastMessage = (result as? WorkflowEngine.Result.Success)?.message.orEmpty().take(40)
            )
        )
        runCatching { AutomationStore(context).upsert(updated) }
            .onFailure { SecureLog.e("AutomationExecutor", "update stats failed", it) }

        if (!success && result is WorkflowEngine.Result.Failure) {
            SecureLog.e("AutomationExecutor", "automation '${automation.title}' failed: ${result.reason}")
        }
        result
    }

    /** 普通任务：系统通知 + 伴侣消息 */
    private suspend fun runLegacy(
        automation: Automation,
        messageWriter: MessageWriteCoordinator?
    ): WorkflowEngine.Result {
        AutomationNotifier.show(context, automation.title, automation.message, automation.companionId)

        val outputSafety = ContentFilter.checkOutputSafety(automation.message)
        if (outputSafety.isSafe) {
            messageWriter?.let { writer ->
                runCatching {
                    writer.enqueueChat(
                        ChatMessage(
                            companionId = automation.companionId,
                            content = automation.message,
                            isFromUser = false
                        )
                    )
                }.onFailure { SecureLog.e("AutomationExecutor", "write chat message failed", it) }
            } ?: SecureLog.w("AutomationExecutor", "MessageWriteCoordinator not registered")
        } else {
            SecureLog.w("AutomationExecutor", "Automation message blocked by safety: ${outputSafety.reason}")
        }
        return WorkflowEngine.Result.Success(automation.message)
    }
}
