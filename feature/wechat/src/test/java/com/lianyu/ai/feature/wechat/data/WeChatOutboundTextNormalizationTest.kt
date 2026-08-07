package com.lianyu.ai.feature.wechat.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WeChatOutboundTextNormalizationTest {
    @Test
    fun normalize_preservesParagraphBoundaries() {
        val normalized = normalizeOutboundText("  第一段  \n\n  第二段  ")

        // 气泡架构（用户定稿）：文本整条保留段落边界，不再按段落拆分为多条
        assertEquals("第一段\n\n第二段", normalized)
    }

    @Test
    fun normalize_collapsesHorizontalWhitespace() {
        assertEquals("你好 世界", normalizeOutboundText("  你好\t  世界  "))
    }
}