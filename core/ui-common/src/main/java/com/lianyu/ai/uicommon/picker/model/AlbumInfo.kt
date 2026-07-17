package com.lianyu.ai.uicommon.picker.model

import android.net.Uri

/**
 * 相册文件夹信息。
 * 由 [AlbumRepository] 通过 BUCKET_ID DISTINCT 查询聚合得到。
 */
data class AlbumInfo(
    /** 相册 ID，对应 MediaStore.Images.Media.BUCKET_ID */
    val bucketId: Long,
    /** 相册显示名称，对应 BUCKET_DISPLAY_NAME */
    val displayName: String,
    /** 该相册包含的图片总数 */
    val count: Int,
    /** 封面图 URI，取该相册最新的那张 */
    val coverUri: Uri?
)
