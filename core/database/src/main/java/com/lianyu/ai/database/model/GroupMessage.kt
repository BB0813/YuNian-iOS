package com.lianyu.ai.database.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("E1")
data class GroupMessage(
    val id: Long = 0,
    val groupId: Long,
    val companionId: Long,
    /** Encrypted-at-rest message body. Use GroupMessageRepository for decrypted reads. */
    val content: String,
    /** Millisecond timestamp used as cursor for paged reads. */
    val timestamp: Long = System.currentTimeMillis(),
    /** Queryable plaintext index for fuzzy search. */
    val searchContent: String = content,
    /** Queryable file category. */
    val fileFormat: FileFormat = FileFormat.TEXT,
    /** Encrypted-at-rest link string for one or more files/resources. */
    val linkString: String = ""
)
