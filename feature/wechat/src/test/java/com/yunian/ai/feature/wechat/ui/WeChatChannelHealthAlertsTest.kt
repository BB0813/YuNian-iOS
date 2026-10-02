package com.yunian.ai.feature.wechat.ui

import com.yunian.ai.domain.wechat.WeChatChannelHealthSnapshot
import com.yunian.ai.domain.wechat.WeChatOutboxFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WeChatChannelHealthAlerts] 的单测：纯 JVM，不碰 Compose、不碰 Android。
 *
 * 覆盖的是「该显示哪条文案 / 什么顺序 / 什么时候不显示」这层判断——也就是本任务里
 * 唯一可自动化验证的部分。渲染本身（颜色、间距）仍只能靠人眼看。
 */
class WeChatChannelHealthAlertsTest {

    private val now = 1_000_000_000L

    private fun snapshot(
        lastError: String? = null,
        lastErrorAtMs: Long = 0L,
        consecutiveFailures: Int = 0,
        recentFailures: List<WeChatOutboxFailure> = emptyList(),
    ) = WeChatChannelHealthSnapshot(
        primaryPollerActive = true,
        consecutiveFailures = consecutiveFailures,
        lastPollAtMs = now,
        lastErrorAtMs = lastErrorAtMs,
        lastError = lastError,
        recentFailures = recentFailures,
    )

    private fun failure(
        id: String,
        lastError: String?,
        ageMs: Long = 0L,
        retryCount: Int = 1,
    ) = WeChatOutboxFailure(
        id = id,
        wechatUserId = "wxid_demo",
        kind = "TEXT",
        retryCount = retryCount,
        lastError = lastError,
        updatedAtMs = now - ageMs,
    )

    // ---- 常态：一行都不多 ------------------------------------------------

    @Test
    fun healthySnapshot_producesNoAlerts() {
        assertTrue(WeChatChannelHealthAlerts.fromSnapshot(snapshot(), now).isEmpty())
    }

    @Test
    fun emptyLastErrorString_producesNoAlerts() {
        // 防御性：Runtime 理论上不会写空串，但真写了也不该渲染一行空白。
        assertTrue(
            WeChatChannelHealthAlerts.fromSnapshot(
                snapshot(lastError = "poll_unknown: ", lastErrorAtMs = now),
                now,
            ).isEmpty(),
        )
    }

    // ---- 会话过期（errcode=-14）------------------------------------------

    @Test
    fun sessionExpired_surfacesRescanHint_first() {
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(
            snapshot(
                lastError = "poll_auth: 微信会话已过期（errcode=-14），请重新扫码登录",
                lastErrorAtMs = now - 120_000L,
                consecutiveFailures = 3,
            ),
            now,
        )

        assertEquals(1, alerts.size)
        val alert = alerts.first()
        assertEquals(WeChatChannelHealthAlerts.SESSION_EXPIRED_HEADLINE, alert.headline)
        assertTrue(alert.headline.contains("请重新扫码登录"))
        assertTrue(alert.actionRequired)
        assertEquals(WeChatChannelHealthAlerts.AlertSeverity.ERROR, alert.severity)
        // 技术原文与「多久之前」都保留
        assertTrue(alert.detail.orEmpty().contains("errcode=-14"))
        assertTrue(alert.detail.orEmpty().contains("2 分钟前"))
    }

    @Test
    fun sessionExpired_winsOverPollFailureRow() {
        // 会话过期本身就是一次轮询失败（consecutiveFailures>0）。若再渲染一行
        // 「轮询连续失败」，用户会以为是两件独立故障。
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(
            snapshot(
                lastError = "poll_auth: errcode=-14",
                lastErrorAtMs = now - 1_000L,
                consecutiveFailures = 9,
            ),
            now,
        )

        assertEquals(1, alerts.size)
        assertEquals(WeChatChannelHealthAlerts.SESSION_EXPIRED_HEADLINE, alerts.first().headline)
    }

    // ---- context_token 缺失 / 过期 ---------------------------------------

    @Test
    fun tokenMissing_fromLastError_surfacesHowToRecover() {
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(
            snapshot(
                lastError = "outbox_dead: context_token 缺失：iLink 协议只能回复对方先发来的消息，请让对方先发一条消息",
                lastErrorAtMs = now,
                consecutiveFailures = 0,
            ),
            now,
        )

        assertEquals(1, alerts.size)
        val alert = alerts.first()
        assertEquals(WeChatChannelHealthAlerts.NO_TOKEN_HEADLINE, alert.headline)
        assertTrue(alert.headline.contains("请让对方先发一条消息"))
        assertTrue(alert.actionRequired)
    }

    @Test
    fun tokenExpired_fromLastError_surfacesHowToRecover() {
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(
            snapshot(
                lastError = "outbox_dead: context_token 已过期（会话超过 24 小时）：请让对方重新发送一条消息后再试",
                lastErrorAtMs = now,
            ),
            now,
        )

        assertEquals(1, alerts.size)
        assertTrue(alerts.first().headline.contains("请让对方重新发送一条消息"))
    }

    @Test
    fun tokenMissing_onlyInRecentFailures_stillSurfaces() {
        // lastError 已经被一次成功轮询清空（onPollSuccess 会置 null），但 outbox 里
        // 那条判死的记录还在——这正是「对方不回消息了」最典型的现场。
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(
            snapshot(
                lastError = null,
                consecutiveFailures = 0,
                recentFailures = listOf(
                    failure("f1", "outbox_dead: context_token 缺失：请让对方先发一条消息", ageMs = 300_000L),
                ),
            ),
            now,
        )

        assertEquals(1, alerts.size)
        assertEquals(WeChatChannelHealthAlerts.NO_TOKEN_HEADLINE, alerts.first().headline)
        assertTrue(alerts.first().detail.orEmpty().contains("5 分钟前"))
    }

    // ---- 轮询连续失败 -----------------------------------------------------

    @Test
    fun pollFailure_surfacesCountReasonAndRawMessage() {
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(
            snapshot(
                lastError = "poll_timeout: socket timeout after 30s",
                lastErrorAtMs = now - 30_000L,
                consecutiveFailures = 4,
            ),
            now,
        )

        assertEquals(1, alerts.size)
        val alert = alerts.first()
        assertTrue(alert.headline.contains("4"))
        assertTrue(alert.headline.contains("轮询超时"))
        assertTrue(alert.detail.orEmpty().contains("socket timeout after 30s"))
        assertEquals(WeChatChannelHealthAlerts.AlertSeverity.WARNING, alert.severity)
        assertFalse(alert.actionRequired)
    }

    @Test
    fun pollFailure_withoutCount_isNotRendered() {
        // consecutiveFailures 已经归零（onPollSuccess），说明通道已经恢复：
        // 此时残留的 lastError 不该再吓用户一跳。
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(
            snapshot(lastError = "poll_timeout: stale", lastErrorAtMs = now - 1_000L, consecutiveFailures = 0),
            now,
        )
        assertTrue(alerts.isEmpty())
    }

    // ---- outbox 发送失败明细 ---------------------------------------------

    @Test
    fun sendFailures_areRenderedUpToLimit_thenCounted() {
        val failures = (1..5).map { i ->
            failure("f$i", "outbox_send: connection reset by peer #$i", ageMs = i * 60_000L)
        }
        val health = snapshot(recentFailures = failures)
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(health, now)

        // 3 条明细 + 1 条「另有 N 条」
        assertEquals(WeChatChannelHealthAlerts.MAX_SEND_FAILURE_ROWS + 1, alerts.size)
        val details = alerts.filter { it.detail != null }
        assertEquals(3, details.size)
        assertTrue(details.first().detail.orEmpty().contains("#1"))
        assertTrue(details.last().detail.orEmpty().contains("#3"))
        val tail = alerts.last()
        assertTrue(tail.headline.contains("2"))
        assertEquals(WeChatChannelHealthAlerts.AlertSeverity.INFO, tail.severity)
        assertNull(tail.detail)
        // 完整摘要文案、唯一摘要与独立计数函数保持一致。
        assertEquals("另有 2 条发送失败未显示", tail.headline)
        assertEquals(1, alerts.count { it.severity == WeChatChannelHealthAlerts.AlertSeverity.INFO })
        assertEquals(2, WeChatChannelHealthAlerts.hiddenSendFailureCount(health, alerts, now))
    }

    @Test
    fun sendFailures_belowLimit_areNotCounted() {
        val health = snapshot(
            recentFailures = listOf(failure("f1", "outbox_send: boom", ageMs = 1_000L)),
        )
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(health, now)
        assertEquals(1, alerts.size)
        assertEquals(0, WeChatChannelHealthAlerts.hiddenSendFailureCount(health, alerts, now))
    }

    @Test
    fun sendFailures_atLimit_haveNoSummary() {
        val health = snapshot(recentFailures = (1..3).map {
            failure("f$it", "outbox_send: failure #$it")
        })
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(health, now)
        assertEquals(3, alerts.size)
        assertTrue(alerts.all { it.headline == WeChatChannelHealthAlerts.SEND_FAILED_HEADLINE })
        assertEquals(0, WeChatChannelHealthAlerts.hiddenSendFailureCount(health, alerts, now))
    }

    @Test
    fun sendFailures_duplicatesAndTokens_doNotInflateHiddenCount() {
        val unique = (1..5).map { failure("f$it", "outbox_send: failure #$it") }
        val health = snapshot(recentFailures = listOf(
            failure("token", "outbox_dead: context_token 缺失：请让对方先发一条消息"),
        ) + unique + listOf(
            unique.first().copy(id = "shown-duplicate"),
            unique.last().copy(id = "hidden-duplicate"),
        ))
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(health, now)
        assertEquals(5, alerts.size) // token + 三条明细 + 一条摘要
        assertEquals(WeChatChannelHealthAlerts.NO_TOKEN_HEADLINE, alerts.first().headline)
        assertEquals(3, alerts.count { it.headline == WeChatChannelHealthAlerts.SEND_FAILED_HEADLINE })
        assertEquals("另有 2 条发送失败未显示", alerts.last().headline)
        assertEquals(2, WeChatChannelHealthAlerts.hiddenSendFailureCount(health, alerts, now))
    }

    @Test
    fun sendFailures_truncatedAndBlankDetails_useRenderedDedupeKey() {
        val prefix = "x".repeat(WeChatChannelHealthAlerts.MAX_MESSAGE_CHARS)
        val health = snapshot(recentFailures = listOf(
            failure("blank1", null),
            failure("blank2", "outbox_send: "),
            failure("f2", "outbox_send: second"),
            failure("f3", "outbox_send: third"),
            failure("hidden1", "outbox_send: " + prefix + "A"),
            failure("hidden2", "outbox_send: " + prefix + "B"),
        ))
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(health, now)
        assertEquals(4, alerts.size)
        assertNull(alerts.first().detail)
        assertEquals("另有 1 条发送失败未显示", alerts.last().headline)
        assertEquals(1, WeChatChannelHealthAlerts.hiddenSendFailureCount(health, alerts, now))
    }

    @Test
    fun sendFailures_identicalToLastError_areDeduped() {
        // 同一件事同时出现在 lastError 与 outbox 明细里时，只渲染一次。
        val tokenMessage = "outbox_dead: context_token 缺失：请让对方先发一条消息"
        val health = snapshot(
            lastError = tokenMessage,
            lastErrorAtMs = now,
            recentFailures = listOf(failure("f1", tokenMessage, ageMs = 0L)),
        )
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(health, now)

        assertEquals(1, alerts.size)
        assertEquals(WeChatChannelHealthAlerts.NO_TOKEN_HEADLINE, alerts.first().headline)
        assertEquals(0, WeChatChannelHealthAlerts.hiddenSendFailureCount(health, alerts, now))
    }

    @Test
    fun tokenFailureInRecentFailures_isNotAlsoRenderedAsSendFailure() {
        // 回归：token 类失败只渲染「该让对方发一条消息」那一句，不再重复渲染 outbox 原文。
        // （首版实现漏了这条，导致同一件事渲染两行——单测当场抓到。）
        val health = snapshot(
            lastError = null,
            recentFailures = listOf(
                failure("f1", "outbox_dead: context_token 缺失：请让对方先发一条消息", ageMs = 0L),
                failure("f2", "outbox_send: connection reset", ageMs = 0L),
            ),
        )
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(health, now)

        assertEquals(2, alerts.size)
        assertEquals(WeChatChannelHealthAlerts.NO_TOKEN_HEADLINE, alerts[0].headline)
        assertEquals(WeChatChannelHealthAlerts.SEND_FAILED_HEADLINE, alerts[1].headline)
        assertEquals(0, WeChatChannelHealthAlerts.hiddenSendFailureCount(health, alerts, now))
    }

    @Test
    fun sendFailures_differentDetails_areNotDeduped() {
        // 去重键包含细节：同样是 outbox_send，原因不同就不能合并。
        val health = snapshot(
            recentFailures = listOf(
                failure("f1", "outbox_send: connection reset", ageMs = 0L),
                failure("f2", "outbox_send: image file missing", ageMs = 0L),
            ),
        )
        assertEquals(2, WeChatChannelHealthAlerts.fromSnapshot(health, now).size)
    }

    @Test
    fun sendFailure_withoutErrorText_stillRendersOneRow() {
        val health = snapshot(recentFailures = listOf(failure("f1", null, ageMs = 0L)))
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(health, now)
        assertEquals(1, alerts.size)
        assertEquals(WeChatChannelHealthAlerts.SEND_FAILED_HEADLINE, alerts.first().headline)
    }

    // ---- 顺序：可操作的排前面 ---------------------------------------------

    @Test
    fun alerts_areOrderedByActionability() {
        val alerts = WeChatChannelHealthAlerts.fromSnapshot(
            snapshot(
                lastError = "poll_timeout: socket timeout",
                lastErrorAtMs = now,
                consecutiveFailures = 2,
                recentFailures = listOf(failure("f1", "outbox_send: boom", ageMs = 0L)),
            ),
            now,
        )

        assertEquals(2, alerts.size)
        assertTrue(alerts[0].headline.startsWith("轮询连续失败"))
        assertEquals(WeChatChannelHealthAlerts.SEND_FAILED_HEADLINE, alerts[1].headline)
    }

    // ---- 纯工具函数 -------------------------------------------------------

    @Test
    fun detailOf_stripsWireNamePrefix_onlyAtFirstSeparator() {
        assertEquals(
            "请重新扫码登录",
            WeChatChannelHealthAlerts.detailOf("poll_auth: 请重新扫码登录"),
        )
        assertEquals(
            "context_token 缺失：请让对方先发一条消息",
            WeChatChannelHealthAlerts.detailOf("outbox_dead: context_token 缺失：请让对方先发一条消息"),
        )
        // 没有分隔符：原样返回，宁可显示原文也不要显示空白
        assertEquals("raw failure", WeChatChannelHealthAlerts.detailOf("raw failure"))
    }

    @Test
    fun formatAge_coversBoundaries() {
        assertNull(WeChatChannelHealthAlerts.formatAge(0L, now))
        assertNull(WeChatChannelHealthAlerts.formatAge(now, now))
        // 时钟回拨：now < atMs
        assertNull(WeChatChannelHealthAlerts.formatAge(now + 1_000L, now))
        assertEquals("刚刚", WeChatChannelHealthAlerts.formatAge(now - 1_000L, now))
        assertEquals("5 分钟前", WeChatChannelHealthAlerts.formatAge(now - 5 * 60_000L, now))
        assertEquals("2 小时前", WeChatChannelHealthAlerts.formatAge(now - 2 * 3_600_000L, now))
        assertEquals("3 天前", WeChatChannelHealthAlerts.formatAge(now - 3 * 86_400_000L, now))
    }

    @Test
    fun matchesTokenMarkers_recognisesBothOutboxMessages() {
        assertTrue(WeChatChannelHealthAlerts.matchesTokenMarkers("outbox_dead: context_token 缺失：x"))
        assertTrue(WeChatChannelHealthAlerts.matchesTokenMarkers("outbox_dead: context_token 已过期：x"))
        assertTrue(WeChatChannelHealthAlerts.matchesTokenMarkers("missing latest context token"))
        assertFalse(WeChatChannelHealthAlerts.matchesTokenMarkers("poll_timeout: socket timeout"))
        assertFalse(WeChatChannelHealthAlerts.matchesTokenMarkers(null))
    }

    // ---- 常量与来源不漂移 -------------------------------------------------

    @Test
    fun headlineConstants_keepTheSourceWording() {
        // 这三句是与 core 侧常量「同义」的副本，断言把措辞钉住：
        // 若有人改了 core 里的 SEND_BLOCKED_* / SESSION_EXPIRED_USER_MESSAGE 而忘了这里，
        // 这条断言至少能在评审时被看见（单测跨模块读不到 core:wechat 的常量）。
        assertTrue(WeChatChannelHealthAlerts.SESSION_EXPIRED_HEADLINE.contains("errcode=-14"))
        assertTrue(WeChatChannelHealthAlerts.SESSION_EXPIRED_HEADLINE.contains("重新扫码登录"))
        assertTrue(WeChatChannelHealthAlerts.NO_TOKEN_HEADLINE.startsWith("context_token 缺失"))
        assertTrue(WeChatChannelHealthAlerts.EXPIRED_TOKEN_HEADLINE.startsWith("context_token 已过期"))
        assertTrue(WeChatChannelHealthAlerts.EXPIRED_TOKEN_HEADLINE.contains("24 小时"))
    }
}
