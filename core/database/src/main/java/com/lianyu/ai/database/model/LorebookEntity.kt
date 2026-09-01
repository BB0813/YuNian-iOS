package com.lianyu.ai.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 世界书/知识书实体。
 *
 * 一个世界书包含多个条目，每个条目可通过关键词触发注入到对话上下文中。
 * 支持全局（companionId=null）或绑定特定伴侣。
 */
@Entity(
    tableName = "lorebooks",
    indices = [
        Index(value = ["companionId"]),
        Index(value = ["enabled"])
    ]
)
@Serializable
data class LorebookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    @ColumnInfo(defaultValue = "")
    val description: String = "",

    // 绑定的伴侣 ID，null 表示全局可用
    val companionId: Long? = null,

    @ColumnInfo(defaultValue = "1")
    val enabled: Int = 1,

    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = 0,

    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0,
) {
    fun isEnabled(): Boolean = enabled == 1
}