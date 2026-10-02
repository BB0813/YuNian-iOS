package com.yunian.ai.wechat

import com.yunian.ai.wechat.map.WeChatContentCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatContentCleanerTest {

    @Test
    fun isStickerContent_matchesBracketOnly() {
        assertTrue(WeChatContentCleaner.isStickerContent("[开心]"))
        assertFalse(WeChatContentCleaner.isStickerContent("你好[开心]"))
    }

    @Test
    fun clean_stripsThinkAndRolePrefix() {
        val raw = "[角色1] <think>secret</think>你好呀。"
        val cleaned = WeChatContentCleaner.clean(raw)
        assertEquals("你好呀。", cleaned)
    }

    @Test
    fun clean_stripsUnclosedThink() {
        val raw = "你好<think>未闭合思考一直到结尾"
        val cleaned = WeChatContentCleaner.clean(raw)
        assertEquals("你好", cleaned)
        assertFalse(cleaned.contains("未闭合"))
    }

    @Test
    fun clean_dedupesRepeatedSentence() {
        val raw = "打算晚上吃什么呀。打算晚上吃什么呀。"
        val cleaned = WeChatContentCleaner.clean(raw)
        assertEquals("打算晚上吃什么呀。", cleaned)
    }

    @Test
    fun clean_canReturnEmptyString_forStickerOnlyReply() {
        // 上游确实会把一段「非空」回复清洗成空串——这正是 G6 静默丢弃的输入源。
        assertEquals("", WeChatContentCleaner.clean("[开心]"))
        assertEquals("", WeChatContentCleaner.clean("[角色1] [开心][难过]"))
    }

    @Test
    fun clean_pureRepetitionWithoutSentenceBoundarySurvivesCleaner() {
        // 记录真实行为：clean 的去重按句号/问号/叹号切句，没有句读的叠词原样留下。
        // 它随后会在桥接链路的 removeLocalRepetition 里被吃成空串——G6 的第二条输入源。
        assertEquals("哈哈哈哈哈哈", WeChatContentCleaner.clean("哈哈哈哈哈哈"))
    }
}
