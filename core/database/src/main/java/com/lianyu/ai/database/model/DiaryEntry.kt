package com.lianyu.ai.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 日记实体 — 每篇日记绑定到一个虚拟恋人，记录用户与该角色的互动日记。
 *
 * mood: 1=开心 2=平静 3=难过 4=生气 5=感动 6=思念
 */
@Entity(
    tableName = "diary_entries",
    indices = [
        Index(value = ["companionId", "deviceId"]),
        Index(value = ["date"]),
        Index(value = ["deviceId"])
    ]
)
@Serializable
@SerialName("E7")
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val companionId: Long,
    val title: String = "",
    val content: String,
    val mood: Int = 2,          // 默认平静
    val date: Long = System.currentTimeMillis(),
    val weather: String = "",   // 可选：天气
    val tags: String = "",      // 逗号分隔的标签
    @ColumnInfo(defaultValue = "''")
    val deviceId: String = ""
)
