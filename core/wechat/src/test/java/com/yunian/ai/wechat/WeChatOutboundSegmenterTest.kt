package com.yunian.ai.wechat

import com.yunian.ai.domain.wechat.WeChatContentKind
import com.yunian.ai.domain.wechat.WeChatMediaRef
import com.yunian.ai.domain.wechat.WeChatOutboundRequest
import com.yunian.ai.wechat.map.WeChatOutboundSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatOutboundSegmenterTest {

    @Test
    fun splitSimple_paragraphsPreferred() {
        val parts = WeChatOutboundSegmenter.splitTextSimple("第一段\n\n第二段")
        assertEquals(listOf("第一段", "第二段"), parts)
    }

    @Test
    fun splitSimple_completeSentencesBecomeSeparateBubbles() {

        val parts = WeChatOutboundSegmenter.splitTextSimple("你好。世界！")
        assertEquals(listOf("你好。", "世界！"), parts)
    }

    @Test
    fun splitSimple_blankLineParagraphsStaySplit() {
        val parts = WeChatOutboundSegmenter.splitTextSimple("嗯。\n\n咋了，加班了？")
        assertEquals(listOf("嗯。", "咋了，加班了？"), parts)
    }

    @Test
    fun splitSimple_shortAffirmationsStayIndependent() {
        val parts = WeChatOutboundSegmenter.splitTextSimple("嗯。好。")
        assertEquals(listOf("嗯。", "好。"), parts)
    }

    @Test
    fun splitSimple_complexShortSentenceStaysIndependent() {
        val parts = WeChatOutboundSegmenter.splitTextSimple("行。那你先忙。")
        assertEquals(listOf("行。", "那你先忙。"), parts)
    }

    @Test
    fun splitSimple_behaviorShiftRespectsSoftSegmentCap() {

        val text = "你今天看起来有点累。要不先休息一下吧。我有点担心你。晚安。"
        val parts = WeChatOutboundSegmenter.splitTextSimple(text)
        assertTrue(parts.size in 2..3)
        assertTrue(parts.none { it.isBlank() })
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }

    @Test
    fun splitSimple_singleChunk() {
        val parts = WeChatOutboundSegmenter.splitTextSimple("一整句没有标点")
        assertEquals(listOf("一整句没有标点"), parts)
    }

    @Test
    fun splitSimple_normalInputsRemainCompatible() {
        val cases = mapOf(
            "" to listOf(""),
            "   \n " to listOf(""),
            "  你好，世界  " to listOf("你好，世界"),
            "你好。世界！还有吗？" to listOf("你好。", "世界！", "还有吗？"),
            "Hello. Next; still here" to listOf("Hello. Next; still here"),
            "甲。乙。丙。丁。" to listOf("甲。乙。", "丙。", "丁。"),
            "第一行\n第二行" to listOf("第一行\n第二行"),
        )
        cases.forEach { (input, expected) ->
            assertEquals(input, expected, WeChatOutboundSegmenter.splitTextSimple(input))
        }
    }

    @Test
    fun splitSimple_adjacentEndersPreserveOriginalTone() {
        val cases = mapOf(
            "你好！！" to listOf("你好！！"),
            "真的？！好！！" to listOf("真的？！", "好！！"),
            "What!? Yes!!" to listOf("What!?", "Yes!!"),
            "等等……然后呢？" to listOf("等等……", "然后呢？"),
            "好。！？!?……继续" to listOf("好。！？!?……", "继续"),
            "你好！！世界？？再见……" to listOf("你好！！", "世界？？", "再见……"),
        )
        cases.forEach { (input, expected) ->
            assertEquals(input, expected, WeChatOutboundSegmenter.splitTextSimple(input))
        }
    }

    @Test
    fun splitSimple_standaloneEllipsesRemainValid() {
        for (input in listOf("…", "……", "………", "...")) {
            assertEquals(listOf(input), WeChatOutboundSegmenter.splitTextSimple(input))
        }
        assertEquals(listOf("……", "好。"), WeChatOutboundSegmenter.splitTextSimple("……好。"))
    }

    @Test
    fun splitSimple_punctuationRunsDoNotCrossLineBoundaries() {
        for (separator in listOf("\n", "\r\n", "\n\n", "\n  \n")) {
            assertEquals(
                listOf("你好！", "！"),
                WeChatOutboundSegmenter.splitTextSimple("你好！" + separator + "！"),
            )
            assertEquals(
                listOf("…", "…"),
                WeChatOutboundSegmenter.splitTextSimple("…" + separator + "…"),
            )
            assertEquals(
                listOf("你好！！", "……", "再见。"),
                WeChatOutboundSegmenter.splitTextSimple("你好！！" + separator + "……" + separator + "再见。"),
            )
        }
    }

    @Test
    fun splitSimple_whitespaceIsNotAdjacentPunctuation() {
        assertEquals(listOf("你好！", "！"), WeChatOutboundSegmenter.splitTextSimple("你好！ ！"))
        assertEquals(listOf("…", "…"), WeChatOutboundSegmenter.splitTextSimple("…\t…"))
    }

    @Test
    fun expand_repeatedEndersProduceWholeIndexedSegments() {
        val request = WeChatOutboundRequest(companionId = 1L, text = "你好！！再见……")
        val segments = WeChatOutboundSegmenter.expand(request, wechatUserId = "wx1", rootId = "r")
        assertEquals(listOf("你好！！", "再见……"), segments.map { it.text })
        assertEquals(listOf("r#0", "r#1"), segments.map { it.outboxId })
        assertEquals(listOf(0, 1), segments.map { it.segmentIndex })
        assertEquals(listOf(2, 2), segments.map { it.segmentCount })
    }

    @Test
    fun expand_standaloneEllipsisIsNotDropped() {
        val request = WeChatOutboundRequest(companionId = 1L, text = "……")
        val segments = WeChatOutboundSegmenter.expand(request, wechatUserId = "wx1", rootId = "r")
        assertEquals(listOf("……"), segments.map { it.text })
        assertEquals(1, segments.single().segmentCount)
    }

    @Test
    fun expand_textProducesIndexedSegments() {

        val request = WeChatOutboundRequest(
            companionId = 1L,
            text = "嗯。好。",
            contextToken = "c",
        )
        val segments = WeChatOutboundSegmenter.expand(
            request = request,
            wechatUserId = "wx1",
            rootId = "root",
        )
        assertEquals(2, segments.size)
        assertEquals(0, segments[0].segmentIndex)
        assertEquals(1, segments[1].segmentIndex)
        assertEquals(2, segments[0].segmentCount)
        assertEquals("root#0", segments[0].outboxId)
        assertEquals("root#1", segments[1].outboxId)
        assertEquals(WeChatContentKind.TEXT, segments[0].kind)
        assertEquals("c", segments[0].contextToken)
        assertEquals("wx1", segments[0].wechatUserId)
    }

    @Test
    fun expand_mediaIsSingleSegment() {
        val request = WeChatOutboundRequest(
            companionId = 1L,
            text = "caption",
            media = WeChatMediaRef(kind = WeChatContentKind.IMAGE, localPath = "/tmp/a.jpg"),
        )
        val segments = WeChatOutboundSegmenter.expand(request, wechatUserId = "wx2", rootId = "m")
        assertEquals(1, segments.size)
        assertEquals(WeChatContentKind.IMAGE, segments.single().kind)
        assertEquals("/tmp/a.jpg", segments.single().media?.localPath)
        assertEquals("caption", segments.single().text)
    }

    @Test
    fun expand_blankText_empty() {
        val request = WeChatOutboundRequest(companionId = 1L, text = "   ")
        val segments = WeChatOutboundSegmenter.expand(request, wechatUserId = "wx3", rootId = "e")
        assertTrue(segments.isEmpty())
    }
}
