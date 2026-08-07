package com.lianyu.ai.feature.automation

import com.lianyu.ai.feature.automation.data.Automation
import com.lianyu.ai.feature.automation.data.AutomationSchedulePolicy
import com.lianyu.ai.feature.automation.data.AutomationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Calendar

class AutomationSchedulePolicyTest {

    private fun automation(
        type: AutomationType,
        triggerAtMillis: Long = 0L,
        hourOfDay: Int = 0,
        minuteOfHour: Int = 0,
        dayOfWeek: Int? = null
    ) = Automation(
        id = "test", title = "喝水", companionId = 1L,
        type = type, triggerAtMillis = triggerAtMillis,
        hourOfDay = hourOfDay, minuteOfHour = minuteOfHour,
        dayOfWeek = dayOfWeek, message = "到点啦～该喝水啦"
    )

    @Test
    fun onceInFutureReturnsTriggerTime() {
        val now = 1_000_000L
        val a = automation(AutomationType.ONCE, triggerAtMillis = 2_000_000L)
        assertEquals(2_000_000L, AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun onceInPastReturnsNull() {
        val now = 3_000_000L
        val a = automation(AutomationType.ONCE, triggerAtMillis = 2_000_000L)
        assertNull(AutomationSchedulePolicy.nextTriggerAtMillis(a, now))
    }

    @Test
    fun dailyBeforeTimeFiresToday() {
        // now = 2026-08-07 08:00，目标 09:30 → 今天 09:30
        val now = calendar(2026, 7, 7, 8, 0).timeInMillis
        val a = automation(AutomationType.DAILY, hourOfDay = 9, minuteOfHour = 30)
        assertEquals(calendar(2026, 7, 7, 9, 30).timeInMillis, AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun dailyAfterTimeFiresTomorrow() {
        // now = 2026-08-07 10:00，目标 09:30 → 明天 09:30
        val now = calendar(2026, 7, 7, 10, 0).timeInMillis
        val a = automation(AutomationType.DAILY, hourOfDay = 9, minuteOfHour = 30)
        assertEquals(calendar(2026, 7, 8, 9, 30).timeInMillis, AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun weeklyFiresNextMatchingDay() {
        // 目标 = 最近一个周一 08:00（Calendar 语义：周一=2）
        // now = 目标前一天（周日）12:00 → 下次触发 = 该周一 08:00
        val target = mondayAt(8, 0)
        val now = target - 24 * 3600_000L
        val a = automation(AutomationType.WEEKLY, hourOfDay = 8, minuteOfHour = 0, dayOfWeek = 2)
        assertEquals(target, AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun weeklySameDayBeforeTimeFiresToday() {
        // now = 周一 07:00，目标周一 08:00 → 今天（同一周一）08:00
        val now = mondayAt(7, 0)
        val a = automation(AutomationType.WEEKLY, hourOfDay = 8, minuteOfHour = 0, dayOfWeek = 2)
        assertEquals(mondayAt(8, 0), AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun weeklyAfterTimeFiresNextWeek() {
        // now = 周一 09:00，目标周一 08:00 → 下周一 08:00
        val now = mondayAt(9, 0)
        val a = automation(AutomationType.WEEKLY, hourOfDay = 8, minuteOfHour = 0, dayOfWeek = 2)
        val next = AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!
        assertTrue(next > now)
        assertEquals(now + 7 * 24 * 3600_000L - 3600_000L, next)
    }

    /** 最近一个周一（无论过去未来）在指定时刻的 epoch millis */
    private fun mondayAt(hour: Int, minute: Int): Long {
        val monday = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        return monday.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun calendar(year: Int, month0: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(year, month0, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
