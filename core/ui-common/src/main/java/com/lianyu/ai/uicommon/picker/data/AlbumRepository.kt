package com.lianyu.ai.uicommon.picker.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import com.lianyu.ai.uicommon.picker.model.AlbumInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 相册文件夹聚合仓库。
 *
 * 核心要点：
 * - 通过 ContentResolver SQL 层的 GROUP BY BUCKET_ID 聚合，
 *   不在内存中遍历全量数据，避免 10000+ 图片时的 UI 卡顿。
 * - 每个相册取最新一张图片作为封面。
 */
internal class AlbumRepository(
    private val contentResolver: ContentResolver
) {

    suspend fun loadAlbums(): List<AlbumInfo> = withContext(Dispatchers.IO) {
        val albums = mutableListOf<AlbumInfo>()

        // 先加「全部照片」虚拟相册
        val allCover = queryLatestImageUri(null)
        albums.add(
            AlbumInfo(
                bucketId = 0L,
                displayName = "全部照片",
                count = queryCount(null),
                coverUri = allCover
            )
        )

        // 按 BUCKET_ID 聚合 — 先取所有不重复的 bucket，
        // 再分别查询各 bucket 的图片数量和封面
        val bucketProjection = arrayOf(
            "DISTINCT ${MediaStore.Images.Media.BUCKET_ID}",
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            bucketProjection,
            null, null,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val bucketId = cursor.getLong(idCol)
                val displayName = cursor.getString(nameCol) ?: "未知相册"
                val count = queryCount(bucketId)
                val cover = queryLatestImageUri(bucketId)

                albums.add(
                    AlbumInfo(
                        bucketId = bucketId,
                        displayName = displayName,
                        count = count,
                        coverUri = cover
                    )
                )
            }
        }

        albums
    }

    private fun queryCount(bucketId: Long?): Int {
        val selection = bucketId?.let { "${MediaStore.Images.Media.BUCKET_ID} = ?" }
        val args = bucketId?.let { arrayOf(it.toString()) }
        val projection = arrayOf("COUNT(*) AS _count")

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection, args, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(cursor.getColumnIndexOrThrow("_count"))
            }
        }
        return 0
    }

    private fun queryLatestImageUri(bucketId: Long?): Uri? {
        val selection = bucketId?.let { "${MediaStore.Images.Media.BUCKET_ID} = ?" }
        val args = bucketId?.let { arrayOf(it.toString()) }
        val projection = arrayOf(MediaStore.Images.Media._ID)

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 1"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return Uri.parse("${MediaStore.Images.Media.EXTERNAL_CONTENT_URI}/$id")
            }
        }
        return null
    }
}
