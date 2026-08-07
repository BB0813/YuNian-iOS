package com.lianyu.ai.feature.chat.ui.viewmodel

/** AI 工具执行前的用户确认请求（驱动 ChatScreen 弹确认卡片）。 */
data class ToolConfirmationRequest(
    val id: Long,
    val toolName: String,
    val summary: String,
    val argumentsJson: String
)
