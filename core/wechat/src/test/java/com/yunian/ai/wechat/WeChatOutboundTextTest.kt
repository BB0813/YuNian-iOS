package com.yunian.ai.wechat

import com.yunian.ai.wechat.map.WeChatContentCleaner
import com.yunian.ai.wechat.map.WeChatOutboundText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「清洗后没有可发送内容」的判定单测。
 *
 * 对应缺陷：桥接链路（WeChatChatBridge.deliverDialogueResultInternal）把 AI 回复清洗后
 * 只写 if (finalText.length >= 1) { 发送 }，**没有 else**：清洗结果为空时既不发送、
 * 也不留任何证据（release 包连日志都没有）。
 *
 * 这些用例正是「在旧实现下为何会失败」的证据——旧实现把「能否发送」藏在发送分支里，
 * 没有可断言的丢弃结果：下面每个 Dropped 用例在旧实现里都只会走空 if，测试无从观测。
 */
class WeChatOutboundTextTest {

    @Test
    fun prepare_keepsSendableText() {
        // 首尾标点会被裁掉（沿用旧实现的内联正则），中间标点保留。
        val sendable = WeChatOutboundText.prepare("  你好，世界。  ") as WeChatOutboundText.Prepared.Sendable
        assertEquals("你好，世界", sendable.text)

        val kept = WeChatOutboundText.prepare("你好，世界。今天好冷") as WeChatOutboundText.Prepared.Sendable
        assertEquals("你好，世界。今天好冷", kept.text)
    }

    @Test
    fun prepare_reportsDropWhenCleanerStripsEverything() {
        // 整段就是表情标签：WeChatContentCleaner 会把标签剥掉、只剩空串。
        val cleaned = WeChatContentCleaner.clean("[开心]")
        assertEquals("", cleaned)

        // 旧实现：cleanText.isBlank() → isTextMeaningful=false → 一个分支都不进，
        // 静默返回，既不发送也不记录。
        val prepared = WeChatOutboundText.prepare(cleaned)
        val dropped = prepared as WeChatOutboundText.Prepared.Dropped
        assertEquals(WeChatOutboundText.DropReason.NO_TEXT_AFTER_CLEANING, dropped.reason)
        assertEquals(0, dropped.cleanedLength)
        assertEquals(0, dropped.strippedLength)
    }

    @Test
    fun prepare_reportsDropWhenOnlyUnknownTagsRemain() {
        // 桥接链路 extractStickerTags 会做一次内联清洗：去掉角色前缀、think 段、
        // 以及所有 [xxx] 标签。整段都是「未匹配到表情包的标签」时，清洗结果就是空串。
        val raw = "[角色1] [未知标签]"
        val inlineCleaned = inlineClean(raw)
        assertEquals("", inlineCleaned)

        // 旧实现：cleanText 为空 → isTextMeaningful=false → 一个分支都不进，静默返回。
        val dropped = WeChatOutboundText.prepare(inlineCleaned) as WeChatOutboundText.Prepared.Dropped
        assertEquals(WeChatOutboundText.DropReason.NO_TEXT_AFTER_CLEANING, dropped.reason)
        assertEquals(0, dropped.cleanedLength)
    }

    @Test
    fun prepare_reportsDropWhenOnlyStickerFileNameTagRemains() {
        // 第二种真实形态：整段只有一个「表情包文件名标签」，而 stickerManager 里没有该包
        // → 标签被清掉、没有匹配到任何表情包 → 文字与表情包都是空，这一轮什么都没发出去。
        val raw = "[sticker_hug_01.png]"
        val inlineCleaned = inlineClean(raw)
        assertEquals("", inlineCleaned)

        val dropped = WeChatOutboundText.prepare(inlineCleaned) as WeChatOutboundText.Prepared.Dropped
        assertEquals(WeChatOutboundText.DropReason.NO_TEXT_AFTER_CLEANING, dropped.reason)
        assertEquals(0, dropped.cleanedLength)
    }

    @Test
    fun prepare_reportsDropWhenPunctuationTrimEatsEverything() {
        // 清洗后还有内容（不 blank），但首尾标点裁剪后什么都不剩。
        val prepared = WeChatOutboundText.prepare("，，。！？")

        val dropped = prepared as WeChatOutboundText.Prepared.Dropped
        assertEquals(WeChatOutboundText.DropReason.TRIMMED_TO_EMPTY, dropped.reason)
        assertEquals(5, dropped.cleanedLength)
        assertEquals(0, dropped.strippedLength)
        assertTrue(dropped.strippedLength < WeChatOutboundText.MIN_SENDABLE_LENGTH)
    }

    @Test
    fun prepare_normalizesWhitespaceLikeLegacyHelper() {
        // 与 feature 侧 normalizeOutboundText 语义一致：段内空白折叠、保留段落边界。
        assertEquals("第一段\n\n第二段", WeChatOutboundText.normalize("  第一段  \n\n  第二段  "))
        assertEquals("你好 世界", WeChatOutboundText.normalize("  你好\t  世界  "))
    }

    /**
     * 与 WeChatChatBridge.extractStickerTags 的内联清洗部分等价的纯 JVM 复刻
     * （表情包匹配部分不在此，未匹配到的标签就是「被清掉」的路径）：
     * 去角色前缀 → 去 think 段 → 去 enc 泄漏 → 去掉标签本体与括号 → 去 sticker 文件名 → trim。
     */
    private fun inlineClean(text: String): String {
        var clean = text.replace(Regex("(?m)^\\s*\\[(?:角色\\d+|[^\\[\\]]+?)\\]\\s*"), "")
        clean = clean.replace(Regex("(?is)<think[^>]*>[\\s\\S]*?</think\\s*>"), "")
        clean = clean.replace(Regex("(?m)^enc:\\S+$"), "")
        for (match in Regex("\\[([^\\[\\]]+?)\\]").findAll(clean).toList()) {
            clean = clean.replace(match.value, "")
        }
        clean = clean.replace("]", "").replace("[", "")
        clean = clean.replace(Regex("\\bsticker_\\w+\\.png\\b", RegexOption.IGNORE_CASE), "")
        return clean.trim()
    }

    /**
     * 与 WeChatChatBridge.removeLocalRepetition 等价的纯 JVM 复刻（两段规则）：
     * 1) 相邻后缀重复（"哈哈哈哈" → "哈哈"）；
     * 2) 后缀在更早位置出现过（"好。好。" → "好。"）。
     */
    private fun removeLocalRepetition(text: String): String {
        if (text.length < 4) return text
        var result = text

        for (len in result.length / 2 downTo 2) {
            val suffix = result.takeLast(len)
            val beforeSuffix = result.dropLast(len)
            if (beforeSuffix.endsWith(suffix)) {
                result = beforeSuffix
                return removeLocalRepetition(result)
            }
        }

        for (len in result.length / 2 downTo 4) {
            val suffix = result.takeLast(len)
            val beforeSuffix = result.dropLast(len)
            if (beforeSuffix.contains(suffix)) {
                result = beforeSuffix
                return removeLocalRepetition(result)
            }
            val suffixCleaned = suffix.trimEnd('。', '！', '？', '，', '.', '!', '?', ',', ' ')
            val beforeSuffixCleaned = beforeSuffix.trimEnd('。', '！', '？', '，', '.', '!', '?', ',', ' ')
            if (suffixCleaned.length >= 4 && beforeSuffixCleaned.endsWith(suffixCleaned)) {
                result = beforeSuffix
                return removeLocalRepetition(result)
            }
        }

        return result
    }
}
