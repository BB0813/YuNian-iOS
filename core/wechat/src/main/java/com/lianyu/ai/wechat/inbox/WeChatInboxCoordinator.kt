package com.lianyu.ai.wechat.inbox

import com.lianyu.ai.database.dao.WeChatInboxDedupeDao
import com.lianyu.ai.database.model.WeChatInboxDedupeEntity
import com.lianyu.ai.domain.wechat.WeChatInboundMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 入站去重 + per-user 串行队列（S2）。
 *
 * - 去重：入队前 claim（insertIgnore），避免重复 AI；handler 失败由调用方 Worker 兜底，**不**回滚 claim
 *   （微信侧 commit 后无法靠重拉恢复，必须本地重试）
 * - 串行：同 fromUserId 消息排队处理，**禁止**「已有 job 则跳过」
 * - [acceptIfNew] 默认只等待「入队/claim」，不阻塞长 AI，保证 poll 循环可继续 getUpdates
 * - SharedFlow 不作为处理主路径
 */
class WeChatInboxCoordinator(
    private val dedupeDao: WeChatInboxDedupeDao,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val queues = ConcurrentHashMap<String, UserQueue>()
    private val mapMutex = Mutex()

    /**
     * @param awaitHandler false（默认）：入队即返回，AI 在 per-user 队列异步执行，不堵 poll
     * @param awaitHandler true：等待 handler 完成（单测 / 需要严格完成语义时）
     * @return true 若首次接受并已入队；false 若重复
     */
    suspend fun acceptIfNew(
        inbound: WeChatInboundMessage,
        awaitHandler: Boolean = false,
        handler: suspend (WeChatInboundMessage) -> Unit,
    ): Boolean {
        return enqueue(inbound.fromUserId, inbound, awaitHandler, handler)
    }

    /**
     * 仅去重检查并标记，不入队（调用方自行处理）。
     */
    suspend fun tryMarkProcessed(inbound: WeChatInboundMessage): Boolean {
        val inserted = dedupeDao.insertIgnore(
            WeChatInboxDedupeEntity(
                dedupeKey = inbound.dedupeKey,
                messageId = inbound.messageId,
                fromUserId = inbound.fromUserId,
                processedAtMs = nowMs(),
            ),
        )
        return inserted != -1L
    }

    suspend fun purgeExpired(ttlMs: Long = DEFAULT_TTL_MS): Int {
        return dedupeDao.deleteOlderThan(nowMs() - ttlMs)
    }

    fun cancelAll() {
        queues.values.forEach { it.job.cancel() }
        queues.clear()
    }

    private suspend fun enqueue(
        userId: String,
        inbound: WeChatInboundMessage,
        awaitHandler: Boolean,
        handler: suspend (WeChatInboundMessage) -> Unit,
    ): Boolean {
        if (dedupeDao.findKey(inbound.dedupeKey) != null) {
            return false
        }
        val q = mapMutex.withLock {
            queues.getOrPut(userId) {
                UserQueue(scope, dedupeDao, nowMs).also { it.start() }
            }
        }
        return q.offer(WorkItem(inbound, handler), awaitHandler)
    }

    private class WorkItem(
        val inbound: WeChatInboundMessage,
        val handler: suspend (WeChatInboundMessage) -> Unit,
        val accepted: CompletableDeferred<Boolean> = CompletableDeferred(),
        val finished: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private class UserQueue(
        private val scope: CoroutineScope,
        private val dedupeDao: WeChatInboxDedupeDao,
        private val nowMs: () -> Long,
    ) {
        private val channel = Channel<WorkItem>(capacity = Channel.UNLIMITED)
        lateinit var job: Job
            private set

        fun start() {
            job = scope.launch {
                for (item in channel) {
                    val claimed = runCatching {
                        dedupeDao.insertIgnore(
                            WeChatInboxDedupeEntity(
                                dedupeKey = item.inbound.dedupeKey,
                                messageId = item.inbound.messageId,
                                fromUserId = item.inbound.fromUserId,
                                processedAtMs = nowMs(),
                            ),
                        ) != -1L
                    }.getOrDefault(false)

                    if (!claimed) {
                        item.accepted.complete(false)
                        item.finished.complete(Unit)
                        continue
                    }

                    item.accepted.complete(true)
                    try {
                        item.handler(item.inbound)
                        item.finished.complete(Unit)
                    } catch (error: Throwable) {
                        // claim 保留：微信已 commit 的消息只能靠本地 Worker/重试完成 AI
                        item.finished.completeExceptionally(error)
                    }
                }
            }
        }

        /**
         * @return true 若 claim 成功（或仍在等待 claim 时已入队且最终 claim 成功）
         */
        suspend fun offer(item: WorkItem, awaitHandler: Boolean): Boolean {
            channel.send(item)
            val accepted = item.accepted.await()
            if (awaitHandler && accepted) {
                item.finished.await()
            }
            return accepted
        }
    }

    companion object {
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}

