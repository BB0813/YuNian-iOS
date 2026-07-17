package com.lianyu.ai.uicommon.picker.ui

import android.content.ContentResolver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.lianyu.ai.uicommon.picker.data.AlbumRepository
import com.lianyu.ai.uicommon.picker.data.MediaPagingSource
import com.lianyu.ai.uicommon.picker.domain.PickerState
import com.lianyu.ai.uicommon.picker.domain.SelectionManager
import com.lianyu.ai.uicommon.picker.model.AlbumInfo
import com.lianyu.ai.uicommon.picker.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * 图片选择器唯一 ViewModel。
 *
 * 职责：
 * - 管理 Paging 3 数据管道（PagingData → cachedIn(viewModelScope)）
 * - 管理 SelectionManager 选中态
 * - 管理 AlbumRepository 文件夹列表
 * - 暴露 PickerState 给 UI
 *
 * Paging 3 与选中态完全分离：PagingData 只负责只读的图片列表，
 * 选中态由 SelectionManager 独立维护。
 */
class PickerViewModel(
    private val contentResolver: ContentResolver
) : ViewModel() {

    // ---------- 选中管理 ----------
    private val _selectionManager = SelectionManager()
    val selectionMap: StateFlow<LinkedHashMap<Long, Int>> = _selectionManager.selectionMap

    // ---------- 状态 ----------
    private val _state = MutableStateFlow(PickerState())
    val state: StateFlow<PickerState> = _state.asStateFlow()

    // ---------- 文件夹仓库 ----------
    private val albumRepository = AlbumRepository(contentResolver)

    // ---------- Paging 3 数据管道 ----------
    private val _pagingFlow = MutableStateFlow<Flow<PagingData<MediaItem>>>(emptyFlow())
    val pagingFlow: StateFlow<Flow<PagingData<MediaItem>>> = _pagingFlow.asStateFlow()

    init {
        loadAlbums()
        switchAlbum(0L, "全部照片")
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
        _state.value = _state.value.copy(
            currentBucketId = bucketId,
            currentAlbumName = displayName
        )
        _pagingFlow.value = Pager(
            config = PagingConfig(
                pageSize = 60,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                MediaPagingSource(contentResolver, bucketId)
            }
        ).flow.cachedIn(viewModelScope)
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
