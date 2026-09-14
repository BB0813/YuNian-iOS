package com.yunian.ai.common.text

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleTextSplitterTest {

    @Test
    fun `无空行长文本保持单条 - 绝不按句末标点拆分`() {
        val text = "你好呀。今天过得怎么样？我这边忙了一整天呢！晚上一起吃个饭吧～"
        val result = BubbleTextSplitter.splitByParagraphs(text)
        assertEquals(1, result.size)
        assertEquals(text, result[0])
    }

    @Test
    fun `三百字无空行整段单条`() {
        val text = "嗯".repeat(150) // 300 chars, no blank line
        val result = BubbleTextSplitter.splitByParagraphs(text)
        assertEquals(1, result.size)
        assertEquals(text, result[0])
    }

    @Test
    fun `按空行分段`() {
        val text = "第一段内容\n\n第二段内容\n\n第三段内容"
        val result = BubbleTextSplitter.splitByParagraphs(text)
        assertEquals(listOf("第一段内容", "第二段内容", "第三段内容"), result)
    }

    @Test
    fun `单个换行不拆`() {
        val text = "上半句\n下半句"
        val result = BubbleTextSplitter.splitByParagraphs(text)
        assertEquals(1, result.size)
        assertEquals(text, result[0])
    }

    @Test
    fun `超过上限时拼接尾段`() {
        val max = 3
        val text = (1..5).joinToString("\n\n") { "段落$it" }
        val result = BubbleTextSplitter.splitByParagraphs(text, max)
        assertEquals(max, result.size)
        assertEquals("段落1", result[0])
        assertEquals("段落2", result[1])
        assertEquals("段落3\n段落4\n段落5", result[2])
    }

    @Test
    fun `空行分隔但含纯标点段时丢弃空段`() {
        val text = "有内容\n\n…\n\n\n\n后面的内容"
        val result = BubbleTextSplitter.splitByParagraphs(text)
        assertEquals(listOf("有内容", "后面的内容"), result)
    }

    @Test
    fun `空白输入原样返回单条`() {
        assertEquals(listOf(""), BubbleTextSplitter.splitByParagraphs(""))
        assertEquals(listOf("   "), BubbleTextSplitter.splitByParagraphs("   "))
    }

    @Test
    fun `splitForDelivery - 禁用分段时整条不拆`() {
        val text = "第一段\n\n第二段\n\n第三段"
        val result = BubbleTextSplitter.splitForDelivery(text, allowParagraphSplit = false)
        assertEquals(1, result.size)
        assertEquals(text, result[0])
    }

    @Test
    fun `splitForDelivery - 启用分段时按空行拆分`() {
        val text = "第一段\n\n第二段"
        val result = BubbleTextSplitter.splitForDelivery(text, allowParagraphSplit = true)
        assertEquals(listOf("第一段", "第二段"), result)
    }
}
