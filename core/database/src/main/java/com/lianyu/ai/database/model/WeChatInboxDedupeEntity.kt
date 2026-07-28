package com.lianyu.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 微信入站去重账本（S2）。
 * [dedupeKey] 优先 mid:{messageId}，否则内容哈希。
 */
@Entity(
    tableName = "wechat_inbox_dedupe",
    indices = [
        Index(value = ["processedAtMs"]),
        Index(value = ["fromUserId"]),
    ],
)
data class WeChatInboxDedupeEntity(
    @PrimaryKey
    val dedupeKey: String,
    val messageId: Long? = null,
    val fromUserId: String,
    val processedAtMs: Long = System.currentTimeMillis(),
)
