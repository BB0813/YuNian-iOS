package com.yunian.ai.network.bubble

import com.yunian.ai.common.SecureLog

class BubbleLoopRunner(

    private val maxBubbles: Int = MAX_BUBBLES,

    private val maxRetries: Int = MAX_RETRIES,
) {

    suspend fun runFollowingBubbles(
        generateOnce: suspend (alreadyGenerated: List<String>) -> String,
    ): List<String> {
        val bubbles = mutableListOf<String>()
        var continueChat = true

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

                reply = BubbleJsonProtocol.parseStrict(raw)
                if (reply != null && reply.text.isNotBlank()) break
            }
            if (reply == null || reply.text.isBlank()) {

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

        const val MAX_BUBBLES = 16

        const val MAX_RETRIES = 3
    }
}
