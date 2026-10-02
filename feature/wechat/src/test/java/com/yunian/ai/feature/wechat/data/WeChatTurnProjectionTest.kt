package com.yunian.ai.feature.wechat.data

import com.yunian.ai.domain.dialogue.DialogueOutputEvent
import com.yunian.ai.domain.dialogue.DialogueTurnSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 微信通道消费判定（P3-3c）：turn 为 null / 非 null 时文本与表情标签各从哪里来，
 * 以及**防双发**。
 *
 * 纯 JVM 测试：投影（[WeChatTurnProjection]）是唯一决策点，与 Android / StickerManager 无关。
 */
class WeChatTurnProjectionTest {

    private fun turn(vararg events: DialogueOutputEvent): DialogueTurnSnapshot =
        DialogueTurnSnapshot(events = events.toList())

    // ── 2) turn == null：回退 replyText，表情标签仍走旧文本正则路径 ──

    @Test
    fun nullTurn_fallsBackToReplyTextVerbatim() {
        val replyText = "你好呀。[猫猫] 今天过得怎么样？"
        val projection = WeChatTurnProjection.of(replyText, turn = null)

        assertEquals(replyText, projection.sourceText)
        assertFalse(projection.fromTurn)
    }

    @Test
    fun nullTurn_keepsTheLegacyRegexStickerPathEnabled() {
        // eventStickerLabels == null 是「没有快照」的信号：桥接层据此继续走改动前的
        // extractStickerTags 正则发现 + 随机兜底，行为逐字不变。
        val projection = WeChatTurnProjection.of("[猫猫] 你好", turn = null)

        assertNull(projection.eventStickerLabels)
    }

    // ── 1) turn != null：按 events 发送 ──

    @Test
    fun nonNullTurn_usesEventsAsTheOnlyTextSource() {
        val projection = WeChatTurnProjection.of(
            replyText = "ignored",
            turn = turn(
                DialogueOutputEvent.Bubble("第一句。"),
                DialogueOutputEvent.Bubble("第二句。"),
            ),
        )

        assertEquals("第一句。\n第二句。", projection.sourceText)
        assertTrue(projection.fromTurn)
    }

    // ── 3) 防双发：turn != null 时 replyText 绝不参与发送 ──

    @Test
    fun nonNullTurn_neverSendsReplyText_antiDoubleSend() {
        val replyText = "REPLY-TEXT-MUST-NOT-BE-SENT"
        val projection = WeChatTurnProjection.of(
            replyText = replyText,
            turn = turn(DialogueOutputEvent.Bubble("EVENT-ONLY-TEXT")),
        )

        assertEquals("EVENT-ONLY-TEXT", projection.sourceText)
        assertFalse(projection.sourceText.contains(replyText))
        assertFalse(projection.sourceText.contains("REPLY-TEXT"))
    }

    // ── 4) 表情标签来自 events 的 Sticker，不再从 replyText 正则抠 ──

    @Test
    fun stickerLabels_comeFromEventsNotFromReplyTextRegex() {
        val projection = WeChatTurnProjection.of(
            // replyText 里有一个**同样能被旧正则抠出来**的标签：它必须被彻底忽略。
            replyText = "[小狗] 你好呀",
            turn = turn(
                DialogueOutputEvent.Bubble("你好呀"),
                DialogueOutputEvent.Sticker("猫猫"),
            ),
        )

        assertEquals(listOf("猫猫"), projection.eventStickerLabels)
        assertEquals("你好呀\n[猫猫]", projection.sourceText)
        assertFalse("replyText 里的标签不得进入标签集", projection.sourceText.contains("[小狗]"))
    }

    @Test
    fun nonNullTurn_withoutStickerEvents_pinsAnEmptyList_soTheRegexPathIsOff() {
        val projection = WeChatTurnProjection.of(
            replyText = "[小狗] 你好呀",
            turn = turn(DialogueOutputEvent.Bubble("你好呀")),
        )

        // 空列表（不是 null）：本轮确实没有表情事件，旧的正则发现与随机兜底都必须关闭，
        // 否则会发出模型从未要求过的表情。
        assertEquals(emptyList<String>(), projection.eventStickerLabels)
    }

    @Test
    fun nonNullTurn_dedupesRepeatedStickerLabels() {
        val projection = WeChatTurnProjection.of(
            replyText = "ignored",
            turn = turn(
                DialogueOutputEvent.Sticker("猫猫"),
                DialogueOutputEvent.Sticker("猫猫"),
                DialogueOutputEvent.Sticker("开心"),
            ),
        )

        assertEquals(listOf("猫猫", "开心"), projection.eventStickerLabels)
    }

    @Test
    fun nonNullTurn_keepsStickerBracketFormInsideTheTextForCleaning() {
        // 事件文本里仍带 "[label]"（契约定义），桥接层既有的文本清洗会把它从待发文本里剥离；
        // 这里只钉住「投影不改变契约给出的文本形状」。
        val projection = WeChatTurnProjection.of(
            replyText = "ignored",
            turn = turn(DialogueOutputEvent.Sticker("猫猫")),
        )

        assertEquals("[猫猫]", projection.sourceText)
        assertEquals(listOf("猫猫"), projection.eventStickerLabels)
    }

    @Test
    fun nonNullTurn_withEmptyEvents_producesBlankText_ratherThanFallingBackToReplyText() {
        val projection = WeChatTurnProjection.of(
            replyText = "REPLY-TEXT-MUST-NOT-BE-SENT",
            turn = DialogueTurnSnapshot(events = emptyList()),
        )

        assertEquals("", projection.sourceText)
        assertEquals(emptyList<String>(), projection.eventStickerLabels)
    }
}
