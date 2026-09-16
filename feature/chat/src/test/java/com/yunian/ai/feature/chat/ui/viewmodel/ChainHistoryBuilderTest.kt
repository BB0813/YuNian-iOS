package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.AiMessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * P0-2：气泡连发第 N 轮的模型历史组装（[ChatGenerationManager.buildChainHistory]）。
 *
 * 缺陷：连发第 2 轮起，`modelHistory` 里没有首条正文气泡（它是在首轮请求之后才送达/落库的），
 * 模型看不到第 1 条说了什么 → 复读/自相矛盾。修复后历史顺序必须为
 * modelHistory, firstBubble, alreadyGenerated₁, alreadyGenerated₂。
 */
class ChainHistoryBuilderTest {

    private fun user(text: String) = AiChatMessage(isFromUser = true, content = text, timestamp = 1L)

    private fun assistant(text: String) = AiChatMessage(isFromUser = false, content = text, timestamp = 1L)

    @Test
    fun `history 顺序 = modelHistory, firstBubble, alreadyGenerated 依次排列`() {
        val modelHistory = listOf(user("在吗"), assistant("在呢，咋啦"))
        val result = ChatGenerationManager.buildChainHistory(
            modelHistory = modelHistory,
            firstBubbleText = "第一条气泡",
            alreadyGenerated = listOf("追尾一", "追尾二"),
        )

        // 2 条原始历史 + 首条气泡 + 2 条追尾 = 5
        assertEquals(5, result.size)
        // 原始历史原样在前（同一引用，未复制改造）
        assertSame(modelHistory[0], result[0])
        assertSame(modelHistory[1], result[1])
        // 首条气泡恰好注入一次，位于原始历史之后、追尾气泡之前
        assertEquals("第一条气泡", result[2].content)
        // 追尾气泡按生成顺序排列
        assertEquals("追尾一", result[3].content)
        assertEquals("追尾二", result[4].content)
    }

    @Test
    fun `注入的首条与追尾均为 assistant 角色`() {
        val result = ChatGenerationManager.buildChainHistory(
            modelHistory = listOf(user("说三条")),
            firstBubbleText = "一",
            alreadyGenerated = listOf("二"),
        )
        assertEquals(3, result.size)
        result.drop(1).forEach { msg ->
            assertFalse(msg.isFromUser)
            assertEquals(AiMessageRole.ASSISTANT, msg.role)
        }
    }

    @Test
    fun `首条恰好注入一次 - alreadyGenerated 不含首条时不重复`() {
        // 去重约定：alreadyGenerated 只含连发循环的追加文本，首条由 firstBubbleText 注入。
        // 若调用方误把首条也塞进 alreadyGenerated，内容会出现两次——此处锁定「各一份」的期望。
        val result = ChatGenerationManager.buildChainHistory(
            modelHistory = emptyList(),
            firstBubbleText = "首条",
            alreadyGenerated = listOf("首条"),
        )
        // 契约上这是 2 条（调用方责任是不把首条放进 alreadyGenerated）；
        // 本用例锁定 helper 不做隐式去重，顺序与份数完全由入参决定。
        assertEquals(listOf("首条", "首条"), result.map { it.content })
    }

    @Test
    fun `空 alreadyGenerated 时仅追加首条`() {
        val result = ChatGenerationManager.buildChainHistory(
            modelHistory = listOf(user("在吗")),
            firstBubbleText = "首条",
            alreadyGenerated = emptyList(),
        )
        assertEquals(2, result.size)
        assertEquals("首条", result.last().content)
    }
}
