package com.lianyu.ai.feature.automation

import com.lianyu.ai.feature.automation.data.Automation
import com.lianyu.ai.feature.automation.data.AutomationSchedulePolicy
import com.lianyu.ai.feature.automation.data.AutomationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

class AutomationSchedulePolicyTest {

    /** 固定参考周基准日：2026-08-10 是周一（已验证）。所有 WEEKLY 测试固定到该周推导，
     *  消除 LocalDate.now() 依赖；期望值用墙钟时间推导而非 7*24h 毫秒算术，避免 DST 过渡周差 1 小时误报。 */
    private val referenceMonday: LocalDate = LocalDate.of(2026, 8, 10)

    private val zone: ZoneId = ZoneId.systemDefault()

    /** 参考周一偏移 offsetDays 天（0=周一）在指定时刻的 epoch millis */
    private fun referenceDay(offsetDays: Int, hour: Int, minute: Int): Long =
        referenceMonday.plusDays(offsetDays.toLong())
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

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
    fun onceTriggerAtNowReturnsNull() {
        // 边界：triggerAtMillis == now → 不触发（触发条件为 > now）
        val now = 2_000_000L
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
        // 目标 = 参考周一（2026-08-10）08:00（Calendar 语义：周一=2）
        // now = 前一天周日（2026-08-09）12:00 → 下次触发 = 该周一 08:00
        val target = referenceDay(0, 8, 0)
        val now = referenceDay(-1, 12, 0)
        val a = automation(AutomationType.WEEKLY, hourOfDay = 8, minuteOfHour = 0, dayOfWeek = 2)
        assertEquals(target, AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun weeklySameDayBeforeTimeFiresToday() {
        // now = 参考周一（2026-08-10）07:00，目标周一 08:00 → 今天（同一周一）08:00
        val now = referenceDay(0, 7, 0)
        val a = automation(AutomationType.WEEKLY, hourOfDay = 8, minuteOfHour = 0, dayOfWeek = 2)
        assertEquals(referenceDay(0, 8, 0), AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun weeklyAfterTimeFiresNextWeek() {
        // now = 参考周一（2026-08-10）09:00，目标周一 08:00 → 下周一（2026-08-17）08:00
        // 期望值基于固定参考日期推导（墙钟时间，与实现 cal.add(DAY_OF_YEAR,1) 语义一致），
        // 不用 7*24h 毫秒算术，DST 过渡周不会差 1 小时误报
        val now = referenceDay(0, 9, 0)
        val a = automation(AutomationType.WEEKLY, hourOfDay = 8, minuteOfHour = 0, dayOfWeek = 2)
        val next = AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!
        assertTrue(next > now)
        assertEquals(referenceDay(7, 8, 0), next)
    }

    @Test
    fun weeklyNullOrInvalidDayOfWeekHandled() {
        // dayOfWeek=null → 生产代码回退 nextDaily 分支，与 DAILY 行为一致
        val now = calendar(2026, 7, 7, 8, 0).timeInMillis // 2026-08-07（周五）08:00
        // 目标 09:30 晚于 now → 当天 09:30
        val a = automation(AutomationType.WEEKLY, hourOfDay = 9, minuteOfHour = 30, dayOfWeek = null)
        assertEquals(calendar(2026, 7, 7, 9, 30).timeInMillis, AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
        // 目标 07:00 早于 now → 次日 07:00
        val b = automation(AutomationType.WEEKLY, hourOfDay = 7, minuteOfHour = 0, dayOfWeek = null)
        assertEquals(calendar(2026, 7, 8, 7, 0).timeInMillis, AutomationSchedulePolicy.nextTriggerAtMillis(b, now)!!)

        // dayOfWeek=9 越界 → coerceIn(1,7)=7，Calendar 语义 7=周六
        // now = 2026-08-07（周五）07:00 → 下次周六 2026-08-08 10:00
        val c = automation(AutomationType.WEEKLY, hourOfDay = 10, minuteOfHour = 0, dayOfWeek = 9)
        assertEquals(
            calendar(2026, 7, 8, 10, 0).timeInMillis,
            AutomationSchedulePolicy.nextTriggerAtMillis(c, calendar(2026, 7, 7, 7, 0).timeInMillis)!!
        )
    }

    private fun calendar(year: Int, month0: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(year, month0, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
