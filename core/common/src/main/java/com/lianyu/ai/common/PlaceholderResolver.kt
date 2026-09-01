package com.lianyu.ai.common

import android.content.Context
import com.lianyu.ai.domain.PlaceholderContext
import com.lianyu.ai.domain.PlaceholderProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 默认占位符解析器实现。
 *
 * 支持的内置占位符：
 * - {{char}} / {char}：角色/伴侣名称
 * - {{user}} / {user}：用户昵称
 * - {{cur_date}} / {cur_date}：当前日期 (yyyy-MM-dd)
 * - {{cur_time}} / {cur_time}：当前时间 (HH:mm)
 * - {{model_id}} / {model_id}：模型 ID
 * - {{model_name}} / {model_name}：模型名称
 * - {{locale}} / {locale}：语言环境
 * - {{timezone}} / {timezone}：时区
 * - {{battery_level}} / {battery_level}：电池电量
 * - {{nickname}} / {nickname}：用户昵称（同 {{user}}）
 *
 * 大小写不敏感，支持 {{key}} 和 {key} 两种格式。
 */
class PlaceholderResolver(
    private val context: Context
) : PlaceholderProvider {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun resolve(key: String): String? {
        val lowerKey = key.lowercase()
        return when (lowerKey) {
            "model_id", "model_name" -> null // 需要上下文提供
            "locale" -> Locale.getDefault().toString()
            "timezone" -> TimeZone.getDefault().id
            "battery_level" -> getBatteryLevel().toString()
            else -> null
        }
    }

    override fun resolve(text: String, context: PlaceholderContext?): String {
        if (text.isBlank()) return text

        var result = text

        // 从上下文获取值
        val charName = context?.charName ?: "角色"
        val userName = context?.userName ?: "用户"
        val curDate = context?.currentTimeMillis?.let { dateFormat.format(Date(it)) } ?: dateFormat.format(Date())
        val curTime = context?.currentTimeMillis?.let { timeFormat.format(Date(it)) } ?: timeFormat.format(Date())
        val modelId = context?.modelId ?: ""
        val modelName = context?.modelName ?: ""
        val locale = context?.locale ?: Locale.getDefault().toString()
        val timezone = context?.timezone ?: TimeZone.getDefault().id
        val batteryLevel = context?.batteryLevel?.toString() ?: getBatteryLevel().toString()

        // 替换常用占位符（支持 {{key}} 和 {key}，不区分大小写）
        result = result
            .replace("{{char}}", charName).replace("{char}", charName)
            .replace("{{CHAR}}", charName).replace("{CHAR}", charName)
            .replace("{{user}}", userName).replace("{user}", userName)
            .replace("{{USER}}", userName).replace("{USER}", userName)
            .replace("{{cur_date}}", curDate).replace("{cur_date}", curDate)
            .replace("{{cur_time}}", curTime).replace("{cur_time}", curTime)
            .replace("{{model_id}}", modelId).replace("{model_id}", modelId)
            .replace("{{model_name}}", modelName).replace("{model_name}", modelName)
            .replace("{{locale}}", locale).replace("{locale}", locale)
            .replace("{{timezone}}", timezone).replace("{timezone}", timezone)
            .replace("{{battery_level}}", batteryLevel).replace("{battery_level}", batteryLevel)
            .replace("{{nickname}}", userName).replace("{nickname}", userName)

        // 其余占位符：使用正则匹配 {{key}} 和 {key}
        val placeholderRegex = "\\{\\{([^}]+)\\}\\}|\\{([^}]+)\\}".toRegex()
        return placeholderRegex.replace(result) { matchResult ->
            val key = matchResult.groupValues[1]?.ifBlank { matchResult.groupValues[2] }?.lowercase() ?: ""
            // 先尝试从 context.extras 获取
            context?.extras?.get(key)
                // 再尝试内置解析
                ?: resolve(key)
                // 最后保留原样
                ?: matchResult.value
        }
    }

    private fun getBatteryLevel(): Int {
        try {
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                return (level * 100 / scale).coerceIn(0, 100)
            }
        } catch (e: Exception) {
            // 忽略
        }
        return 100
    }
}