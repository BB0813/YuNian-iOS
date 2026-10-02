package com.yunian.ai.wechat

import com.yunian.ai.wechat.outbox.WeChatOutboxCoordinator
import com.yunian.ai.wechat.outbox.WeChatOutboxRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SENDING 僵尸行恢复的纯决策单测（不依赖 Room / Android）。
 *
 * 「在旧实现下为何会失败」：旧实现里根本不存在恢复这件事——SENDING 行既不在
 * listReady（PENDING/FAILED）里，也不在 listOpenByRootId 里，没有任何代码路径会
 * 把它重置回 PENDING。因此这些断言在旧实现下无对应代码可跑（编译不过）。
 */
class WeChatOutboxRecoveryTest {

    private val now = 1_700_000_000_000L
    private val lease = WeChatOutboxCoordinator.SENDING_LEASE_MS

    private fun candidate(id: String, retryCount: Int, stuckAtMs: Long) =
        WeChatOutboxRecovery.Candidate(
            id = id,
            retryCount = retryCount,
            wechatUserId = "user-1",
            updatedAtMs = stuckAtMs,
        )

    @Test
    fun plan_requeuesStaleSendingAndBumpsRetry() {
        val plan = WeChatOutboxRecovery.plan(
            candidates = listOf(candidate("r1#0", retryCount = 0, stuckAtMs = now - lease - 1)),
            nowMs = now,
            maxRetry = WeChatOutboxCoordinator.DEFAULT_MAX_RETRY,
        )

        val outcome = plan.outcomes.single() as WeChatOutboxRecovery.Outcome.Requeued
        assertEquals("r1#0", outcome.id)
        assertEquals(1, outcome.retryCount)
        // 不立刻重发：进程刚起来通道往往还没就绪，先退避 2s
        assertEquals(now + 2_000L, outcome.nextAttemptAtMs)
        assertTrue(outcome.lastError.startsWith(WeChatOutboxRecovery.RECOVERED_MARKER))
        assertEquals(1, plan.recovered)
        assertEquals(0, plan.dead)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun plan_backoffGrowsWithRetryAndCapsAtOneMinute() {
        assertEquals(2_000L, WeChatOutboxRecovery.recoveryBackoffMs(1))
        assertEquals(4_000L, WeChatOutboxRecovery.recoveryBackoffMs(2))
        assertEquals(8_000L, WeChatOutboxRecovery.recoveryBackoffMs(3))
        assertEquals(16_000L, WeChatOutboxRecovery.recoveryBackoffMs(4))
        assertEquals(32_000L, WeChatOutboxRecovery.recoveryBackoffMs(5))
        assertEquals(60_000L, WeChatOutboxRecovery.recoveryBackoffMs(6))
        assertEquals(60_000L, WeChatOutboxRecovery.recoveryBackoffMs(7))
        assertEquals(60_000L, WeChatOutboxRecovery.recoveryBackoffMs(64))
        // 单调不减，且永不溢出成负数（Long.MAX_VALUE/4 那种哨兵值不允许出现在这里）
        var prev = 0L
        for (retry in 1..64) {
            val current = WeChatOutboxRecovery.recoveryBackoffMs(retry)
            assertTrue(current >= prev)
            assertTrue(current > 0L)
            prev = current
        }
    }

    @Test
    fun plan_marksDeadWhenRetryBudgetExhausted() {
        val plan = WeChatOutboxRecovery.plan(
            candidates = listOf(candidate("r2#0", retryCount = 4, stuckAtMs = now - lease - 1)),
            nowMs = now,
            maxRetry = WeChatOutboxCoordinator.DEFAULT_MAX_RETRY,
        )

        val outcome = plan.outcomes.single() as WeChatOutboxRecovery.Outcome.Dead
        assertEquals("r2#0", outcome.id)
        // 不改 retryCount：判死回收仍走既有 deleteDeadBefore(retryCount >= maxRetry) 路径
        assertEquals(4, outcome.retryCount)
        assertTrue(outcome.lastError.startsWith(WeChatOutboxRecovery.DEAD_MARKER))
        assertEquals(0, plan.recovered)
        assertEquals(1, plan.dead)
    }

    @Test
    fun plan_isEmptyForNoCandidates() {
        val plan = WeChatOutboxRecovery.plan(emptyList(), nowMs = now, maxRetry = 5)
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.recovered)
        assertEquals(0, plan.dead)
    }

    @Test
    fun leaseIsFarLongerThanWorstCaseSingleSend() {
        // 单条发送的最坏预算：文本 15s；图片 = getuploadurl 15s + CDN write 30s + read 30s ≈ 70s。
        // 租约必须显著大于它，否则会把「仍在发送中」的行误判成僵尸并重复投递。
        assertTrue(WeChatOutboxCoordinator.SENDING_LEASE_MS >= 4 * 70_000L)
    }
}
