package com.yunian.ai.feature.qqbot.data

import com.yunian.ai.domain.DialogueResult
import com.yunian.ai.domain.dialogue.DialogueOutputEvent
import com.yunian.ai.domain.dialogue.DialogueTurnSnapshot
import com.yunian.ai.feature.qqbot.channel.QQBotChannelAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ 通道消费判定（P3-3c）：turn 为 null / 非 null 时各发什么，以及**防双发**。
 *
 * 本文件是纯 JVM 测试：投影（[QQBotOutboundProjection]）是唯一决策点，
 * 与 Android / Room / ServiceRegistry 无关，所以不需要 Robolectric。
 */
class QQBotOutboundProjectionTest {

    private fun turn(vararg events: DialogueOutputEvent): DialogueTurnSnapshot =
        DialogueTurnSnapshot(events = events.toList())

    // ── 1) turn != null：按 events 发送（Bubble 走文本路径） ──

    @Test
    fun bubbleEvents_areProjectedToTheTextPathInOrder() {
        val result = DialogueResult(
            replyText = "ignored",
            turn = turn(
                DialogueOutputEvent.Bubble("今天天气不错。"),
                DialogueOutputEvent.Bubble("要不要出去走走？"),
            ),
        )

        assertEquals("今天天气不错。\n要不要出去走走？", QQBotOutboundProjection.resolveSendText(result))
    }

    @Test
    fun noticeEvent_isProjectedToTheSameTextPathAsBubbles() {
        val result = DialogueResult(
            replyText = "ignored",
            turn = turn(DialogueOutputEvent.Notice("这一步需要你在 App 内确认后才能执行（本次未执行）。")),
        )

        assertEquals(
            "这一步需要你在 App 内确认后才能执行（本次未执行）。",
            QQBotOutboundProjection.resolveSendText(result),
        )
    }

    // ── 2) turn == null：回退 replyText，行为与改动前逐字一致 ──

    @Test
    fun nullTurn_fallsBackToReplyTextVerbatim() {
        val replyText = "第一句。第二句！[猫猫] 第三句？"
        val result = DialogueResult(replyText = replyText, turn = null)

        assertEquals(replyText, QQBotOutboundProjection.resolveSendText(result))
    }

    @Test
    fun nullTurn_keepsBracketLabelsInsideTheText_unchangedFromBefore() {
        // 改动前：replyText 里的 [标签] 原样进入 splitIntoSentences + sendTextMessage。
        // 回退路径必须保持这一点（QQ 无表情通路，标签就是纯文本）。
        val result = DialogueResult(replyText = "你好[猫猫]", turn = null)

        assertEquals("你好[猫猫]", QQBotOutboundProjection.resolveSendText(result))
    }

    // ── 3) 防双发：turn != null 时 replyText 绝不参与发送 ──

    @Test
    fun nonNullTurn_neverSendsReplyText_antiDoubleSend() {
        val replyText = "REPLY-TEXT-MUST-NOT-BE-SENT"
        val result = DialogueResult(
            replyText = replyText,
            turn = turn(DialogueOutputEvent.Bubble("EVENT-ONLY-TEXT")),
        )

        val sendText = QQBotOutboundProjection.resolveSendText(result)

        assertEquals("EVENT-ONLY-TEXT", sendText)
        assertFalse("replyText 不得在 turn 非空时被二次发送", sendText.contains(replyText))
        assertFalse(sendText.contains("REPLY-TEXT"))
    }

    @Test
    fun nonNullTurn_withEmptyEvents_sendsNothing_ratherThanFallingBackToReplyText() {
        // DialogueTurnMapper 只在 cleanedReplyText 为空 / 不安全时才产出空快照，
        // 因此这里 replyText 非空是不可能的组合；本断言把「不偷偷回退」钉死。
        val result = DialogueResult(
            replyText = "REPLY-TEXT-MUST-NOT-BE-SENT",
            turn = DialogueTurnSnapshot(events = emptyList()),
        )

        assertEquals("", QQBotOutboundProjection.resolveSendText(result))
    }

    // ── 5) Sticker 在无表情能力的通道上退化为 [label] 文本 ──

    @Test
    fun stickerEvent_degradesToBracketLabelText() {
        val result = DialogueResult(
            replyText = "ignored",
            turn = turn(DialogueOutputEvent.Sticker("猫猫")),
        )

        assertEquals("[猫猫]", QQBotOutboundProjection.resolveSendText(result))
    }

    @Test
    fun stickerEvent_textIsByteIdenticalToTheLegacyReplyTextForm() {
        // 改动前：AgentTurnReplyText.resolve 把 sticker 事件写成 "[$label]"，
        // 再被 splitIntoSentences 原样发送。退化结果必须逐字一致。
        val label = "猫猫"
        val legacyForm = "[$label]"

        assertEquals(legacyForm, DialogueOutputEvent.Sticker(label).text)
        assertEquals(
            legacyForm,
            QQBotOutboundProjection.resolveSendText(
                DialogueResult(replyText = "ignored", turn = turn(DialogueOutputEvent.Sticker(label))),
            ),
        )
    }

    @Test
    fun stickerAndBubbleEvents_areMixedInProducedOrder() {
        val result = DialogueResult(
            replyText = "ignored",
            turn = turn(
                DialogueOutputEvent.Bubble("好呀。"),
                DialogueOutputEvent.Sticker("开心"),
                DialogueOutputEvent.Bubble("那我们走。"),
            ),
        )

        assertEquals("好呀。\n[开心]\n那我们走。", QQBotOutboundProjection.resolveSendText(result))
    }

    @Test
    fun qqChannelReallyHasNoStickerCapability() {
        // 退化路径的前提是「本通道无表情通路」——把它钉在真实的诚实能力声明上，
        // 而不是留一句无法被证伪的注释。若 QQ 将来接通表情链路，本断言会先红。
        assertFalse(
            "QQ 通道声明支持表情时，Sticker 退化路径必须重新设计",
            QQBotChannelAdapter.CAPABILITIES.stickers,
        )
    }

    // ── 连接符与既有分段链路的一致性 ──

    @Test
    fun eventsAreJoinedByNewline_whichIsOneOfTheLegacySentenceDelimiters() {
        // splitIntoSentences 的分隔符集合是 。！？!?\n —— "\n" 在其中，
        // 所以「先连接再分段」与改动前「直接分段 replyText」得到同一分段序列。
        val result = DialogueResult(
            replyText = "ignored",
            turn = turn(
                DialogueOutputEvent.Bubble("没有句末标点"),
                DialogueOutputEvent.Bubble("第二段"),
            ),
        )

        assertEquals("没有句末标点\n第二段", QQBotOutboundProjection.resolveSendText(result))
        assertTrue(QQBotOutboundProjection.resolveSendText(result).contains("\n"))
    }
}
