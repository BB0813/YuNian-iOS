package com.lianyu.ai.feature.wechat.data

import com.lianyu.ai.common.text.MessageSegmenter
import org.junit.Assert.assertEquals
import org.junit.Test

class WeChatOutboundTextNormalizationTest {
    @Test
    fun normalize_preservesParagraphBoundariesUsedByAppSegmenter() {
        val normalized = normalizeOutboundText("  第一段  \n\n  第二段  ")

        assertEquals("第一段\n\n第二段", normalized)
        assertEquals(
            listOf("第一段", "第二段"),
            MessageSegmenter.split(normalized, MessageSegmenter.SplitMode.SIMPLE),
        )
    }

    @Test
    fun normalize_collapsesHorizontalWhitespace() {
        assertEquals("你好 世界", normalizeOutboundText("  你好\t  世界  "))
    }
}