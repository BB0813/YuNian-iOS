package com.lianyu.ai.database.cache

import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ChatGroup
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.model.ConversationSummary
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主界面会话列表 / 通讯录的进程内快照缓存。
 *
 * 冷启动时 Room Flow 首帧前会有空白 Loading；在 Application 后台预热后，
 * ViewModel 可用此快照作为 StateFlow 初始值，避免空白主界面。
 */
object HomeListCache {
    @Volatile
    private var companions: List<CompanionEntity> = emptyList()

    @Volatile
    private var groups: List<ChatGroup> = emptyList()

    @Volatile
    private var chatSummaries: List<ConversationSummary> = emptyList()

    private val warmed = AtomicBoolean(false)

    fun isWarmed(): Boolean = warmed.get()

    fun snapshotCompanions(): List<CompanionEntity> = companions

    fun snapshotGroups(): List<ChatGroup> = groups

    fun snapshotChatSummaries(): List<ConversationSummary> = chatSummaries

    fun putCompanions(list: List<CompanionEntity>) {
        companions = list
        if (list.isNotEmpty()) {
            warmed.set(true)
        }
    }

    fun putGroups(list: List<ChatGroup>) {
        groups = list
    }

    fun putChatSummaries(list: List<ConversationSummary>) {
        chatSummaries = list
    }

    /**
     * 从 Room 同步预热主列表数据。应在默认伴侣 seed 之后调用。
     */
    suspend fun warm(database: AppDatabase) {
        companions = database.companionDao().getAllCompanionsSync()
        groups = database.chatGroupDao().getAllGroupsSync()
        chatSummaries = database.conversationSummaryDao().getSummariesByTypeSync("chat")
        warmed.set(true)
    }

    fun clear() {
        companions = emptyList()
        groups = emptyList()
        chatSummaries = emptyList()
        warmed.set(false)
    }
}
