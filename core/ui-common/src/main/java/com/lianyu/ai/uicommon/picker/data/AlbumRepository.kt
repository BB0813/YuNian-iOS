package com.lianyu.ai.uicommon.picker.data

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.lianyu.ai.uicommon.picker.model.AlbumInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 相册文件夹聚合仓库 — 单次全表扫描，零 N+1 查询。
 *
 * 核心要点：
 * - 一次 ContentResolver.query 扫描所有图片行，在内存中按键聚合，
 *   避免 1+2N 次 SQL 查询导致的严重卡顿。
 * - 每个相册跟踪计数和最新图片的 mediaId（通过比较 DATE_ADDED），
 *   无需额外 SQL。
 */
internal class AlbumRepository(
    private val contentResolver: ContentResolver
) {

    /** 单次扫描所需四列 */
    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.DATE_ADDED
    )

    suspend fun loadAlbums(): List<AlbumInfo> = withContext(Dispatchers.IO) {
        val bucketMap = LinkedHashMap<Long, BucketAcc>()

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null, null, null
        )?.use { cursor ->
            val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val bucketId = cursor.getLong(bucketCol)
                val mediaId  = cursor.getLong(idCol)
                val date     = cursor.getLong(dateCol)

                val acc = bucketMap[bucketId]
                if (acc == null) {
                    bucketMap[bucketId] = BucketAcc(
                        name    = cursor.getString(nameCol) ?: "未知相册",
                        count   = 1,
                        latestDate = date,
                        coverId = mediaId
                    )
                } else {
                    acc.count++
                    if (date > acc.latestDate) {
                        acc.latestDate = date
                        acc.coverId = mediaId
                    }
                }
            }
        }

        // 构建结果：全部照片 + 各相册
        var totalCount = 0
        var allCoverId: Long? = null
        var allLatestDate = Long.MIN_VALUE

        val albums = mutableListOf<AlbumInfo>()

        for ((bucketId, acc) in bucketMap) {
            totalCount += acc.count
            if (acc.latestDate > allLatestDate) {
                allLatestDate = acc.latestDate
                allCoverId = acc.coverId
            }
            albums.add(
                AlbumInfo(
                    bucketId    = bucketId,
                    displayName = acc.name,
                    count       = acc.count,
                    coverUri    = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, acc.coverId
                    )
                )
            )
        }

        // 按数量降序排序（全部照片放最前）
        albums.sortByDescending { it.count }

        // 全部照片插在最前
        albums.add(0, AlbumInfo(
            bucketId    = 0L,
            displayName = "全部照片",
            count       = totalCount,
            coverUri    = allCoverId?.let {
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it)
            }
        ))

        albums
    }
}

/** 每个相册的累加器 */
private class BucketAcc(
    var name: String,
    var count: Int,
    var latestDate: Long,
    var coverId: Long
)
