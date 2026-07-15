package com.lianyu.ai.database.cache

import android.util.LruCache
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.GroupMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

/**
 * L1 内存缓存层 — 三级存储架构的第一层。
 *
 * 设计原则：
 * - LruCache 按消息条数淘汰，防止内存膨胀
 * - 每个会话/群组独立缓存最近一页消息（解密后的明文）
 * - 写入时同步更新缓存，读取时优先命中缓存
 * - 缓存失效策略：clear / evict / 超阈值清理
 *
 * 容量计算：假设每条消息 ~2KB，500 条/会话 × 8 会话 ≈ 8MB，安全上限。
 */
object MessageCache {

    /** 单个会话缓存的最大消息条数 */
    private const val MESSAGES_PER_SESSION = 500

    /** 最大缓存会话数（LruCache 的 size 单位 = 会话） */
    private const val MAX_SESSIONS = 8

    // ── 单聊缓存 ──
    private val chatCache = LruCache<Long, SessionCache<ChatMessage>>(MAX_SESSIONS)

    // ── 群聊缓存 ──
    private val groupCache = LruCache<Long, SessionCache<GroupMessage>>(MAX_SESSIONS)

    // ── 会话摘要缓存（避免联查消息表获取最后一条消息） ──
    private val chatSummaryCache = LruCache<Long, SessionSummary>(MAX_SESSIONS * 2)
    private val groupSummaryCache = LruCache<Long, SessionSummary>(MAX_SESSIONS * 2)

    // ── 单聊 ──

    /**
     * 获取单聊缓存消息（已解密、正序）。
     * @return null 表示缓存未命中，需要从 L2 加载
     */
    fun getChatMessages(companionId: Long): List<ChatMessage>? {
        return chatCache.get(companionId)?.snapshot()
    }

    fun observeChatMessages(companionId: Long): StateFlow<List<ChatMessage>> =
        chatCache.get(companionId)?.state ?: SessionCache<ChatMessage>().also {
            chatCache.put(companionId, it)
        }.state

    /**
     * 写入单聊缓存（覆盖式）。
     * 调用时机：从 L2 加载完成、收到新消息。
     */
    fun putChatMessages(companionId: Long, messages: List<ChatMessage>) {
        chatCache.get(companionId)?.replace(messages)
            ?: chatCache.put(companionId, SessionCache(messages))
    }

    /**
     * 追加单条消息到缓存尾部。
     * 如果缓存不存在（被 LRU 淘汰），不做任何操作 — 下次加载时自然重建。
     */
    fun appendChatMessage(companionId: Long, message: ChatMessage) {
        chatCache.get(companionId)?.let { session ->
            session.append(message)
        }
    }

    /**
     * 更新缓存中某条消息的内容（编辑/撤回场景）。
     */
    fun updateChatMessage(companionId: Long, messageId: Long, transformer: (ChatMessage) -> ChatMessage) {
        chatCache.get(companionId)?.let { session ->
            session.update { if (it.id == messageId) transformer(it) else it }
        }
    }

    /**
     * 从缓存删除指定消息。
     */
    fun removeChatMessage(companionId: Long, messageId: Long) {
        chatCache.get(companionId)?.let { session ->
            session.remove { it.id == messageId }
        }
    }

    /**
     * 清除指定会话的全部缓存（删除聊天记录 / 清空历史时调用）。
     */
    fun evictChat(companionId: Long) {
        chatCache.remove(companionId)
        chatSummaryCache.remove(companionId)
    }

    // ── 群聊 ──

    fun getGroupMessages(groupId: Long): List<GroupMessage>? {
        return groupCache.get(groupId)?.snapshot()
    }

    fun observeGroupMessages(groupId: Long): StateFlow<List<GroupMessage>> =
        groupCache.get(groupId)?.state ?: SessionCache<GroupMessage>().also {
            groupCache.put(groupId, it)
        }.state

    fun putGroupMessages(groupId: Long, messages: List<GroupMessage>) {
        groupCache.get(groupId)?.replace(messages)
            ?: groupCache.put(groupId, SessionCache(messages))
    }

    fun appendGroupMessage(groupId: Long, message: GroupMessage) {
        groupCache.get(groupId)?.let { session ->
            session.append(message)
        }
    }

    fun updateGroupMessage(groupId: Long, messageId: Long, transformer: (GroupMessage) -> GroupMessage) {
        groupCache.get(groupId)?.let { session ->
            session.update { if (it.id == messageId) transformer(it) else it }
        }
    }

    fun removeGroupMessage(groupId: Long, messageId: Long) {
        groupCache.get(groupId)?.let { session ->
            session.remove { it.id == messageId }
        }
    }

    fun evictGroup(groupId: Long) {
        groupCache.remove(groupId)
        groupSummaryCache.remove(groupId)
    }

    // ── 会话摘要 ──

    /**
     * 会话摘要：缓存最后一条消息的关键信息，避免联查消息表。
     * 用于首页列表展示「最后消息预览」。
     */
    data class SessionSummary(
        val lastMessagePreview: String,
        val lastMessageTimestamp: Long,
        val lastMessageIsFromUser: Boolean,
        val unreadCount: Int = 0
    )

    fun getChatSummary(companionId: Long): SessionSummary? = chatSummaryCache.get(companionId)

    fun putChatSummary(companionId: Long, summary: SessionSummary) {
        chatSummaryCache.put(companionId, summary)
    }

    fun getGroupSummary(groupId: Long): SessionSummary? = groupSummaryCache.get(groupId)

    fun putGroupSummary(groupId: Long, summary: SessionSummary) {
        groupSummaryCache.put(groupId, summary)
    }

    // ── 全局操作 ──

    /**
     * 清除所有缓存（数据库重建 / 注销时调用）。
     */
    fun clearAll() {
        chatCache.evictAll()
        groupCache.evictAll()
        chatSummaryCache.evictAll()
        groupSummaryCache.evictAll()
    }

    /**
     * 缓存统计信息（调试用）。
     */
    fun stats(): String {
        return "chatSessions=${chatCache.size()}, groupSessions=${groupCache.size()}, " +
            "chatSummaries=${chatSummaryCache.size()}, groupSummaries=${groupSummaryCache.size()}"
    }

    /**
     * 单个会话的缓存数据结构。
     */
    private class SessionCache<T>(messages: List<T> = emptyList()) {
        private val deque = ArrayDeque<T>(MESSAGES_PER_SESSION)
        private val mutableState = MutableStateFlow<List<T>>(emptyList())
        val state: StateFlow<List<T>> = mutableState

        init {
            replace(messages)
        }

        @Synchronized
        fun snapshot(): List<T> = deque.toList()

        @Synchronized
        fun replace(messages: List<T>) {
            deque.clear()
            messages.takeLast(MESSAGES_PER_SESSION).forEach(deque::addLast)
            publish()
        }

        @Synchronized
        fun append(message: T) {
            if (deque.size == MESSAGES_PER_SESSION) deque.removeFirst()
            deque.addLast(message)
            publish()
        }

        @Synchronized
        fun update(transformer: (T) -> T) {
            val updated = deque.map(transformer)
            deque.clear()
            updated.forEach(deque::addLast)
            publish()
        }

        @Synchronized
        fun remove(predicate: (T) -> Boolean) {
            deque.removeIf(predicate)
            publish()
        }

        private fun publish() {
            mutableState.value = deque.toList()
        }
    }
}
