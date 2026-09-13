package com.yunian.ai.common.safety

data class SafetySample(
    val text: String,
    val isViolation: Boolean,
    val source: SampleSource,
    val label: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

enum class SampleSource { USER_INPUT, MODEL_OUTPUT }
