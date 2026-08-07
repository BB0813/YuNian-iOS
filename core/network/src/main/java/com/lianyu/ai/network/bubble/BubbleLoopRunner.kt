package com.lianyu.ai.network.bubble

import com.lianyu.ai.common.SecureLog

/**
 * 气泡连发循环器（用户定稿架构的核心）。
 *
 * 职责：把「一轮 AI 回复」组织为**多次独立 API 调用**，每次调用输出一条气泡。
 * - 首条气泡通常由调用方现有的完整流程生成（流式/工具/视觉/本地模型），
 *   本循环器只负责「首条之后是否继续连发」。
 * - 每次调用前，把已生成的气泡作为 assistant 消息追加回历史，
 *   由调用方在 [generateOnce] 闭包中完成（各路径历史类型不同，循环器不感知具体类型）。
 * - 每次调用输出必须符合 [BubbleJsonProtocol] 的 JSON 格式；
 *   格式漂移（解析失败）→ 重新生成，最多 [MAX_RETRIES] 次。
 * - 硬上限 [MAX_BUBBLES]：含首条在内每轮最多这么多条气泡，{continue} 只是提议。
 * - 失败降级：某次调用最终仍无法解析 → 停止连发，已生成气泡全部保留（不补齐、不重试）。
 *
 * 注意：本类不直接依赖 AiServiceProvider / 历史类型，只依赖「单次生成函数」，
 * 因此可复用于单聊（sendMessage + extraSystemRules）、群聊（sendMessageWithCustomSystem）、
 * 微信转发等所有路径。
 */
class BubbleLoopRunner(
    /** 含首条在内，每轮最多气泡条数（App 侧硬性卡死） */
    private val maxBubbles: Int = MAX_BUBBLES,
    /** 单次调用的格式漂移重试次数 */
    private val maxRetries: Int = MAX_RETRIES,
) {

    /**
     * 在首条之后继续连发气泡。
     *
     * @param generateOnce 单次生成：入参为「本次调用前已生成的气泡列表」（不含首条），
     *       返回该次调用的原始文本（由调用方负责把已生成气泡追加进历史并调用 AI）。
     *       返回空文本视为该次调用失败，按重试逻辑处理。
     * @return 首条之后新生成的气泡列表（不含首条）
     */
    suspend fun runFollowingBubbles(
        generateOnce: suspend (alreadyGenerated: List<String>) -> String,
    ): List<String> {
        val bubbles = mutableListOf<String>()
        var continueChat = true
        // 首条已在调用方生成并占用 1 个名额；此处最多再发 maxBubbles - 1 条
        val remainingSlots = (maxBubbles - 1).coerceAtLeast(0)
        var attemptCount = 0
        while (continueChat && bubbles.size < remainingSlots) {
            var reply: BubbleReply? = null
            for (attempt in 0 until maxRetries) {
                attemptCount++
                val raw = try {
                    generateOnce(bubbles)
                } catch (e: Exception) {
                    SecureLog.w("BubbleLoopRunner", "generateOnce failed (attempt ${attempt + 1}): ${e.message}")
                    ""
                }
                // 严格解析：非法 JSON 一律视为格式漂移 → 重试（用户定稿：最多 3 次）
                reply = BubbleJsonProtocol.parseStrict(raw)
                if (reply != null && reply.text.isNotBlank()) break
            }
            if (reply == null || reply.text.isBlank()) {
                // 格式漂移重试耗尽 / 调用失败 → 停止连发，已生成气泡保留
                SecureLog.w("BubbleLoopRunner", "Bubble generation exhausted after $attemptCount attempts; stop chaining")
                break
            }
            bubbles.add(reply.text)
            continueChat = reply.continueChat
        }
        if (bubbles.size == remainingSlots && remainingSlots > 0) {
            SecureLog.d("BubbleLoopRunner", "Reached hard cap of $maxBubbles bubbles per turn")
        }
        return bubbles
    }

    companion object {
        /** 含首条在内，每轮最多气泡条数（用户定稿：16 条硬上限） */
        const val MAX_BUBBLES = 16

        /** 格式漂移重试上限（用户定稿：最多尝试 3 次） */
        const val MAX_RETRIES = 3
    }
}
