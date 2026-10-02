package com.yunian.ai.wechat.outbox

import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.dao.WeChatOutboxDao
import com.yunian.ai.database.model.WeChatOutboxEntity
import com.yunian.ai.domain.wechat.WeChatDeliveryStatus

/**
 * SENDING 僵尸行的启动/周期性恢复。
 *
 * ## 缺陷
 * [WeChatOutboxCoordinator.dispatchOne] 先把行写成 SENDING 再调用 transport 发送。
 * 进程在「写完 SENDING」与「发送结果落库」之间被杀（崩溃、被系统回收、断电），
 * 该行会永久停在 SENDING：DAO 的 [WeChatOutboxDao.listReady] 只取 PENDING/FAILED，
 * [WeChatOutboxDao.listOpenByRootId] 同样排除 SENDING，因此没有任何查询会再捡起它。
 * 用户视角就是「消息发了没反应」——既不发出，也不失败，也不重试。
 *
 * ## 恢复策略
 * 用 **行时间戳租约** 代替新增列：dispatchOne 写 SENDING 时 updatedAtMs = now，
 * 因此 now - updatedAtMs > SENDING_LEASE_MS 就意味着「这条发送已经超出租约」。
 * 超租约的行重置回 PENDING，并 **把 retryCount 加一**，让既有重试/判死链路继续约束它：
 * 反复崩溃不会无限复活同一条消息，最多多试 [WeChatOutboxCoordinator.DEFAULT_MAX_RETRY] 次。
 *
 * ## 与「发送已完成但状态未落库」的区别
 * 数据库里这两种情况留下的痕迹完全一样（都是 status=SENDING），无法区分，因此本方案
 * 必然偏向「宁可重发，不可静默丢失」：在发送成功、SENT 未落库的极小窗口内被杀，
 * 恢复后会造成一次重复投递。权衡依据：
 * - 重复投递是**用户可见且可自愈**的（对方多收到一条）；
 * - 静默丢失是**用户不可见且不可自愈**的（这条消息永远不会到达）。
 * - 租约取 [WeChatOutboxCoordinator.SENDING_LEASE_MS]（5 分钟），远大于任何一次
 *   单条发送的最坏耗时（文本 15s；图片 = getuploadurl 15s + CDN 上传 30s write / 30s read），
 *   所以「仍在正常发送中」的行几乎不会被误判。
 */
internal object WeChatOutboxRecovery {

    /** 待恢复的 SENDING 行（只保留决策所需字段，便于纯 JVM 单测）。 */
    data class Candidate(
        val id: String,
        val retryCount: Int,
        val wechatUserId: String,
        val updatedAtMs: Long,
    )

    sealed interface Outcome {
        /** 重置回 PENDING，交还既有重试链路。 */
        data class Requeued(
            val id: String,
            val retryCount: Int,
            val nextAttemptAtMs: Long,
            val lastError: String,
        ) : Outcome

        /**
         * 已经用满重试预算：不再复活，改写 lastError 并写上「永不重试」哨兵，
         * 交给 [WeChatOutboxCoordinator.drain] 的 deleteDeadBefore 正常回收。
         * 注意：**不改动 retryCount**，避免越过既有判死回收路径。
         */
        data class Dead(val id: String, val retryCount: Int, val lastError: String) : Outcome
    }

    data class Plan(val outcomes: List<Outcome>) {
        val recovered: Int get() = outcomes.count { it is Outcome.Requeued }
        val dead: Int get() = outcomes.count { it is Outcome.Dead }
        val isEmpty: Boolean get() = outcomes.isEmpty()
    }

    /** 恢复动作写入 lastError 的前缀，便于在 listRecentFailed / 日志里一眼认出。 */
    const val RECOVERED_MARKER = "outbox_recovered"

    const val DEAD_MARKER = "outbox_recovered_dead"

    /**
     * 「永不重试」哨兵，与 [WeChatOutboxCoordinator.updateFailure] 判死分支使用同一个值。
     *
     * 预算用尽的僵尸行必须一并写上它：该行的 nextAttemptAtMs 还停在「入队时的 0」，
     * 若不改写，下一次 drain 会立刻把它捞出来再发一遍——等于绕过了判死语义。
     */
    const val NEVER_RETRY_AT_MS = Long.MAX_VALUE / 4

    suspend fun readStaleSending(dao: WeChatOutboxDao, nowMs: Long, limit: Int): List<Candidate> =
        dao.listStaleSending(staleBeforeMs = nowMs - WeChatOutboxCoordinator.SENDING_LEASE_MS, limit = limit)
            .map { it.toCandidate() }

    /**
     * 纯决策：给定超租约的 SENDING 行，算出每条该怎么收场。
     *
     * 前置条件：调用方已按 [WeChatOutboxCoordinator.SENDING_LEASE_MS] 过滤过租约，
     * 本函数只负责「重试预算」与时间戳计算，因此可以脱离 Android/Room 直接单测。
     */
    fun plan(candidates: List<Candidate>, nowMs: Long, maxRetry: Int): Plan {
        val outcomes = candidates.map { candidate ->
            val nextRetry = candidate.retryCount + 1
            val nextAttemptAtMs = nowMs + recoveryBackoffMs(nextRetry)
            if (nextRetry >= maxRetry) {
                Outcome.Dead(
                    id = candidate.id,
                    retryCount = candidate.retryCount,
                    lastError = DEAD_MARKER + ": 发送中进程被杀且重试预算已用尽 " +
                        "(retry=" + candidate.retryCount + "/" + maxRetry +
                        ", stuckSinceMs=" + candidate.updatedAtMs + ")",
                )
            } else {
                Outcome.Requeued(
                    id = candidate.id,
                    retryCount = nextRetry,
                    nextAttemptAtMs = nextAttemptAtMs,
                    lastError = RECOVERED_MARKER + ": 发送中进程被杀，超租约 " +
                        (WeChatOutboxCoordinator.SENDING_LEASE_MS / 1000) + "s 后重置为待发 " +
                        "(retry=" + nextRetry + "/" + maxRetry + ")",
                )
            }
        }
        return Plan(outcomes)
    }

    /**
     * 恢复重试的退避：进程刚起来时通道往往还没就绪，立刻重发大概率再失败一次，
     * 所以第一次恢复等 [RECOVERY_BASE_BACKOFF_MS]，之后按 2 的幂增长并封顶
     * [RECOVERY_MAX_BACKOFF_MS]（对齐 WeChatChannelRuntime 的退避量级）。
     */
    fun recoveryBackoffMs(retryCount: Int): Long {
        val exp = RECOVERY_BASE_BACKOFF_MS shl (retryCount - 1).coerceIn(0, 5)
        return exp.coerceAtMost(RECOVERY_MAX_BACKOFF_MS)
    }

    /** 把决策落库。返回被重置回 PENDING 的行数。 */
    suspend fun applyPlan(dao: WeChatOutboxDao, plan: Plan, nowMs: Long): Int {
        var recovered = 0
        for (outcome in plan.outcomes) {
            when (outcome) {
                is Outcome.Requeued -> {
                    dao.updateAttempt(
                        id = outcome.id,
                        status = WeChatDeliveryStatus.PENDING.name,
                        retryCount = outcome.retryCount,
                        nextAttemptAtMs = outcome.nextAttemptAtMs,
                        lastError = outcome.lastError,
                        updatedAtMs = nowMs,
                    )
                    recovered++
                }

                is Outcome.Dead -> {
                    dao.updateAttempt(
                        id = outcome.id,
                        status = WeChatDeliveryStatus.FAILED.name,
                        // 保持原值：既有 deleteDeadBefore(retryCount >= maxRetry) 仍能回收它
                        retryCount = outcome.retryCount,
                        nextAttemptAtMs = NEVER_RETRY_AT_MS,
                        lastError = outcome.lastError,
                        updatedAtMs = nowMs,
                    )
                }
            }
        }
        return recovered
    }

    private fun WeChatOutboxEntity.toCandidate(): Candidate = Candidate(
        id = id,
        retryCount = retryCount,
        wechatUserId = wechatUserId,
        updatedAtMs = updatedAtMs,
    )

    private const val RECOVERY_BASE_BACKOFF_MS = 2_000L
    private const val RECOVERY_MAX_BACKOFF_MS = 60_000L

    private const val TAG = "WeChatOutbox"

    /** 恢复结果的可观测记录。 */
    fun log(plan: Plan, nowMs: Long) {
        if (plan.isEmpty) return
        SecureLog.i(
            TAG,
            "stale_sending recovered=" + plan.recovered + " dead=" + plan.dead + " atMs=" + nowMs,
        )
        plan.outcomes.filterIsInstance<Outcome.Dead>().forEach { dead ->
            SecureLog.w(TAG, "stale_sending dead id=" + dead.id.take(8) + " retry=" + dead.retryCount)
        }
    }
}
