package com.lianyu.ai.feature.wechat.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WeChatChannelRuntimeTest {

    @Before
    fun setUp() {
        WeChatChannelRuntime.reset()
    }

    @After
    fun tearDown() {
        WeChatChannelRuntime.reset()
    }

    @Test
    fun claimPrimaryPoller_isExclusive() {
        assertTrue(WeChatChannelRuntime.claimPrimaryPoller())
        assertFalse(WeChatChannelRuntime.claimPrimaryPoller())
        assertTrue(WeChatChannelRuntime.isPrimaryPollerActive())
        assertTrue(WeChatChannelRuntime.shouldSkipFallbackPoll())

        WeChatChannelRuntime.releasePrimaryPoller()
        assertFalse(WeChatChannelRuntime.isPrimaryPollerActive())
        assertFalse(WeChatChannelRuntime.shouldSkipFallbackPoll())
        assertTrue(WeChatChannelRuntime.claimPrimaryPoller())
    }

    @Test
    fun backoff_growsWithFailures_andCaps() {
        WeChatChannelRuntime.onPollFailure("socket timeout")
        val first = WeChatChannelRuntime.nextBackoffMs()
        assertTrue(first >= WeChatChannelRuntime.BASE_BACKOFF_MS)

        repeat(8) { WeChatChannelRuntime.onPollFailure("failed to connect") }
        val capped = WeChatChannelRuntime.nextBackoffMs()
        assertTrue(capped <= WeChatChannelRuntime.MAX_BACKOFF_MS)
        assertTrue(WeChatChannelRuntime.consecutiveFailures() >= 9)

        WeChatChannelRuntime.onPollSuccess()
        assertEquals(0, WeChatChannelRuntime.consecutiveFailures())
        assertEquals(null, WeChatChannelRuntime.lastError())
    }

    @Test
    fun healthSnapshot_includesRuntimeAndOutboxFields() {
        assertTrue(WeChatChannelRuntime.claimPrimaryPoller())
        WeChatChannelRuntime.onPollFailure("HTTP 403 forbidden")

        val snap = WeChatChannelRuntime.healthSnapshot(
            openOutboxCount = 3,
            pendingOutboxCount = 1,
            failedOutboxCount = 2,
            sendingOutboxCount = 0,
        )

        assertTrue(snap.primaryPollerActive)
        assertEquals(1, snap.consecutiveFailures)
        assertEquals(3, snap.openOutboxCount)
        assertEquals(1, snap.pendingOutboxCount)
        assertEquals(2, snap.failedOutboxCount)
        assertTrue(snap.lastError?.contains("poll_auth") == true)
        assertTrue(snap.lastErrorAtMs > 0L)
    }

    @Test
    fun shouldSkipFallbackPoll_falseWhenPrimaryStale() {
        assertTrue(WeChatChannelRuntime.claimPrimaryPoller())
        WeChatChannelRuntime.onPollSuccess()
        val last = WeChatChannelRuntime.lastPollAtMs()
        assertTrue(WeChatChannelRuntime.shouldSkipFallbackPoll(last + 1_000L))
        assertFalse(
            WeChatChannelRuntime.shouldSkipFallbackPoll(
                last + WeChatChannelRuntime.STALE_POLL_MS + 1L,
            ),
        )
    }

    @Test
    fun evaluateWatchdog_primaryStale_requiresRelease() {
        assertTrue(WeChatChannelRuntime.claimPrimaryPoller())
        WeChatChannelRuntime.onPollSuccess()
        val last = WeChatChannelRuntime.lastPollAtMs()
        val decision = WeChatChannelRuntime.evaluateWatchdog(
            last + WeChatChannelRuntime.STALE_POLL_MS + 5_000L,
        )
        assertTrue(decision.needsAction)
        assertTrue(decision.forceReleasePrimary)
        assertTrue(decision.reason.contains("primary_stale"))
    }

    @Test
    fun evaluateWatchdog_healthyPrimary_noAction() {
        assertTrue(WeChatChannelRuntime.claimPrimaryPoller())
        WeChatChannelRuntime.onPollSuccess()
        val decision = WeChatChannelRuntime.evaluateWatchdog(
            WeChatChannelRuntime.lastPollAtMs() + 1_000L,
        )
        assertFalse(decision.needsAction)
        assertFalse(decision.forceReleasePrimary)
    }

    @Test
    fun tryBeginHeal_respectsCooldown() {
        val t0 = 1_000_000L
        assertTrue(WeChatChannelRuntime.tryBeginHeal(t0))
        assertFalse(WeChatChannelRuntime.tryBeginHeal(t0 + 1_000L))
        assertTrue(
            WeChatChannelRuntime.tryBeginHeal(
                t0 + WeChatChannelRuntime.HEAL_COOLDOWN_MS + 1L,
            ),
        )
    }

    @Test
    fun isPollActivityStale_usesClaimTimeWhenNeverPolled() {
        assertTrue(WeChatChannelRuntime.claimPrimaryPoller())
        val claimedAt = WeChatChannelRuntime.primaryClaimedAtMs()
        assertFalse(WeChatChannelRuntime.isPollActivityStale(claimedAt + 1_000L))
        assertTrue(
            WeChatChannelRuntime.isPollActivityStale(
                claimedAt + WeChatChannelRuntime.STALE_POLL_MS + 1L,
            ),
        )
    }
}
