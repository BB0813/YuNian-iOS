package com.yunian.ai.network

import com.yunian.ai.network.bubble.BubbleJsonProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QA 独立验证：气泡协议模式下，畸形 / 截断 JSON 经 [AiService.streamMessage] 的真实后处理路径
 * 后，[BubbleJsonProtocol.extractTextLenient] 是否仍能正确抠出纯文本、不把 JSON 骨架展示给用户。
 *
 * 缺陷（P1）：a5a7d6b 在 [AiService.streamMessage]（约 2594 行）计算了
 * `bubbleMode = BubbleJsonProtocol.isProtocolEnabled(systemPrompt)`，
 * 但其后的后处理调用（约 2613 行）`applyPersonaPostProcessing(raw, sortedHistory)` **未透传**
 * `preserveRaw = bubbleMode`，该变量成为未使用值。结果：`preserveRaw` 恒为 false，
 * 畸形 JSON 先被 `extractDirectReply` 的引号抽取逻辑打碎成「text / 正文 / continue」多行，
 * 再交给 `extractTextLenient`，导致 2eb0556 的兜底失效、JSON 骨架仍会泄漏给用户。
 *
 * 修复：AiService.kt 后处理调用处补 `preserveRaw = bubbleMode`（一行）。
 * 修复前本类第 1 个用例为红（bug 证据）；修复后应全绿。
 */
class QaBubblePreserveRawTest {

    /** 模型流被截断的典型残片（2eb0556 声称要清洗掉、绝不展示给用户的形态）。 */
    private val truncatedJson = """{"text":"你好呀今天过得怎么样","continue":"""

    @Test
    fun `生产路径 - 截断 JSON 经 streamMessage 后处理仍应提取出纯文本`() {
        // 与 AiService.streamMessage 第 2613 行完全一致的调用签名（不传 preserveRaw）。
        val postProcessed = AiPromptBuilder.applyPersonaPostProcessing(truncatedJson, emptyList())
        val delivered = BubbleJsonProtocol.extractTextLenient(postProcessed)

        assertEquals(
            "截断 JSON 经生产后处理路径后，送达文本应为纯正文，不得含 JSON 骨架",
            "你好呀今天过得怎么样",
            delivered,
        )
    }

    @Test
    fun `preserveRaw=true - 同一残片原样保留且可正确提取纯文本`() {
        val postProcessed = AiPromptBuilder.applyPersonaPostProcessing(
            truncatedJson,
            emptyList(),
            preserveRaw = true,
        )
        assertEquals(truncatedJson, postProcessed)
        assertEquals("你好呀今天过得怎么样", BubbleJsonProtocol.extractTextLenient(postProcessed))
    }
}
