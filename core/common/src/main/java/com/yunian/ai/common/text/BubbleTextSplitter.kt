package com.yunian.ai.common.text

/**
 * 气泡文本切分器（AI 自主决策的兜底层）。
 *
 * 设计原则：**拆不拆、拆几条，由 AI 显式标记决定**。
 * 本切分器只认 AI 自己写下的「空行分段」这一显式意图，绝不按句末标点做句子级切分——
 * 因为句子级切分会把 AI 想要的「一段连贯叙述」硬拆成多条气泡，破坏真人连发观感。
 *
 * 与之配合的另外两条显式通道：
 *  - 气泡协议 JSON（[com.yunian.ai.network.bubble.BubbleJsonProtocol]）→ 由连发循环逐条生成，本层不参与；
 *  - 无空行、无协议标记的整段文本 → 原样单条送达。
 */
object BubbleTextSplitter {

    /** 单条回复最多拆分出的气泡数。超出部分拼接进最后一条，避免无限连发。 */
    const val DEFAULT_MAX_BUBBLES = 8

    /** 只认空行分段：单个 `\n` 不拆。 */
    private val BLANK_LINE = Regex("\\n\\s*\\n")

    /**
     * 只按 AI 显式空行分段；绝不做句子级切分。无空行 → 整段单条。
     *
     * @param text 待切分文本。
     * @param maxBubbles 气泡数上限；超过时保留前 `maxBubbles - 1` 条并把尾段拼接为最后一条。
     * @return 气泡列表；至少包含一个元素（空白输入返回 `listOf(text)`）。
     */
    fun splitByParagraphs(text: String, maxBubbles: Int = DEFAULT_MAX_BUBBLES): List<String> {
        if (text.isBlank()) return listOf(text)
        val blocks = text.split(BLANK_LINE).map { it.trim() }.filter { hasContent(it) }
        if (blocks.size <= 1) return listOf(text)
        if (blocks.size <= maxBubbles) return blocks
        return blocks.take(maxBubbles - 1) + listOf(blocks.drop(maxBubbles - 1).joinToString("\n"))
    }

    /**
     * 送达前切分：`allowParagraphSplit == false` 时整条不拆（用于气泡协议已逐条生成的场景，
     * 避免对单条气泡做二次拆分）。
     *
     * @param text 待切分文本。
     * @param allowParagraphSplit 是否允许按空行分段。
     * @param maxBubbles 气泡数上限。
     */
    fun splitForDelivery(
        text: String,
        allowParagraphSplit: Boolean,
        maxBubbles: Int = DEFAULT_MAX_BUBBLES,
    ): List<String> = if (allowParagraphSplit) splitByParagraphs(text, maxBubbles) else listOf(text)

    /** 段内须含至少一个字母/数字或 CJK 汉字，纯标点/空白段被丢弃。 */
    private fun hasContent(s: String): Boolean =
        s.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }
}
