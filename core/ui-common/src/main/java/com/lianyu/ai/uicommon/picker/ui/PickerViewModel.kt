package com.lianyu.ai.uicommon.picker.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.uicommon.picker.data.AlbumRepository
import com.lianyu.ai.uicommon.picker.domain.PickerState
import com.lianyu.ai.uicommon.picker.domain.SelectionManager
import com.lianyu.ai.uicommon.picker.model.AlbumInfo
import com.lianyu.ai.uicommon.picker.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图片选择器唯一 ViewModel。
 *
 * 职责：
 * - 一次性查出所有 URI 放入内存，依赖 LazyGrid 自身回收机制渲染
 * - 管理 SelectionManager 选中态
 * - 管理 AlbumRepository 文件夹列表
 * - 暴露 PickerState 给 UI
 *
 * 纯内存分页：数据列表与选中态完全分离。
 */
class PickerViewModel(
    private val contentResolver: ContentResolver
) : ViewModel() {

    companion object {
        private val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        private const val ID_COL = 0
        private const val DATE_ADDED_COL = 1
        private const val BUCKET_ID_COL = 2
        private const val DISPLAY_NAME_COL = 3
    }

    // ---------- 选中管理 ----------
    private val _selectionManager = SelectionManager()
    val selectionMap: StateFlow<LinkedHashMap<Long, Int>> = _selectionManager.selectionMap

    // ---------- 状态 ----------
    private val _state = MutableStateFlow(PickerState())
    val state: StateFlow<PickerState> = _state.asStateFlow()

    // ---------- 文件夹仓库 ----------
    private val albumRepository = AlbumRepository(contentResolver)

    // ---------- 纯内存图片列表 ----------
    private val _mediaList = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaList: StateFlow<List<MediaItem>> = _mediaList.asStateFlow()

    // 不在 init 中加载：首次打开时权限可能尚未授予，
    // 由 CustomImagePicker 在权限就绪后调用 reload()。

    /** 权限就绪后重新加载相册与当前文件夹图片 */
    fun reload() {
        loadAlbums()
        val bucketId = _state.value.currentBucketId
        val name = _state.value.currentAlbumName.ifBlank { "全部照片" }
        switchAlbum(bucketId, name)
    }

    // ---------- 文件夹 ----------

    private fun loadAlbums() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isAlbumsLoading = true, error = null)
            try {
                val albums = albumRepository.loadAlbums()
                _state.value = _state.value.copy(
                    albums = albums,
                    isAlbumsLoading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isAlbumsLoading = false,
                    error = "加载相册失败: ${e.message}"
                )
            }
        }
    }

    fun switchAlbum(bucketId: Long, displayName: String) {
        // 先清空旧列表，避免切换瞬间仍显示上一相册内容，造成“读错文件夹”的错觉
        _mediaList.value = emptyList()
        _state.value = _state.value.copy(
            currentBucketId = bucketId,
            currentAlbumName = displayName,
            isMediaLoading = true,
            error = null
        )
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    queryAllMedia(bucketId)
                }
                // 若用户已再次切换，丢弃过期结果
                if (_state.value.currentBucketId != bucketId) return@launch
                _mediaList.value = items
                _state.value = _state.value.copy(isMediaLoading = false)
            } catch (e: Exception) {
                if (_state.value.currentBucketId != bucketId) return@launch
                _state.value = _state.value.copy(
                    isMediaLoading = false,
                    error = "加载图片失败: ${e.message}"
                )
            }
        }
    }

    private fun queryAllMedia(bucketId: Long): List<MediaItem> {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        // 0L 仅表示「全部照片」。MediaStore 的 BUCKET_ID 是路径哈希，常为负数，
        // 绝不能用 bucketId > 0，否则负 ID 相册会被当成全部照片。
        val (selection, selectionArgs) = if (bucketId != 0L) {
            "${MediaStore.Images.Media.BUCKET_ID} = ?" to arrayOf(bucketId.toString())
        } else {
            null to null
        }
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        return contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            ?.use { cursor ->
                val list = mutableListOf<MediaItem>()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(ID_COL)
                    list.add(
                        MediaItem(
                            id = id,
                            uri = Uri.parse("${MediaStore.Images.Media.EXTERNAL_CONTENT_URI}/$id"),
                            dateAdded = cursor.getLong(DATE_ADDED_COL),
                            bucketId = cursor.getLong(BUCKET_ID_COL),
                            displayName = cursor.getString(DISPLAY_NAME_COL) ?: ""
                        )
                    )
                }
                list
            } ?: emptyList()
    }

    fun setMaxSelection(max: Int) {
        _selectionManager.setMaxSelection(max)
        _state.value = _state.value.copy(maxSelection = max)
    }

    // ---------- 选中操作 ----------

    fun toggleSelection(mediaId: Long): Boolean {
        return _selectionManager.toggle(mediaId)
    }

    fun getSelectionOrder(mediaId: Long): Int? {
        return _selectionManager.getOrder(mediaId)
    }

    fun selectedIds(): List<Long> {
        return _selectionManager.selectedIds.value
    }

    fun clearSelection() {
        _selectionManager.clear()
    }
}
