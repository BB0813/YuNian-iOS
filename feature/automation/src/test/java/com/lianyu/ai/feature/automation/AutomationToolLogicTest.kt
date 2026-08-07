package com.lianyu.ai.feature.automation

import com.lianyu.ai.feature.automation.data.Automation
import com.lianyu.ai.feature.automation.data.AutomationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutomationToolLogicTest {

    @Test
    fun parseDailyCreateParams() {
        val params = AutomationToolLogic.parseCreateParams(
            """{"title":"喝水","companionId":1,"type":"daily","hour":8,"minute":0,"message":"到点啦"}"""
        )
        assertNotNull(params)
        assertEquals("喝水", params!!.title)
        assertEquals(AutomationType.DAILY, params.type)
        assertEquals(8, params.hourOfDay)
        assertEquals(0, params.minuteOfHour)
    }

    @Test
    fun parseOnceCreateParamsWithTriggerAt() {
        val params = AutomationToolLogic.parseCreateParams(
            """{"title":"开会","companionId":2,"type":"once","triggerAt":1700000000000,"message":"去开会"}"""
        )
        assertNotNull(params)
        assertEquals(AutomationType.ONCE, params!!.type)
        assertEquals(1_700_000_000_000L, params.triggerAtMillis)
    }

    @Test
    fun parseInvalidReturnsNull() {
        assertNull(AutomationToolLogic.parseCreateParams("""{"title":123}"""))
    }

    @Test
    fun weeklyMapsDayOfWeek() {
        // AI 约定：dayOfWeek 1=周一 .. 7=周日（与 Calendar.DAY_OF_WEEK 的 1=周日不一致，逻辑内换算）
        val params = AutomationToolLogic.parseCreateParams(
            """{"title":"健身","companionId":1,"type":"weekly","dayOfWeek":1,"hour":19,"minute":30}"""
        )
        assertNotNull(params)
        assertEquals(AutomationType.WEEKLY, params!!.type)
        // Calendar.DAY_OF_WEEK：周一 = 2
        assertEquals(2, params.dayOfWeekCalendar)
    }

    @Test
    fun matchByTitleUniqueHit() {
        val list = listOf(
            automation("a", "喝水"),
            automation("b", "健身"),
            automation("c", "健身")
        )
        assertEquals(listOf(list[0]), AutomationToolLogic.matchByTitle(list, "喝水"))
        assertEquals(2, AutomationToolLogic.matchByTitle(list, "健身").size)
        assertEquals(0, AutomationToolLogic.matchByTitle(list, "不存在").size)
    }

    private fun automation(id: String, title: String) = Automation(
        id = id, title = title, companionId = 1L, type = AutomationType.ONCE,
        triggerAtMillis = 1L, hourOfDay = 0, minuteOfHour = 0, message = "m"
    )
}
