package com.lianyu.ai.wechat.outbox

import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.dao.WeChatOutboxDao
import com.lianyu.ai.database.model.WeChatOutboxEntity
import com.lianyu.ai.domain.wechat.WeChatContentKind
import com.lianyu.ai.domain.wechat.WeChatDeliveryStatus
import com.lianyu.ai.domain.wechat.WeChatFailureReason
import com.lianyu.ai.domain.wechat.WeChatOutboundRequest
import com.lianyu.ai.domain.wechat.WeChatOutboxFailure
import com.lianyu.ai.wechat.ilink.IlinkSessionStore
import com.lianyu.ai.wechat.map.WeChatOutboundSegmenter
import com.lianyu.ai.wechat.transport.WeChatTransportPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * 微信出站队列（S1）：SIMPLE 分段入队 + 持久化 + 可重试 drain。
 * S5：SecureLog + 状态计数 / 近期失败查询。
 */
class WeChatOutboxCoordinator(
    private val dao: WeChatOutboxDao,
    private val transport: WeChatTransportPort,
    private val sessionStore: IlinkSessionStore? = null,
    private val segmentGapMs: Long = DEFAULT_SEGMENT_GAP_MS,
    private val maxRetry: Int = DEFAULT_MAX_RETRY,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val managedMediaCacheDir: File? = null,
) {
    private val drainMutex = Mutex()

    /**
     * 将请求按 SIMPLE 分段写入 Outbox。
     * @return rootId
     */
    suspend fun enqueue(
        request: WeChatOutboundRequest,
        wechatUserId: String = request.wechatUserId.orEmpty(),
        rootId: String = UUID.randomUUID().toString(),
    ): String {
        require(wechatUserId.isNotBlank()) { "wechatUserId blank" }
        val created = nowMs()
        val entities = buildEntities(request, wechatUserId, rootId, created)
        if (entities.isNotEmpty()) {
            dao.insertAll(entities)
            SecureLog.i(
                TAG,
                "enqueue rootId=${rootId.take(8)} segs=${entities.size} kind=${entities.firstOrNull()?.kind} user=${maskUser(wechatUserId)}",
            )
        }
        return rootId
    }

    /**
     * 发送就绪段；同一次 drain 内按序串行发送。
     * @return 本轮成功发送条数
     */
    suspend fun drain(limit: Int = DEFAULT_DRAIN_LIMIT): Int = drainMutex.withLock {
        val ready = dao.listReady(nowMs = nowMs(), limit = limit)
        var sent = 0
        val deliveries = ready.map { it.rootId }.distinct().map { rootId ->
            dao.listOpenByRootId(rootId).ifEmpty { ready.filter { it.rootId == rootId } }
        }
        for ((index, items) in deliveries.withIndex()) {
            if (index > 0 && segmentGapMs > 0) {
                delay(segmentGapMs)
            }
            val delivered = if (
                items.all { WeChatContentKind.fromWireType(it.kind) == WeChatContentKind.TEXT } &&
                items.all { it.nextAttemptAtMs <= nowMs() }
            ) {
                dispatchTextSegments(items.sortedBy { it.segmentIndex })
            } else {
                items.filter { it.nextAttemptAtMs <= nowMs() }.count { dispatchOne(it) }
            }
            sent += delivered
        }
        // 清理过期记录，再删除已无 Outbox 引用的受管缓存文件。
        val cutoff = nowMs() - SENT_RETENTION_MS
        dao.deleteSentBefore(cutoff)
        dao.deleteDeadBefore(maxRetry = maxRetry, cutoffMs = cutoff)
        cleanupManagedCache(dao.listMediaLocalPaths().toSet(), cutoff)
        if (sent > 0) {
            SecureLog.i(TAG, "drain sent=$sent ready=${ready.size}")
        }
        sent
    }

    private suspend fun dispatchTextSegments(items: List<WeChatOutboxEntity>): Int {
        var sent = 0
        for ((index, item) in items.withIndex()) {
            if (index > 0 && segmentGapMs > 0) {
                delay(segmentGapMs)
            }
            if (dispatchOne(item)) {
                sent++
            }
        }
        return sent
    }

    internal fun cleanupManagedCache(referencedPaths: Set<String>, cutoffMs: Long): Int {
        val cacheDir = managedMediaCacheDir?.takeIf { it.exists() && it.isDirectory } ?: return 0
        val canonicalDir = runCatching { cacheDir.canonicalFile }.getOrNull() ?: return 0
        val canonicalReferences = referencedPaths.mapNotNullTo(mutableSetOf()) { path ->
            runCatching { File(path).canonicalPath }.getOrNull()
        }
        return canonicalDir.listFiles().orEmpty().count { file ->
            file.isFile &&
                file.lastModified() < cutoffMs &&
                file.canonicalFile.parentFile == canonicalDir &&
                file.canonicalPath !in canonicalReferences &&
                file.delete()
        }
    }

    suspend fun openCount(): Int = dao.countOpen()

    /** S5：按投递状态计数 */
    suspend fun countByStatus(status: WeChatDeliveryStatus): Int =
        dao.countByStatus(status.name)

    /** S5：近期失败摘要 */
    suspend fun recentFailures(limit: Int = DEFAULT_RECENT_FAILURE_LIMIT): List<WeChatOutboxFailure> {
        return dao.listRecentFailed(limit).map { entity ->
            WeChatOutboxFailure(
                id = entity.id,
                wechatUserId = entity.wechatUserId,
                kind = WeChatContentKind.fromWireType(entity.kind).name,
                retryCount = entity.retryCount,
                lastError = entity.lastError,
                updatedAtMs = entity.updatedAtMs,
            )
        }
    }

    /** 供单测：SIMPLE 展开为 Room 行（不写库）。 */
    fun buildEntities(
        request: WeChatOutboundRequest,
        wechatUserId: String,
        rootId: String,
        createdAtMs: Long,
    ): List<WeChatOutboxEntity> {
        val segments = WeChatOutboundSegmenter.expand(request, wechatUserId, rootId)
        return segments.map { seg ->
            WeChatOutboxEntity(
                id = seg.outboxId,
                rootId = rootId,
                companionId = request.companionId,
                wechatUserId = wechatUserId,
                kind = seg.kind.wireType,
                text = seg.text,
                mediaLocalPath = seg.media?.localPath,
                mediaFileName = seg.media?.fileName,
                mediaDescription = seg.media?.description,
                segmentIndex = seg.segmentIndex,
                segmentCount = seg.segmentCount,
                sourceMessageId = request.sourceMessageId,
                status = WeChatDeliveryStatus.PENDING.name,
                retryCount = 0,
                nextAttemptAtMs = 0L,
                lastError = null,
                createdAtMs = createdAtMs,
                updatedAtMs = createdAtMs,
            )
        }
    }

    private suspend fun dispatchOne(item: WeChatOutboxEntity): Boolean {
        dao.updateStatus(item.id, WeChatDeliveryStatus.SENDING.name)
        val result = runCatching {
            val contextToken = resolveContextToken(item.wechatUserId)
            when (WeChatContentKind.fromWireType(item.kind)) {
                WeChatContentKind.TEXT -> {
                    val text = item.text?.takeIf { it.isNotBlank() }
                        ?: error("empty text segment")
                    transport.sendText(item.wechatUserId, text, contextToken).getOrThrow()
                }
                WeChatContentKind.IMAGE -> {
                    val path = item.mediaLocalPath ?: error("image path missing")
                    val bytes = File(path).takeIf { it.exists() }?.readBytes()
                        ?: error("image file missing: $path")
                    transport.sendImage(
                        toUserId = item.wechatUserId,
                        imageBytes = bytes,
                        fileName = item.mediaFileName ?: "image.png",
                        description = item.mediaDescription,
                        contextToken = contextToken,
                    ).getOrThrow()
                }
                else -> error("unsupported outbox kind=${item.kind}")
            }
        }
        return if (result.isSuccess) {
            dao.updateStatus(item.id, WeChatDeliveryStatus.SENT.name)
            true
        } else {
            updateFailure(item, result.exceptionOrNull())
            false
        }
    }

    private suspend fun resolveContextToken(wechatUserId: String): String? =
        sessionStore?.getSessionAccount()?.let { account ->
            sessionStore.getContextToken(account.accountId, wechatUserId)
        }

    private suspend fun updateFailure(item: WeChatOutboxEntity, error: Throwable?) {
        val raw = error?.message ?: "send failed"
        val nextRetry = item.retryCount + 1
        val reason = if (nextRetry >= maxRetry) {
            WeChatFailureReason.OUTBOX_DEAD
        } else {
            WeChatFailureReason.OUTBOX_SEND
        }
        val err = "${reason.wireName}: ${raw.take(160)}"
        if (nextRetry >= maxRetry) {
            dao.updateAttempt(
                id = item.id,
                status = WeChatDeliveryStatus.FAILED.name,
                retryCount = nextRetry,
                nextAttemptAtMs = Long.MAX_VALUE / 4,
                lastError = err,
            )
            SecureLog.w(
                TAG,
                "dead id=${item.id.take(8)} retry=$nextRetry user=${maskUser(item.wechatUserId)} err=$err",
            )
        } else {
            val backoff = (1L shl nextRetry.coerceAtMost(6)) * 1000L
            dao.updateAttempt(
                id = item.id,
                status = WeChatDeliveryStatus.FAILED.name,
                retryCount = nextRetry,
                nextAttemptAtMs = nowMs() + backoff.coerceAtMost(60_000L),
                lastError = err,
            )
            SecureLog.w(
                TAG,
                "retry id=${item.id.take(8)} retry=$nextRetry backoff=${backoff.coerceAtMost(60_000L)}ms err=$err",
            )
        }
    }

    companion object {
        private const val TAG = "WeChatOutbox"
        const val DEFAULT_SEGMENT_GAP_MS = 0L
        const val DEFAULT_MAX_RETRY = 5
        const val DEFAULT_DRAIN_LIMIT = 20
        const val DEFAULT_RECENT_FAILURE_LIMIT = 5
        private const val SENT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        private fun maskUser(userId: String): String {
            if (userId.length <= 6) return "***"
            return userId.take(3) + "***" + userId.takeLast(2)
        }
    }
}
