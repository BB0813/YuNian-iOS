package com.lianyu.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 微信出站队列（S1）。
 * 一条 AI 回复可拆成多段，每段一行；status 驱动重试与投递。
 */
@Entity(
    tableName = "wechat_outbox",
    indices = [
        Index(value = ["status", "nextAttemptAtMs"]),
        Index(value = ["wechatUserId", "status"]),
        Index(value = ["rootId"]),
        Index(value = ["companionId"]),
    ],
)
data class WeChatOutboxEntity(
    @PrimaryKey
    val id: String,
    val rootId: String,
    val companionId: Long,
    val wechatUserId: String,
    /** WeChatContentKind.wireType */
    val kind: Int,
    val text: String? = null,
    val mediaLocalPath: String? = null,
    val mediaFileName: String? = null,
    val mediaDescription: String? = null,
    val segmentIndex: Int,
    val segmentCount: Int,
    val sourceMessageId: Long? = null,
    /** PENDING / SENDING / SENT / FAILED / CANCELLED */
    val status: String,
    val retryCount: Int = 0,
    val nextAttemptAtMs: Long = 0L,
    val lastError: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)
