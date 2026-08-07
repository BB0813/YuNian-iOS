package com.lianyu.ai.feature.automation

import com.lianyu.ai.feature.automation.data.Automation
import com.lianyu.ai.feature.automation.data.AutomationType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 自动化工具的纯逻辑（无 Android 依赖，便于 JVM 单测）。
 */
object AutomationToolLogic {

    private val json = Json { ignoreUnknownKeys = true }

    data class CreateParams(
        val title: String,
        val companionId: Long,
        val type: AutomationType,
        val triggerAtMillis: Long,
        val hourOfDay: Int,
        val minuteOfHour: Int,
        val dayOfWeekCalendar: Int?,  // Calendar.DAY_OF_WEEK 语义：1=周日..7=周六
        val message: String
    )

    /**
     * 解析 AI 的 automation_create 参数。
     * AI 约定：type = "once"|"daily"|"weekly"；dayOfWeek 1=周一..7=周日；
     * once 用 triggerAt（epoch ms）；daily/weekly 用 hour/minute（+dayOfWeek）。
     * @return 解析失败返回 null
     */
    fun parseCreateParams(argsJson: String): CreateParams? {
        return runCatching {
            val obj = json.parseToJsonElement(argsJson).jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val companionId = obj["companionId"]?.jsonPrimitive?.longOrNull ?: 0L
            if (companionId <= 0L) return null
            val type = when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "once" -> AutomationType.ONCE
                "daily" -> AutomationType.DAILY
                "weekly" -> AutomationType.WEEKLY
                else -> return null
            }
            val triggerAt = obj["triggerAt"]?.jsonPrimitive?.longOrNull ?: 0L
            val hour = obj["hour"]?.jsonPrimitive?.intOrNull ?: 0
            val minute = obj["minute"]?.jsonPrimitive?.intOrNull ?: 0
            // AI 传 1=周一..7=周日 → Calendar 语义（1=周日）: +1，周日(7) → 1
            val dayOfWeekAi = obj["dayOfWeek"]?.jsonPrimitive?.intOrNull
            val dayOfWeekCalendar = dayOfWeekAi?.let { if (it in 1..7) (it % 7) + 1 else null }
            val message = obj["message"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: defaultMessage(title)
            CreateParams(
                title = title,
                companionId = companionId,
                type = type,
                triggerAtMillis = triggerAt,
                hourOfDay = hour.coerceIn(0, 23),
                minuteOfHour = minute.coerceIn(0, 59),
                dayOfWeekCalendar = dayOfWeekCalendar,
                message = message
            )
        }.getOrNull()
    }

    /** 按 title 模糊匹配（包含即命中） */
    fun matchByTitle(automations: List<Automation>, keyword: String): List<Automation> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        return automations.filter { it.title.contains(kw, ignoreCase = true) }
    }

    fun defaultMessage(title: String): String = "到点啦～该${title}啦"
}
