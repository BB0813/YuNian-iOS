package com.lianyu.ai.uicommon.picker.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lianyu.ai.uicommon.picker.model.MediaItem

/**
 * Paging 3 数据源 — 按页查询系统相册图片。
 *
 * 核心要点：
 * - 使用 `cursor.use {}` 自动关闭 Cursor，防止 CursorWindow 泄漏。
 * - 按 dateAdded DESC 排序（最新在前）。
 * - 支持按 bucketId 筛选文件夹（0 = 全部）。
 */
internal class MediaPagingSource(
    private val contentResolver: ContentResolver,
    private val bucketId: Long = 0L
) : PagingSource<Int, MediaItem>() {

    companion object {
        private val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        private const val ID_COL = 0
        private const val DATA_COL = 1
        private const val DATE_ADDED_COL = 2
        private const val BUCKET_ID_COL = 3
        private const val DISPLAY_NAME_COL = 4
    }

    override fun getRefreshKey(state: PagingState<Int, MediaItem>): Int? {
        // 取 anchorPosition 对应页的 dateAdded 作为 refresh key
        return state.anchorPosition?.let { anchor ->
            state.closestItemToPosition(anchor)?.dateAdded?.toInt()
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaItem> {
        return try {
            val offset = params.key ?: 0
            val limit = params.loadSize

            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val selection = buildSelection()
            val selectionArgs = buildSelectionArgs()
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            val cursor = contentResolver.query(
                uri, projection, selection, selectionArgs, sortOrder
            )

            // use 块确保 Cursor 自动关闭，防止 CursorWindow 泄漏
            val items = cursor.use { c ->
                val list = mutableListOf<MediaItem>()
                if (c == null) return@use list

                // 定位到 offset 前一行，确保 moveToNext() 从 offset 开始
                if (offset > 0) {
                    val target = (offset - 1).coerceAtMost(c.count - 1)
                    c.moveToPosition(target)
                } else {
                    c.moveToPosition(-1)
                }

                var count = 0
                while (c.moveToNext() && count < limit) {
                    list.add(
                        MediaItem(
                            id = c.getLong(ID_COL),
                            uri = Uri.parse("${MediaStore.Images.Media.EXTERNAL_CONTENT_URI}/${c.getLong(ID_COL)}"),
                            dateAdded = c.getLong(DATE_ADDED_COL),
                            bucketId = c.getLong(BUCKET_ID_COL),
                            displayName = c.getString(DISPLAY_NAME_COL) ?: ""
                        )
                    )
                    count++
                }
                list
            }

            val nextKey = if (items.size < limit) null else offset + limit
            val prevKey = if (offset == 0) null else (offset - limit).coerceAtLeast(0)

            LoadResult.Page(
                data = items,
                prevKey = prevKey,
                nextKey = nextKey
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private fun buildSelection(): String? {
        return if (bucketId > 0) {
            "${MediaStore.Images.Media.BUCKET_ID} = ?"
        } else null
    }

    private fun buildSelectionArgs(): Array<String>? {
        return if (bucketId > 0) {
            arrayOf(bucketId.toString())
        } else null
    }
}
