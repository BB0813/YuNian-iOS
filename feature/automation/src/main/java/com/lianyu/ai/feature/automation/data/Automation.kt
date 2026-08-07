package com.lianyu.ai.feature.automation.data

import kotlinx.serialization.Serializable

enum class AutomationType { ONCE, DAILY, WEEKLY }

@Serializable
data class Automation(
    val id: String,
    val title: String,
    val companionId: Long,
    val type: AutomationType,
    val triggerAtMillis: Long,
    val hourOfDay: Int,
    val minuteOfHour: Int,
    val dayOfWeek: Int? = null,
    val message: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
