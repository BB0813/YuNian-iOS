package com.yunian.ai.feature.chat.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P1-4：跨轮滚动查重窗口（[ChatTurnState.recentDedupWindow]）。
 *
 * 缺陷：旧实现每轮只用「最近 3 条历史 AI 消息」做查重窗口，窗口随轮次滚动后，
 * 上几轮刚发过的内容掉出窗口 → 跨轮复读。修复后已落实气泡的归一化内容进入
 * 容量 ≤ [RECENT_DEDUP_WINDOW_CAP] 的滚动窗口，且 reset 不清空。
 */
class ChatTurnStateRecentDedupTest {

    @Test
    fun `reset 清本轮缓存但不清跨轮窗口`() {
        val state = ChatTurnState()
        state.pushRecentDedup("上一轮发过的内容")
        state.dedupTurnKey = "turn-1"
        state.dedupWindow = mutableListOf("本轮缓存")

        state.reset()

        // 本轮缓存被清
        assertNull(state.dedupTurnKey)
        assertNull(state.dedupWindow)
        // 跨轮窗口保留——这正是它的价值所在
        assertEquals(listOf("上一轮发过的内容"), state.recentDedupWindow.toList())
    }

    @Test
    fun `容量滚动 - 超过上限时从队首滚出`() {
        val state = ChatTurnState()
        val overflow = 3
        repeat(RECENT_DEDUP_WINDOW_CAP + overflow) { state.pushRecentDedup("msg$it") }

        assertEquals(RECENT_DEDUP_WINDOW_CAP, state.recentDedupWindow.size)
        // 最早的 overflow 条被滚出，窗口里是最新的 RECENT_DEDUP_WINDOW_CAP 条
        assertEquals("msg$overflow", state.recentDedupWindow.first())
        assertEquals("msg${RECENT_DEDUP_WINDOW_CAP + overflow - 1}", state.recentDedupWindow.last())
    }

    @Test
    fun `空归一化内容不入窗`() {
        val state = ChatTurnState()
        state.pushRecentDedup("")
        assertEquals(0, state.recentDedupWindow.size)
    }
}
