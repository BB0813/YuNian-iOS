package com.lianyu.ai.network.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡连发 JSON 协议解析测试。
 *
 * 覆盖：严格 JSON、```json 包裹、宽松提取、空文本兜底、非 JSON 降级、continue 语义。
 */
class BubbleJsonProtocolTest {

    @Test
    fun `parse - 严格 JSON`() {
        val reply = BubbleJsonProtocol.parse("""{"text":"好啊，一起吧！","continue":true}""")
        assertNotNull(reply)
        assertEquals("好啊，一起吧！", reply!!.text)
        assertTrue(reply.continueChat)
    }

    @Test
    fun `parse - continue false`() {
        val reply = BubbleJsonProtocol.parse("""{"text":"就说到这里。","continue":false}""")
        assertNotNull(reply)
        assertFalse(reply!!.continueChat)
    }

    @Test
    fun `parse - 代码块包裹`() {
        val raw = """
            ```json
            {"text":"第一条","continue":true}
            ```
        """.trimIndent()
        val reply = BubbleJsonProtocol.parse(raw)
        assertNotNull(reply)
        assertEquals("第一条", reply!!.text)
        assertTrue(reply.continueChat)
    }

    @Test
    fun `parse - 前后杂文包裹仍提取 JSON 对象`() {
        val raw = "好的，我看看。\n{\"text\":\"被提取的文本\",\"continue\":false}\n以上。"
        val reply = BubbleJsonProtocol.parse(raw)
        assertNotNull(reply)
        assertEquals("被提取的文本", reply!!.text)
        assertFalse(reply.continueChat)
    }

    @Test
    fun `parse - 空 text 返回 null`() {
        val reply = BubbleJsonProtocol.parse("""{"text":"","continue":true}""")
        assertNull(reply)
    }

    @Test
    fun `parse - 空白 text 返回 null`() {
        val reply = BubbleJsonProtocol.parse("""{"text":"   ","continue":true}""")
        assertNull(reply)
    }

    @Test
    fun `parse - 无 continue 字段默认为不继续`() {
        val reply = BubbleJsonProtocol.parse("""{"text":"只是陈述。"}""")
        assertNotNull(reply)
        assertFalse(reply!!.continueChat)
    }

    @Test
    fun `parse - 非法 JSON 降级为单条文本`() {
        val reply = BubbleJsonProtocol.parse("这是一条普通的自然语言回复，没有 JSON。")
        assertNotNull(reply)
        assertEquals("这是一条普通的自然语言回复，没有 JSON。", reply!!.text)
        assertFalse(reply.continueChat)
    }

    @Test
    fun `parseStrict - 非法 JSON 返回 null 触发重试`() {
        // 宽容 parse 会降级为单条文本；严格 parse 必须返回 null（格式漂移 → 重试）
        assertNotNull(BubbleJsonProtocol.parse("这是一条普通的自然语言回复，没有 JSON。"))
        assertNull(BubbleJsonProtocol.parseStrict("这是一条普通的自然语言回复，没有 JSON。"))
    }

    @Test
    fun `parseStrict - 有效 JSON 正常解析`() {
        val reply = BubbleJsonProtocol.parseStrict("""{"text":"严格模式正常","continue":true}""")
        assertNotNull(reply)
        assertEquals("严格模式正常", reply!!.text)
        assertTrue(reply.continueChat)
    }

    @Test
    fun `parse - 纯空白降级 null`() {
        assertNull(BubbleJsonProtocol.parse("   \n\t  "))
    }

    @Test
    fun `parse - 超长非 JSON 返回 null 触发重试`() {
        val long = "长".repeat(5000)
        val reply = BubbleJsonProtocol.parse(long)
        assertNull(reply)
    }
}
