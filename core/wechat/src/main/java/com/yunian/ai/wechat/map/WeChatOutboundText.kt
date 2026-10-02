package com.yunian.ai.wechat.map

/**
 * 出站文本的规范化与「清洗后是否还有可发内容」的判定。
 *
 * ## 缺陷（原实现内联在 WeChatChatBridge 里）
 * 桥接链路把 AI 回复清洗后直接判断 finalText.length >= 1，**没有 else 分支**：
 * 一旦清洗结果为空，这一轮回复既不发送、也不留任何证据（release 包连日志都没有）。
 * 上游 [WeChatContentCleaner] 与 WeChatChatBridge.removeLocalRepetition 都可能把
 * 一段非空文本清洗成空串（整段是表情标签 / 整段被去重规则吃掉 / 只有标点），
 * 用户视角就是「AI 明明回了，微信一条都没收到」。
 *
 * 本文件只做两件事：
 * 1. [normalize] 保持原有规范化语义（与 feature 侧 normalizeOutboundText 完全一致）；
 * 2. [prepare] 把「能否发送」变成一个**显式结果**，让调用方必须处理丢弃分支。
 *
 * 这里**不会**把空文本强行发出去，也不会伪造内容——丢弃就是丢弃，只是不再静默。
 */
object WeChatOutboundText {

    /** 原实现的开头/结尾标点与方括号裁剪规则（WeChatChatBridge 内联正则）。 */
    private val LEADING_TRIM = Regex("^[\\[\\]\\s，。！？、]+")
    private val TRAILING_TRIM = Regex("[\\[\\]\\s，。！？、]+$")

    /** 规范化空白：与 feature 侧 normalizeOutboundText 等价。 */
    fun normalize(text: String): String = text.trim()
        .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")

    /**
     * 把清洗后的文本加工成可发送文本。
     *
     * @param cleanedText 已经过表情标签剥离 + 局部去重的文本
     */
    fun prepare(cleanedText: String): Prepared {
        val normalized = normalize(cleanedText)
        val stripped = normalized
            .replace(LEADING_TRIM, "")
            .replace(TRAILING_TRIM, "")
            .trim()
        return if (stripped.length >= MIN_SENDABLE_LENGTH) {
            Prepared.Sendable(stripped)
        } else {
            Prepared.Dropped(
                reason = if (normalized.isBlank()) {
                    DropReason.NO_TEXT_AFTER_CLEANING
                } else {
                    DropReason.TRIMMED_TO_EMPTY
                },
                cleanedLength = cleanedText.length,
                strippedLength = stripped.length,
            )
        }
    }

    /** 与旧实现一致：长度 >= 1 才发。 */
    const val MIN_SENDABLE_LENGTH = 1

    sealed interface Prepared {
        data class Sendable(val text: String) : Prepared

        data class Dropped(
            val reason: DropReason,
            /** 清洗后的长度（可能是 0，也可能非 0 但被标点裁剪吃光）。 */
            val cleanedLength: Int,
            /** 裁剪后剩下的长度，恒 < [MIN_SENDABLE_LENGTH]。 */
            val strippedLength: Int,
        ) : Prepared
    }

    enum class DropReason(val wireName: String) {
        /** 清洗后就没有内容了（整段是表情标签 / 整段被去重吃掉）。 */
        NO_TEXT_AFTER_CLEANING("no_text_after_cleaning"),

        /** 清洗后还有内容，但裁剪首尾标点后什么都不剩（例如整段只有标点）。 */
        TRIMMED_TO_EMPTY("trimmed_to_empty"),
    }
}
