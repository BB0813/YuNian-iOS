package com.lianyu.ai.feature.automation

import android.app.Application
import com.lianyu.ai.domain.AiTool
import com.lianyu.ai.domain.ToolRegistry
import com.lianyu.ai.feature.automation.data.Automation
import com.lianyu.ai.feature.automation.data.AutomationStore
import com.lianyu.ai.feature.automation.data.AutomationType
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * 自动化 AI 工具集 —— 注册为 [AiTool]，供 AI 对话调用。
 * - automation_create  创建自动化（需确认卡片）
 * - automation_cancel  取消自动化（直接执行）
 * - automation_list    列出当前自动化
 */
object AutomationTools {

    fun registerAll(store: AutomationStore, app: Application) {
        ToolRegistry.register(CreateAutomationTool(store, app))
        ToolRegistry.register(CancelAutomationTool(store, app))
        ToolRegistry.register(ListAutomationsTool(store))
    }

    private class CreateAutomationTool(
        private val store: AutomationStore,
        private val app: Application
    ) : AiTool {
        override val name = "automation_create"
        override val description = "创建定时自动化任务（如 每天早8点提醒喝水）。" +
            "参数：title 任务名，type=once|daily|weekly，" +
            "once 用 triggerAt(epoch毫秒)，daily/weekly 用 hour+minute（weekly 加 dayOfWeek，1=周一..7=周日），" +
            "message 为到点时伴侣发的自然文案（可选，缺省自动生成）。此操作需用户确认。"
        override val parametersJsonSchema = """
            {"type":"object","properties":{
                "title":{"type":"string","description":"任务名，如 喝水"},
                "companionId":{"type":"integer","description":"从哪个伴侣对话创建"},
                "type":{"type":"string","enum":["once","daily","weekly"]},
                "triggerAt":{"type":"integer","description":"ONCE 触发时间 epoch 毫秒"},
                "hour":{"type":"integer","description":"DAILY/WEEKLY 触发小时 0-23"},
                "minute":{"type":"integer","description":"DAILY/WEEKLY 触发分钟 0-59"},
                "dayOfWeek":{"type":"integer","description":"WEEKLY 周几，1=周一..7=周日"},
                "message":{"type":"string","description":"到点时伴侣发的自然文案"}
            },"required":["title","companionId","type"]}
        """.trimIndent()
        override val requiresConfirmation: Boolean get() = true

        override fun summarizeArguments(argumentsJson: String): String {
            val p = AutomationToolLogic.parseCreateParams(argumentsJson) ?: return argumentsJson.take(120)
            val whenText = when (p.type) {
                AutomationType.ONCE -> "一次（${formatOnce(p.triggerAtMillis)}）"
                AutomationType.DAILY -> "每天 ${p.hourOfDay.toString().padStart(2, '0')}:${p.minuteOfHour.toString().padStart(2, '0')}"
                AutomationType.WEEKLY -> "每周${weekdayName(p.dayOfWeekCalendar)} ${p.hourOfDay.toString().padStart(2, '0')}:${p.minuteOfHour.toString().padStart(2, '0')}"
            }
            return "自动化「${p.title}」· $whenText"
        }

        override suspend fun execute(argumentsJson: String): String {
            val p = AutomationToolLogic.parseCreateParams(argumentsJson)
                ?: return """{"error":"参数解析失败，请重试"}"""
            val automation = Automation(
                id = UUID.randomUUID().toString(),
                title = p.title,
                companionId = p.companionId,
                type = p.type,
                triggerAtMillis = p.triggerAtMillis,
                hourOfDay = p.hourOfDay,
                minuteOfHour = p.minuteOfHour,
                dayOfWeek = p.dayOfWeekCalendar,
                message = p.message
            )
            store.upsert(automation)
            AutomationScheduler.reschedule(app, automation)
            return """{"id":"${automation.id}","title":"${p.title}","created":true}"""
        }

        private fun formatOnce(ms: Long): String {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            return "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日 " +
                "${cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')}:${cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')}"
        }

        private fun weekdayName(dayOfWeekCalendar: Int?): String = when (dayOfWeekCalendar) {
            2 -> "一"; 3 -> "二"; 4 -> "三"; 5 -> "四"; 6 -> "五"; 7 -> "六"; 1 -> "日"; else -> "?"
        }
    }

    private class CancelAutomationTool(
        private val store: AutomationStore,
        private val app: Application
    ) : AiTool {
        override val name = "automation_cancel"
        override val description = "取消定时自动化任务。参数 id 为自动化ID；若只给 title 则按名称模糊匹配，唯一命中时取消，多个匹配返回候选列表。"
        override val parametersJsonSchema = """
            {"type":"object","properties":{
                "id":{"type":"string","description":"自动化ID"},
                "title":{"type":"string","description":"任务名（模糊匹配）"}
            }}
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): String {
            val obj = runCatching {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(argumentsJson).jsonObject
            }.getOrNull() ?: return """{"error":"参数解析失败"}"""
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
            val title = obj["title"]?.jsonPrimitive?.contentOrNull
            val all = store.list()
            if (!id.isNullOrBlank()) {
                val target = all.firstOrNull { it.id == id } ?: return """{"error":"自动化不存在"}"""
                store.delete(id)
                AutomationScheduler.cancel(app, id)
                return """{"cancelled":true,"title":"${target.title}"}"""
            }
            val matches = AutomationToolLogic.matchByTitle(all, title.orEmpty())
            return when {
                matches.isEmpty() -> """{"cancelled":false,"error":"没有找到匹配的自动化"}"""
                matches.size == 1 -> {
                    store.delete(matches[0].id)
                    AutomationScheduler.cancel(app, matches[0].id)
                    """{"cancelled":true,"title":"${matches[0].title}"}"""
                }
                else -> """{"cancelled":false,"candidates":[${matches.joinToString(",") { "\"${it.title}\"" }}]}"""
            }
        }
    }

    private class ListAutomationsTool(
        private val store: AutomationStore
    ) : AiTool {
        override val name = "automation_list"
        override val description = "列出当前全部定时自动化任务，返回 id、title、type、触发时间、启用状态。"
        override val parametersJsonSchema = """{"type":"object","properties":{}}"""

        override suspend fun execute(argumentsJson: String): String {
            val list = store.list()
            val sb = StringBuilder("[")
            list.forEachIndexed { i, a ->
                if (i > 0) sb.append(",")
                sb.append("""{"id":"${a.id}","title":"${a.title}","type":"${a.type.name.lowercase()}","enabled":${a.enabled}}""")
            }
            sb.append("]")
            return sb.toString()
        }
    }
}
