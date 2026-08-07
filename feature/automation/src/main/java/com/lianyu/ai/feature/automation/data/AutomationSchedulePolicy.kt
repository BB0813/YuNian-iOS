package com.lianyu.ai.feature.automation.data

import java.util.Calendar

/**
 * 计算自动化下次触发时刻（纯函数，无 Android 依赖，便于 JVM 单测）。
 * @return 下次触发 epoch millis；null 表示不再触发（ONCE 已过期）。
 */
object AutomationSchedulePolicy {

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
