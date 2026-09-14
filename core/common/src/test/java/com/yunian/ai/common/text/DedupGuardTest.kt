package com.yunian.ai.common.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupGuardTest {

    @Test
    fun `归一化剥除标点空白与at并小写`() {
        assertEquals("你好world", DedupGuard.normalize("你好，World！"))
        assertEquals("abc", DedupGuard.normalize("@A B C"))
        assertEquals("今天天气不错", DedupGuard.normalize("【今天天气不错】～"))
    }

    @Test
    fun `归一化截断到四十字`() {
        val long = "字".repeat(60)
        assertEquals(40, DedupGuard.normalize(long).length)
    }

    @Test
    fun `精确相等命中重复`() {
        assertTrue(DedupGuard.isDuplicate("嗯嗯", listOf("其他", "嗯嗯")))
    }

    @Test
    fun `较短串长度至少十且被包含时命中`() {
        val short = "今天天气真的很不错呀"
        val long = short + "我们出去走走吧"
        assertTrue(DedupGuard.isDuplicate(long, listOf(short)))
        // 反向：候选较短、历史较长
        assertTrue(DedupGuard.isDuplicate(short, listOf(long)))
    }

    @Test
    fun `较短串不足十字时即使包含也不命中`() {
        // "哈哈哈哈" 长度 4 < 10，不允许作为包含命中的短串
        assertFalse(DedupGuard.isDuplicate("哈哈哈哈哈哈", listOf("哈哈哈哈")))
    }

    @Test
    fun `短肯定不会被误判为重复`() {
        assertFalse(DedupGuard.isDuplicate("嗯", listOf("好的", "行", "嗯嗯")))
    }

    @Test
    fun `空候选或空窗口不命中`() {
        assertFalse(DedupGuard.isDuplicate("", listOf("abc")))
        assertFalse(DedupGuard.isDuplicate("abc", emptyList()))
    }

    @Test
    fun `方向守卫 - 长度相等但内容不同不命中`() {
        assertFalse(DedupGuard.isDuplicate("今天天气很不错", listOf("今天心情还不错呀")))
    }
}
