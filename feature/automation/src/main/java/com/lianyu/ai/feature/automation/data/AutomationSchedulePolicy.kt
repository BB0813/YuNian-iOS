package com.lianyu.ai.feature.automation.data

import java.util.Calendar

/**
 * 计算自动化下次触发时刻（纯函数，无 Android 依赖，便于 JVM 单测）。
 * @return 下次触发 epoch millis；null 表示不再触发（ONCE 已过期）。
 */
object AutomationSchedulePolicy {

    /**
     * 判断本次触发是否应执行（到点 + 幂等）。
     * 幂等依据：lastScheduledFiredAt < triggerAtMillis 表示本次计划尚未被处理，
     * 与 WorkManager worker / 保活 tick 双路径共用，避免重复执行。
     * 手动执行只更新 lastFiredAt，不影响本判定（否则一次手动执行会让
     * DAILY/WEEKLY 的 lastFiredAt 越过 triggerAtMillis，调度永久静默失效）。
     *
     * 防御：非 ONCE 类型若 triggerAtMillis 未初始化（<=0，如旧数据），不触发，
     * 由 [normalizedAutomation] 修复后再按新时刻触发。
     */
    fun shouldFire(a: Automation, now: Long): Boolean {
        return a.enabled &&
            a.triggerAtMillis > 0 &&
            now >= a.triggerAtMillis &&
            a.stats.lastScheduledFiredAt < a.triggerAtMillis
    }

    /**
     * 规范化触发时刻：非 ONCE 且 triggerAtMillis 未初始化（<=0）
     * → 初始化为下一次触发时刻。修复 DAILY/WEEKLY 创建时 triggerAtMillis=0
     * 导致判定失效、触发时机错乱的问题。
     */
    fun normalizedAutomation(a: Automation, now: Long): Automation {
        if (a.type == AutomationType.ONCE || a.triggerAtMillis > 0) return a
        val next = nextTriggerAtMillis(a, now) ?: return a
        return a.copy(triggerAtMillis = next)
    }

    fun nextTriggerAtMillis(a: Automation, now: Long): Long? {
        return when (a.type) {
            AutomationType.ONCE -> if (a.triggerAtMillis > now) a.triggerAtMillis else null
            AutomationType.DAILY -> nextDaily(a, now)
            AutomationType.WEEKLY -> nextWeekly(a, now)
        }
    }

    private fun nextDaily(a: Automation, now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, a.hourOfDay)
        cal.set(Calendar.MINUTE, a.minuteOfHour)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun nextWeekly(a: Automation, now: Long): Long {
        val targetDay = a.dayOfWeek?.coerceIn(1, 7) ?: return nextDaily(a, now)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, a.hourOfDay)
        cal.set(Calendar.MINUTE, a.minuteOfHour)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        for (i in 0 until 7) {
            if (cal.timeInMillis > now && cal.get(Calendar.DAY_OF_WEEK) == targetDay) {
                return cal.timeInMillis
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
