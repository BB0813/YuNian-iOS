package com.lianyu.ai.feature.automation

import android.content.Context
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.domain.AutomationTickProvider
import com.lianyu.ai.feature.automation.data.AutomationSchedulePolicy
import com.lianyu.ai.feature.automation.data.AutomationStore
import com.lianyu.ai.feature.automation.data.AutomationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动化到点检查实现。
 *
 * 由保活服务周期性调用 [onTick]：找出「已到点且本次尚未执行」的自动化，
 * 经 [AutomationScheduler.fireDue] 执行并重排。
 *
 * 并发安全：
 * - [AtomicBoolean] 互斥：onTick 内 AI 生成/执行可能超过 60s 节拍，防 handler 堆叠并发；
 * - 幂等：以 stats.lastFiredAt < triggerAtMillis 判断本次触发是否已执行
 *   （与 WorkManager worker 的 shouldFire 共用），配合 [AutomationExecutor] 的 per-id 锁，
 *   双路径不会重复发消息。
 */
class AutomationTickProviderImpl(private val context: Context) : AutomationTickProvider {

    private val ticking = AtomicBoolean(false)

    override suspend fun onTick() = withContext(Dispatchers.IO) {
        if (!ticking.compareAndSet(false, true)) {
            SecureLog.w("AutomationTick", "previous tick still running, skip this round")
            return@withContext
        }
        try {
            runCatching {
                val store = AutomationStore(context)
                val now = System.currentTimeMillis()
                val all = store.list()
                // 规范化旧数据：非 ONCE 且 triggerAtMillis 未初始化（<=0，旧版本创建）→ 修正为下次触发时刻
                all.filter { it.enabled && it.type != AutomationType.ONCE && it.triggerAtMillis <= 0 }.forEach { a ->
                    val next = AutomationSchedulePolicy.nextTriggerAtMillis(a, now)
                    if (next != null) {
                        store.upsert(a.copy(triggerAtMillis = next))
                        AutomationScheduler.reschedule(context, a.copy(triggerAtMillis = next))
                    }
                }
                val due = all.filter { automation ->
                    AutomationSchedulePolicy.shouldFire(automation, now)
                }
                if (due.isEmpty()) return@withContext
                SecureLog.i("AutomationTick", "due automations: ${due.map { it.title }}")
                due.forEach { automation ->
                    runCatching { AutomationScheduler.fireDue(context, automation) }
                        .onFailure { SecureLog.e("AutomationTick", "fire '${automation.title}' failed", it) }
                }
            }.onFailure { SecureLog.e("AutomationTick", "onTick failed", it) }
        } finally {
            ticking.set(false)
        }
    }
}
