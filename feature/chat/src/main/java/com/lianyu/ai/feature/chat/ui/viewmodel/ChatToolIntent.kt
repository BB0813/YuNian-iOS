package com.lianyu.ai.feature.chat.ui.viewmodel

/**
 * AI 工具启用判断 — 关键词快速通道（0 网络延迟）。
 *
 * 历史：两级判断（关键词 + AI 预判兜底）。AI 预判会在每次非关键词消息的
 * 正式回复开始前阻塞一整轮非流式 AI 调用（20s 读超时 + 多 Key 重试），
 * 用户侧表现为「发送后转圈 ~10s 才出回复」；且该预判复用 @提及判断器的
 * 系统提示词（JSON 输出），对工具意图判断结果不可靠。故移除网络预判，
 * 仅保留关键词命中——0 延迟，覆盖当前全部工具触发词。
 *
 * 未命中关键词的普通闲聊直接不开工具，保住流式打字体验。
 */
internal object ChatToolIntent {

    private val keywords = listOf(
        "记忆", "记得", "回忆", "想起", "以前", "之前", "偏好", "喜欢什么",
        "咖啡", "瑞幸", "luckin", "拿铁", "美式", "生椰", "门店", "下单", "订单", "取餐", "取消订单", "支付", "价格",
        "提醒", "定时", "几点", "每天", "每周", "明天", "明早", "闹钟", "待办", "别忘了", "自动化",
        "工作流", "巡检", "吃醋", "抽查", "流程", "节点", "执行"
    )

    /** 关键词快速通道：命中即启用工具，0 延迟 */
    fun hasKeywordHit(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = text.lowercase()
        return keywords.any { keyword -> normalized.contains(keyword) }
    }

    /**
     * 关键词命中判断（纯本地，无网络）。
     *
     * @param content 当前触发文本（可能是 memory 标签等）
     * @param latestUserText 最近一条用户消息原文
     */
    fun shouldEnableTools(content: String, latestUserText: String?): Boolean {
        val text = content.ifBlank { latestUserText.orEmpty() }
        if (text.isBlank()) return false
        return hasKeywordHit(text)
    }
}
