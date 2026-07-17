package com.lianyu.ai.uicommon.picker.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 选中状态管理器 — "购物车"。
 *
 * 核心要点：
 * - 使用 LinkedHashMap：key=mediaId，value=选中序号(1-based)。
 * - Linked 特性保留插入顺序，方便 UI 展示「第几张选中」的角标序号。
 * - 与 Paging 3 的数据管道完全分离，不耦合。
 */
class SelectionManager {

    private val _selectionMap = MutableStateFlow(LinkedHashMap<Long, Int>())
    val selectionMap: StateFlow<LinkedHashMap<Long, Int>> = _selectionMap.asStateFlow()

    private val _selectedIds = MutableStateFlow<List<Long>>(emptyList())
    val selectedIds: StateFlow<List<Long>> = _selectedIds.asStateFlow()

    private var maxSelection: Int = 1

    fun setMaxSelection(max: Int) {
        maxSelection = max
    }

    /** 切换选中状态。返回 true 表示操作成功，false 表示已满。 */
    fun toggle(mediaId: Long): Boolean {
        val current = _selectionMap.value
        if (current.containsKey(mediaId)) {
            // 取消选中，重新编号
            val newMap = LinkedHashMap<Long, Int>()
            var idx = 1
            for ((id, _) in current) {
                if (id != mediaId) {
                    newMap[id] = idx++
                }
            }
            _selectionMap.value = newMap
            _selectedIds.value = newMap.keys.toList()
            return true
        } else {
            // 添加选中
            if (current.size >= maxSelection) return false
            val newMap = LinkedHashMap(current)
            newMap[mediaId] = current.size + 1
            _selectionMap.value = newMap
            _selectedIds.value = newMap.keys.toList()
            return true
        }
    }

    /** 获取指定 mediaId 的选中序号，未选中返回 null */
    fun getOrder(mediaId: Long): Int? = _selectionMap.value[mediaId]

    /** 是否允许继续选中 */
    fun canSelect(): Boolean = _selectionMap.value.size < maxSelection

    /** 是否已满 */
    fun isFull(): Boolean = _selectionMap.value.size >= maxSelection

    /** 当前选中数量 */
    fun selectedCount(): Int = _selectionMap.value.size

    /** 清空选中 */
    fun clear() {
        _selectionMap.value = LinkedHashMap()
        _selectedIds.value = emptyList()
    }
}
