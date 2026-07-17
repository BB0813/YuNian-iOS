package com.lianyu.ai.uicommon.picker.model

import android.net.Uri

/**
 * 单张媒体图片项。
 *
 * **关键约束**：此类不包含 `isSelected` 字段。
 * 选中状态由 [SelectionManager] 独立管理，通过 `id` 关联查询。
 * 这是 Paging 3 + Compose 状态分离的核心红线。
 */
data class MediaItem(
    /** 唯一标识，对应 MediaStore.Images.Media._ID */
    val id: Long,
    /** 内容 URI */
    val uri: Uri,
    /** 添加时间戳（毫秒），用于排序和 getRefreshKey */
    val dateAdded: Long,
    /** 所属相册 BUCKET_ID，用于文件夹筛选 */
    val bucketId: Long,
    /** 文件名 */
    val displayName: String
)
