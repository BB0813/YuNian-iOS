package com.yunian.ai.feature.qqbot.data

import org.junit.Assert.assertTrue
import org.junit.Test

class QQBotProactiveErrorMapperTest {
    @Test
    fun `40034105 明确映射为企业资质限制而非客户端故障`() {
        val message = QQBotProactiveErrorMapper.message(
            400,
            "{\"message\":\"主动消息失败, 无权限\",\"code\":40034105}",
        )
        assertTrue(message.contains("需要企业资质"))
        assertTrue(message.contains("不是客户端架构、群路由或连接故障"))
        assertTrue(message.contains("个人资质仍可正常使用群聊被动回复"))
    }

    @Test
    fun `其他错误保留HTTP状态和平台详情`() {
        val message = QQBotProactiveErrorMapper.message(429, "rate limited")
        assertTrue(message.contains("429"))
        assertTrue(message.contains("rate limited"))
    }
}
