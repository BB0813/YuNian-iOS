package com.lianyu.ai.uicommon.picker.domain

import com.lianyu.ai.uicommon.picker.model.AlbumInfo

/**
 * 选择器全局不可变状态。
 *
 * 由 [PickerViewModel] 通过 StateFlow 下发，
 * 不包含 PagingData（Paging 3 有独立的 Flow 管道）。
 */
data class PickerState(
    /** 相册文件夹列表 */
    val albums: List<AlbumInfo> = emptyList(),
    /** 当前选中的文件夹 ID，0 = 全部 */
    val currentBucketId: Long = 0L,
    /** 当前文件夹名称 */
    val currentAlbumName: String = "全部照片",
    /** 相册列表是否正在加载 */
    val isAlbumsLoading: Boolean = false,
    /** 错误信息 */
    val error: String? = null,
    /** 最大可选数量 */
    val maxSelection: Int = 1
)
