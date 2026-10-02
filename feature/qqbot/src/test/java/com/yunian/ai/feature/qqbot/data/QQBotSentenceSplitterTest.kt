package com.yunian.ai.feature.qqbot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ 出站分句的回归测试（纯 JVM，无 Android 依赖）。
 *
 * ## 为什么会有这个文件
 *
 * 修复前 feature:qqbot 的 5 个测试类**没有任何一个**触及分句：分句逻辑当时是
 * [QQBotChatBridge] 的 private 方法，而 `QQBotChatBridge` 需要 Android `Context`
 * 才能构造，纯 JVM 单测根本碰不到它。于是下面这个 bug 长期无人发现：
 *
 * > 分句不跳过连续分隔符，「你好！！」被切成 `["你好！", "！"]`；
 * > 第二段 trim 后是「！」，**不是 blank**，而发送循环只 `continue` 掉
 * > `isBlank()` 的片段 ⇒ **真的向用户发出一条只含「！」的 QQ 消息**。
 *
 * 分句抽到 [QQBotSentenceSplitter] 之后即可在此逐字驱动。
 *
 * ## 断言口径
 *
 * 每个用例都额外过一遍 [assertNoPunctuationOnly]：它用**测试侧独立抄写**的
 * 分隔符集合判断「片段里有没有实义字符」，**故意不复用被测实现**——
 * 否则实现改坏了，断言会跟着一起坏。
 */
class QQBotSentenceSplitterTest {

    /**
     * 分隔符集合（测试侧独立抄一份）。
     *
     * 与 [QQBotSentenceSplitter] 内部的集合逐字相同，但**刻意不共享**：
     * 这份是断言的判据，必须是独立的事实来源。
     */
    private val delimiters = charArrayOf('。', '！', '？', '!', '?', '\n')

    /** 每个片段都必须含至少一个实义字符 —— 这就是「不再发出纯标点消息」的断言。 */
    private fun assertNoPunctuationOnly(sentences: List<String>) {
        sentences.forEach { sentence ->
            assertTrue(
                "片段只含标点/空白，会变成一条只含标点的 QQ 消息：「" + sentence + "」",
                sentence.any { it !in delimiters && !it.isWhitespace() },
            )
        }
    }

    // ── 1) 本次修复的 bug：连续句末标点 ──

    @Test
    fun `连续感叹号并入前一句_不再切出只含标点的片段`() {
        val sentences = QQBotSentenceSplitter.split("你好！！")
        // 修复前这里是 ["你好！", "！"]，第二条会真的发出去。
        assertEquals(listOf("你好！！"), sentences)
        assertNoPunctuationOnly(sentences)
    }

    @Test
    fun `连续问号并入前一句`() {
        val sentences = QQBotSentenceSplitter.split("什么？？？")
        assertEquals(listOf("什么？？？"), sentences)
        assertNoPunctuationOnly(sentences)
    }

    @Test
    fun `连续感叹号三个_语气强度保留`() {
        val sentences = QQBotSentenceSplitter.split("好！！！")
        // 选「并入」而不是「只保留一个」：语气强度不丢。
        assertEquals(listOf("好！！！"), sentences)
        assertNoPunctuationOnly(sentences)
    }

    @Test
    fun `问号与感叹号混排的连续标点并成一句`() {
        val sentences = QQBotSentenceSplitter.split("真的吗？！")
        assertEquals(listOf("真的吗？！"), sentences)
        assertNoPunctuationOnly(sentences)
    }

    @Test
    fun `半角连续标点同样并入`() {
        assertEquals(listOf("hello!!"), QQBotSentenceSplitter.split("hello!!"))
        assertEquals(listOf("really??"), QQBotSentenceSplitter.split("really??"))
    }

    @Test
    fun `标点游程夹在两句之间`() {
        val sentences = QQBotSentenceSplitter.split("第一句！！第二句？？")
        assertEquals(listOf("第一句！！", "第二句？？"), sentences)
        assertNoPunctuationOnly(sentences)
    }

    // ── 2) 行首 / 整段就是标点：游程没有前一句可并入 ⇒ 丢弃 ──

    @Test
    fun `整段只含标点时返回空列表`() {
        listOf("！", "？？", "。。。", "!?!?", "？。！", "。。").forEach { input ->
            assertEquals("输入「" + input + "」不该产出任何可发片段", emptyList<String>(), QQBotSentenceSplitter.split(input))
        }
    }

    @Test
    fun `行首的标点游程被丢弃`() {
        val sentences = QQBotSentenceSplitter.split("。。你好")
        assertEquals(listOf("你好"), sentences)
        assertNoPunctuationOnly(sentences)
    }

    @Test
    fun `行首与句末的标点游程同时存在`() {
        val sentences = QQBotSentenceSplitter.split("！！你好！！")
        assertEquals(listOf("你好！！"), sentences)
        assertNoPunctuationOnly(sentences)
    }

    // ── 3) 回归：既有正常分句行为必须逐字不变 ──

    @Test
    fun `既有正常分句行为不变`() {
        assertEquals(
            listOf("你好。", "世界！"),
            QQBotSentenceSplitter.split("你好。世界！"),
        )
    }

    @Test
    fun `无分隔符的整段原样返回`() {
        assertEquals(listOf("你好呀"), QQBotSentenceSplitter.split("你好呀"))
    }

    @Test
    fun `片段首尾空白被裁剪`() {
        assertEquals(listOf("你好。"), QQBotSentenceSplitter.split("  你好。  "))
    }

    @Test
    fun `空与全空白输入返回空列表`() {
        listOf("", "   ", "\n\n", "\t").forEach { input ->
            assertEquals(emptyList<String>(), QQBotSentenceSplitter.split(input))
        }
    }

    // ── 4) 换行是消息边界：标点游程**不跨行合并** ──

    @Test
    fun `换行仍是消息边界_正常换行分段不变`() {
        // drainPending 把合并窗口内的多条入站消息用 joinToString("\n") 拼起来，
        // 所以换行在真实输入里很常见，这条回归必须有。
        assertEquals(
            listOf("你好。", "世界。"),
            QQBotSentenceSplitter.split("你好。\n\n世界。"),
        )
    }

    @Test
    fun `换行之后的孤立标点被丢弃而不是粘到上一句`() {
        // 若允许游程跨换行合并，这里会粘成一条带空行的消息；限制后得到 ["嗯？"]。
        val sentences = QQBotSentenceSplitter.split("嗯？\n\n？")
        assertEquals(listOf("嗯？"), sentences)
        assertNoPunctuationOnly(sentences)
    }

    @Test
    fun `标点游程与换行混排`() {
        val sentences = QQBotSentenceSplitter.split("你好！！\n\n什么？？")
        assertEquals(listOf("你好！！", "什么？？"), sentences)
        assertNoPunctuationOnly(sentences)
    }

    @Test
    fun `正文后换行不能吸入下一行标点_LF与CRLF`() {
        listOf("\n", "\r\n").forEach { newline ->
            val sentences = QQBotSentenceSplitter.split("你好${newline}！！世界")
            assertEquals(listOf("你好", "世界"), sentences)
            assertNoPunctuationOnly(sentences)
        }
    }

    @Test
    fun `正文后换行的分隔符_only段仍丢弃`() {
        listOf("\n", "\r\n", "\n\n", "\r\n\r\n").forEach { newline ->
            assertEquals(listOf("你好"), QQBotSentenceSplitter.split("你好${newline}！？!?。"))
            assertEquals(emptyList<String>(), QQBotSentenceSplitter.split("${newline}！？!?。"))
        }
    }

    @Test
    fun `换行后省略号保留且不吸入上一句`() {
        listOf("\n", "\r\n").forEach { newline ->
            assertEquals(listOf("你好", "……"), QQBotSentenceSplitter.split("你好${newline}！！……"))
            assertEquals(listOf("你好！！", "……"), QQBotSentenceSplitter.split("你好！！${newline}……"))
        }
    }

    // ── 5) 有意保留的边界：只含**非分隔符**标点的片段仍然保留 ──

    @Test
    fun `省略号消息被保留_这是刻意的`() {
        // '…' 不在分隔符集合里，因此「……」不是被切出来的碎片，而是一条合法消息
        // （中文聊天里表示无语 / 停顿）。丢掉它比发出去更糟。
        // 见 QQBotSentenceSplitter 的 KDoc「刻意不做的事」。
        val sentences = QQBotSentenceSplitter.split("……")
        assertEquals(listOf("……"), sentences)
    }

    // ── 6) 语料回归：任何输入都不产出只含标点的片段 ──

    @Test
    fun `语料回归_任何输入都不产出只含标点的片段`() {
        val corpus = listOf(
            "你好！！",
            "什么？？？",
            "好！！！",
            "！！",
            "！？",
            "。。",
            "。。。",
            "？",
            "!!",
            "a!!b??c",
            "！！你好！！",
            "你好！！\n\n什么？？",
            "你好。\n\n世界。",
            "第一句！！第二句？？",
            "真的吗？！好！！！",
        )
        corpus.forEach { input ->
            val sentences = QQBotSentenceSplitter.split(input)
            assertNoPunctuationOnly(sentences)
            // 发送循环只跳过 isBlank()；分句器必须自己保证不产出空白片段。
            sentences.forEach { s ->
                assertTrue("片段不该是空白：「" + s + "」", s.isNotBlank())
            }
        }
    }
}
