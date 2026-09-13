package com.yunian.ai.network

import com.yunian.ai.common.ChatConstants
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.repository.AppMetaStore
import com.yunian.ai.domain.ConversationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * 滚动摘要的持久化状态（存于 AppMetaStore KV，schema 冻结 v41 红线下的合法持久化方式）。
 * 只存裸摘要文本，注入格式（标题/包装）由 AutoContextManager 在组装时添加。
 */
@Serializable
data class RollingSummaryState(
    val summaryText: String = "",

    /** 摘要已覆盖到（含）的消息 id，按片段一次推进到批次末尾。 */
    val coveredUpToMessageId: Long = 0L,

    /** 摘要累计已压缩覆盖的消息条数（用于注入格式的统计文案）。 */
    val coveredMessageCount: Int = 0,

    val updatedAt: Long = 0L
)

/**
 * 滚动摘要管理器：把「装不进上下文窗口」的旧对话增量合并进一份持久化摘要，
 * 后续请求只回放 [RollingSummaryState.summaryText] + 水位线之后的原文。
 *
 * 并发模型：
 * - 同一会话（storeKey）共用一把 [Mutex]，避免并发合并互相覆盖水位线；
 * - [requestIncrementalMerge] 为 fire-and-forget，[inFlight] 保证同一 key 只有一个
 *   在飞合并（重复触发直接丢弃，绝不阻塞调用方）；
 * - [mergeBlocking] 为同步版本，仅供首启回退路径复用（走同一把锁与双检逻辑）。
 *
 * 容错：AppMetaStore 全部读写以 runCatching 包裹，读失败/解码失败按「无状态」处理，
 * 写失败仅记日志（SecureLog.w）静默降级，不影响主聊天链路。
 */
class RollingSummaryManager(
    private val metaStore: AppMetaStore,
    private val mergeSummarizer: suspend (
        oldSummary: String,
        deltaText: String,
        companionNameMap: Map<Long, String>,
        memoryContext: String,
        selfName: String?
    ) -> String?,
    private val backgroundScope: CoroutineScope,
    /**
     * 增量补拉：delta 首条与水位线之间出现空洞时按会话补拉消息，保证片段连续。
     * 仅对单聊有意义（群聊消息表口径不同），群聊场景调用方传 null。
     */
    private val gapFetcher: (suspend (conversationId: Long, afterId: Long) -> List<ChatMessage>)?
) {

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun lockFor(key: String): Mutex = locks.computeIfAbsent(key) { Mutex() }

    /** 读取指定会话的滚动摘要状态；读失败/无状态返回 null（天然容错）。 */
    suspend fun loadState(scope: ConversationScope): RollingSummaryState? {
        return runCatching {
            metaStore.get(scope.storeKey(), RollingSummaryState.serializer())
        }.getOrNull()
    }

    /** 清除指定会话的滚动摘要状态（陈旧防护：会话重置后旧摘要作废）。 */
    suspend fun clear(scope: ConversationScope) {
        runCatching { metaStore.remove(scope.storeKey()) }
            .onFailure { SecureLog.w(TAG, "clear rolling summary failed: ${it.message}") }
    }

    /**
     * 异步增量合并（fire-and-forget，绝不阻塞调用方）：
     * - 同一 key 已有在飞合并则直接 return；
     * - 锁内双检水位线：[batchEndId] 已被覆盖则跳过；
     * - delta 过滤掉水位线之前的消息；与水位线之间存在空洞时用 [gapFetcher] 补拉；
     * - delta 超过单次合并上限（条数/估算 token）时先按段摘要、按序合并；
     * - 最终得到新整合摘要后推进水位线并持久化；失败静默（记日志）。
     */
    fun requestIncrementalMerge(
        scope: ConversationScope,
        delta: List<ChatMessage>,
        batchEndId: Long,
        companionNameMap: Map<Long, String> = emptyMap(),
        memoryContext: String = "",
        selfName: String? = null
    ) {
        if (delta.isEmpty() || batchEndId <= 0L) return
        val key = scope.storeKey()
        if (!inFlight.add(key)) {
            SecureLog.d(TAG, "merge already in flight, skip: $key")
            return
        }
        backgroundScope.launch {
            try {
                lockFor(key).withLock {
                    mergeLocked(scope, key, delta, batchEndId, companionNameMap, memoryContext, selfName)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.w(TAG, "incremental merge failed: ${e.message}")
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /**
     * 同步合并 + 持久化（首启回退路径复用）。
     * 与 [requestIncrementalMerge] 共用锁与双检逻辑；调用方挂起等待结果。
     *
     * @param prewrittenText 首启路径已生成的一次性摘要：非空时跳过 LLM 合并直接落库，
     *   避免同一批消息被摘要两次。
     * @return 合并后的状态；水位线已覆盖/无可合并内容/摘要失败时返回 null。
     */
    suspend fun mergeBlocking(
        scope: ConversationScope,
        delta: List<ChatMessage>,
        batchEndId: Long,
        companionNameMap: Map<Long, String> = emptyMap(),
        memoryContext: String = "",
        selfName: String? = null,
        prewrittenText: String? = null
    ): RollingSummaryState? {
        if (delta.isEmpty() || batchEndId <= 0L) return null
        val key = scope.storeKey()
        return try {
            lockFor(key).withLock {
                mergeLocked(scope, key, delta, batchEndId, companionNameMap, memoryContext, selfName, prewrittenText)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SecureLog.w(TAG, "blocking merge failed: ${e.message}")
            null
        }
    }

    /** 锁内合并主体（双检水位线 → 补拉 → 分段 → 合并 → 持久化）。 */
    private suspend fun mergeLocked(
        scope: ConversationScope,
        key: String,
        rawDelta: List<ChatMessage>,
        batchEndId: Long,
        companionNameMap: Map<Long, String>,
        memoryContext: String,
        selfName: String?,
        prewrittenText: String? = null
    ): RollingSummaryState? {
        val current = runCatching { metaStore.get(key, RollingSummaryState.serializer()) }.getOrNull()
        val watermark = current?.coveredUpToMessageId ?: 0L

        // 双检：该批次已被覆盖（在飞合并已推进水位线），跳过
        if (watermark >= batchEndId) return current

        // 过滤掉水位线之前的消息，避免重复进摘要
        val delta = rawDelta.filter { it.id > watermark }
        if (delta.isEmpty()) {
            // 无新增内容但水位线可推进（例如批次全部已被覆盖）：直接推进水位线
            val nextState = RollingSummaryState(
                summaryText = current?.summaryText.orEmpty(),
                coveredUpToMessageId = batchEndId,
                coveredMessageCount = current?.coveredMessageCount ?: 0,
                updatedAt = System.currentTimeMillis()
            )
            return persist(key, nextState).let { if (it) nextState else null }
        }

        // 补拉：首条与水位线之间存在空洞时补齐，保证片段连续（仅单聊）
        val firstId = delta.first().id
        val covered = if (firstId > watermark + 1) {
            val gapMessages = (scope as? ConversationScope.Single)
                ?.let { single -> gapFetcher?.invoke(single.companionId, watermark) }
                .orEmpty()
                .filter { it.id > watermark && it.id < firstId }
            if (gapMessages.isEmpty()) {
                delta
            } else {
                SecureLog.d(TAG, "gap filled: ${gapMessages.size} messages between $watermark and $firstId")
                (gapMessages + delta).sortedBy { it.id }
            }
        } else {
            delta
        }

        // 分段：超过单次合并上限时先按段摘要，再按序合并（段间顺序合并，累积摘要作 oldSummary）
        val segments = splitIntoSegments(covered)
        var mergedText: String? = null

        // 最后一个成功段的末尾消息 id；整批成功时以 batchEndId 为准（批次契约：
        // 一次推进到批次末尾），仅分段子集失败时钳位到实际成功位置（见下）
        var lastSuccessEndId = 0L
        var allSucceeded = false

        if (prewrittenText != null && prewrittenText.isNotBlank()) {
            mergedText = prewrittenText.trim()
            lastSuccessEndId = covered.last().id
            allSucceeded = true
        } else if (segments.size <= 1) {
            val deltaText = formatDelta(covered, companionNameMap)
            mergedText = mergeSummarizer(current?.summaryText.orEmpty(), deltaText, companionNameMap, memoryContext, selfName)
                ?.trim()?.takeIf { it.isNotBlank() }
            if (mergedText != null) {
                lastSuccessEndId = covered.last().id
                allSucceeded = true
            }
        } else {
            SecureLog.d(TAG, "delta too large (${covered.size} msgs), merging in ${segments.size} segments")
            var accumulated = current?.summaryText.orEmpty()
            val initialSummary = accumulated
            for (segment in segments) {
                val segmentText = formatDelta(segment, companionNameMap)
                val mergedSegment = mergeSummarizer(accumulated, segmentText, companionNameMap, memoryContext, selfName)
                    ?.trim()?.takeIf { it.isNotBlank() }
                if (mergedSegment == null) {
                    // 段合并失败即中断：accumulated 只包含到最后成功段为止的内容，
                    // 失败段及之后的消息既不进摘要、也不推进水位线（下次 build 会
                    // 作为 pending 重新触发合并重试），避免消息内容静默丢失。
                    break
                }
                accumulated = mergedSegment
                lastSuccessEndId = segment.last().id
            }
            // 全部段成功 → 恢复批次契约推进到 batchEndId；部分成功 → 只算实际覆盖
            allSucceeded = lastSuccessEndId == covered.last().id
            // 全部段都失败（accumulated 停留在旧摘要）→ 视为本次合并失败，状态不变
            mergedText = accumulated.takeIf { it.isNotBlank() && it != initialSummary }
        }

        if (mergedText.isNullOrBlank()) {
            SecureLog.w(TAG, "merge summarizer returned empty, keeping state unchanged: $key")
            return null
        }

        // 水位线：整批成功推进到批次末尾；分段子集失败只推进到最后成功段末尾，
        // 失败段留待下次 build 的 pending 重试
        val nextWatermark = if (allSucceeded) batchEndId else minOf(batchEndId, lastSuccessEndId)
        val nextCount = if (allSucceeded) covered.size else covered.count { it.id <= lastSuccessEndId }
        val nextState = RollingSummaryState(
            summaryText = mergedText!!,
            coveredUpToMessageId = nextWatermark,
            coveredMessageCount = (current?.coveredMessageCount ?: 0) + nextCount,
            updatedAt = System.currentTimeMillis()
        )
        return if (persist(key, nextState)) nextState else null
    }

    private suspend fun persist(key: String, state: RollingSummaryState): Boolean {
        return runCatching {
            metaStore.put(key, state, RollingSummaryState.serializer())
        }.onFailure {
            SecureLog.w(TAG, "persist rolling summary failed: ${it.message}")
        }.isSuccess
    }

    /**
     * 按单次合并上限切分片段：条数不超过 [ChatConstants.SEGMENT_MAX_MESSAGES]，
     * 估算 token 不超过 [ChatConstants.SEGMENT_MAX_TOKENS]（单条超限时独占一段）。
     */
    private fun splitIntoSegments(messages: List<ChatMessage>): List<List<ChatMessage>> {
        val segments = mutableListOf<List<ChatMessage>>()
        var current = mutableListOf<ChatMessage>()
        var currentTokens = 0
        for (message in messages) {
            val messageTokens = TokenEstimator.estimate(message.content)
            val wouldExceed = current.isNotEmpty() &&
                (current.size + 1 > ChatConstants.SEGMENT_MAX_MESSAGES ||
                    currentTokens + messageTokens > ChatConstants.SEGMENT_MAX_TOKENS)
            if (wouldExceed) {
                segments.add(current)
                current = mutableListOf()
                currentTokens = 0
            }
            current.add(message)
            currentTokens += messageTokens
        }
        if (current.isNotEmpty()) segments.add(current)
        return segments
    }

    /**
     * 把消息片段格式化为摘要输入文本：每行「发送者：内容」，换行串接。
     * 发送者优先取 [companionNameMap]（群聊成员名），单聊按 isFromUser 兜底。
     */
    private fun formatDelta(messages: List<ChatMessage>, companionNameMap: Map<Long, String>): String {
        return messages.joinToString("\n") { message ->
            val sender = when {
                message.isFromUser -> "用户"
                else -> companionNameMap[message.companionId] ?: "AI"
            }
            "$sender：${message.content}"
        }
    }

    private companion object {
        const val TAG = "RollingSummaryManager"
    }
}
