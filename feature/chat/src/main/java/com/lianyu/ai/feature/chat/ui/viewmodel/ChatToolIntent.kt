package com.lianyu.ai.feature.chat.ui.viewmodel

internal object ChatToolIntent {
    private val keywords = listOf(
        "记忆", "记得", "回忆", "想起", "以前", "之前", "偏好", "喜欢什么",
        "咖啡", "瑞幸", "luckin", "拿铁", "美式", "生椰", "门店", "下单", "订单", "取餐", "取消订单", "支付", "价格"
    )

    fun shouldEnableTools(content: String, latestUserText: String?): Boolean {
        val text = content.ifBlank { latestUserText.orEmpty() }
        if (text.isBlank()) return false
        val normalized = text.lowercase()
        return keywords.any { keyword -> normalized.contains(keyword) }
    }
}