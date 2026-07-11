package com.lianyu.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lianyu.ai.database.model.ConversationSummary
import kotlinx.coroutines.flow.Flow

/**
 * 会话摘要 DAO — 三级存储架构 L2 层。
 *
 * 负责会话最后一条消息摘要的增删改查，避免联查消息表。
 */
@Dao
interface ConversationSummaryDao {

    @Query("SELECT * FROM conversation_summary WHERE sessionType = :sessionType ORDER BY isPinned DESC, lastMessageTimestamp DESC")
    fun getSummariesByType(sessionType: String): Flow<List<ConversationSummary>>

    @Query("SELECT * FROM conversation_summary WHERE sessionId = :sessionId AND sessionType = :sessionType")
    fun getSummary(sessionId: Long, sessionType: String): Flow<ConversationSummary?>

    @Query("SELECT * FROM conversation_summary WHERE sessionId = :sessionId AND sessionType = :sessionType")
    suspend fun getSummarySync(sessionId: Long, sessionType: String): ConversationSummary?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(summary: ConversationSummary)

    @Query("UPDATE conversation_summary SET lastMessagePreview = :preview, lastMessageTimestamp = :timestamp, lastMessageIsFromUser = :isFromUser WHERE sessionId = :sessionId AND sessionType = :sessionType")
    suspend fun updateLastMessage(
        sessionId: Long,
        sessionType: String,
        preview: String,
        timestamp: Long,
        isFromUser: Boolean
    )

    @Query("UPDATE conversation_summary SET unreadCount = unreadCount + 1 WHERE sessionId = :sessionId AND sessionType = :sessionType")
    suspend fun incrementUnread(sessionId: Long, sessionType: String)

    @Query("UPDATE conversation_summary SET unreadCount = 0 WHERE sessionId = :sessionId AND sessionType = :sessionType")
    suspend fun clearUnread(sessionId: Long, sessionType: String)

    @Query("UPDATE conversation_summary SET isPinned = :pinned WHERE sessionId = :sessionId AND sessionType = :sessionType")
    suspend fun setPinned(sessionId: Long, sessionType: String, pinned: Boolean)

    @Query("UPDATE conversation_summary SET isMuted = :muted WHERE sessionId = :sessionId AND sessionType = :sessionType")
    suspend fun setMuted(sessionId: Long, sessionType: String, muted: Boolean)

    @Query("DELETE FROM conversation_summary WHERE sessionId = :sessionId AND sessionType = :sessionType")
    suspend fun deleteSummary(sessionId: Long, sessionType: String)
}
