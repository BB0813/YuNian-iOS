package com.lianyu.ai.database.model

import androidx.room.Embedded
import androidx.room.Relation
import com.lianyu.ai.database.model.LorebookEntryEntity

/**
 * 世界书 + 条目聚合实体（用于 @Transaction + @Relation 查询）。
 *
 * 必须是顶层类，不能是 DAO 内部类，否则 Room/KSP 无法识别。
 */
data class LorebookWithEntries(
    @Embedded val lorebook: LorebookEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "lorebookId"
    )
    val entries: List<LorebookEntryEntity>
)