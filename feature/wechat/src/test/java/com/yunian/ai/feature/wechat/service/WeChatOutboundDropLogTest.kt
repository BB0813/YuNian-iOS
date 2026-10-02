package com.yunian.ai.feature.wechat.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「清洗后无可发送文本」的证据记录单测。
 *
 * 对应缺陷：WeChatChatBridge 原实现在 finalText.length < 1 时直接跳过发送、
 * 不留任何痕迹（release 包连日志都没有），用户只看到「AI 回了但微信没收到」。
 *
 * 「在旧实现下为何会失败」：旧实现没有任何计数器/环形缓冲/丢弃记录，
 * 本测试文件里的每个断言在旧实现下都无对应 API（编译不过），
 * 即「这条回复被丢弃」这件事在旧实现里完全不可观测。
 */
class WeChatOutboundDropLogTest {

    @Before
    fun setUp() {
        WeChatOutboundDropLog.reset()
    }

    @After
    fun tearDown() {
        WeChatOutboundDropLog.reset()
    }

    @Test
    fun record_countsAndKeepsDetails() {
        WeChatOutboundDropLog.record(
            reason = "no_text_after_cleaning",
            cleanedLength = 0,
            strippedLength = 0,
            stickerCount = 1,
            atMs = 1234L,
        )

        assertEquals(1, WeChatOutboundDropLog.dropCount())
        val drop = WeChatOutboundDropLog.recentDrops().single()
        assertEquals("no_text_after_cleaning", drop.reason)
        assertEquals(0, drop.cleanedLength)
        assertEquals(0, drop.strippedLength)
        assertEquals(1, drop.stickerCount)
        assertEquals(1234L, drop.atMs)
    }

    @Test
    fun recentDrops_isBoundedButCounterKeepsGrowing() {
        repeat(WeChatOutboundDropLog.MAX_RECENT_DROPS + 7) { index ->
            WeChatOutboundDropLog.record(
                reason = "trimmed_to_empty",
                cleanedLength = index,
                strippedLength = 0,
            )
        }

        // 计数不丢：总次数要能反映真实丢弃量。
        assertEquals(WeChatOutboundDropLog.MAX_RECENT_DROPS + 7, WeChatOutboundDropLog.dropCount())
        // 明细有界：只保留最近 MAX_RECENT_DROPS 条，避免长期运行内存增长。
        val recent = WeChatOutboundDropLog.recentDrops()
        assertEquals(WeChatOutboundDropLog.MAX_RECENT_DROPS, recent.size)
        assertEquals(WeChatOutboundDropLog.MAX_RECENT_DROPS + 6, recent.last().cleanedLength)
        assertEquals(7, recent.first().cleanedLength)
    }

    @Test
    fun recordStaleSendingRecovered_countsRecoveredAndDead() {
        WeChatOutboundDropLog.recordStaleSendingRecovered(recovered = 2, dead = 1, atMs = 999L)

        assertEquals(1, WeChatOutboundDropLog.dropCount())
        val entry = WeChatOutboundDropLog.recentDrops().single()
        assertEquals(WeChatOutboundDropLog.REASON_STALE_SENDING_RECOVERED, entry.reason)
        assertEquals(2, entry.cleanedLength)
        assertEquals(1, entry.strippedLength)
        assertEquals(999L, entry.atMs)
    }

    @Test
    fun recordStaleSendingRecovered_ignoresNoopRecovery() {
        // 每个进程启动都会调一次恢复；没有僵尸行时不得产生噪声证据。
        WeChatOutboundDropLog.recordStaleSendingRecovered(recovered = 0, dead = 0)

        assertEquals(0, WeChatOutboundDropLog.dropCount())
        assertTrue(WeChatOutboundDropLog.recentDrops().isEmpty())
    }

    @Test
    fun reset_clearsEvidence() {
        WeChatOutboundDropLog.record(reason = "no_text_after_cleaning", cleanedLength = 0, strippedLength = 0)
        assertTrue(WeChatOutboundDropLog.dropCount() > 0)

        WeChatOutboundDropLog.reset()

        assertEquals(0, WeChatOutboundDropLog.dropCount())
        assertTrue(WeChatOutboundDropLog.recentDrops().isEmpty())
    }
}
