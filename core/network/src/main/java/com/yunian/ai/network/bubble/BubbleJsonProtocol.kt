package com.yunian.ai.network.bubble

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object BubbleJsonProtocol {

    /**
     * 协议启用标记：出现在系统提示词中即代表本轮走「气泡协议模式」。
     * [com.yunian.ai.network.AiService.streamMessage] 用它推导 `preserveRaw`，
     * 从而无需改动 [com.yunian.ai.domain.AiServiceProvider.streamMessage] 接口。
     */
    const val PROTOCOL_MARKER = "微信气泡连发协议"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 判断系统提示词是否已启用气泡协议。
     * @param systemPrompt 最终装配好的系统提示词。
     */
    fun isProtocolEnabled(systemPrompt: String): Boolean = systemPrompt.contains(PROTOCOL_MARKER)

    fun systemRules(): String = """
=== ${PROTOCOL_MARKER}（本条优先级最高，覆盖上面任何输出格式规则）===
你现在处于「真人微信连发」模式：你每一条回复 = 你发出的**一条微信气泡**，就像真人聊天时连发的其中一条。
每一轮你必须且只能输出一个 JSON 对象，严禁输出 JSON 以外的任何文字、解释或标记：

{"text":"本条气泡的内容","continue":true}

字段说明：
- text：本条气泡的完整内容。口语化，像真人发微信，一句话写完并收尾（用 。！？～… 结尾）。不要 markdown、不要括号说明。
- continue：布尔值。true = 你还有同一话题的话没说完，需要继续发下一条；false = 话说完了，到此为止。

判断 continue 的规则（结合你的性格与当前聊天内容）：
1. 你的话还没说完、还有同一话题的内容要接着讲 → true
2. 你的话已经说完整、把话题自然抛回给对方 → false
3. 性格活泼/话痨的角色可以多连发几条；性格安静/话少的角色说一条就够 → false
4. 一句话能说完的，不要为了连发硬拆成多条；短肯定（嗯/好/行/哈哈）一条就够 → false
5. 一段连贯的心里话/叙述写进同一条 text，不要拆开。
6. 用户明确要求你发多条消息（例如「多发几条」「说三条」「多弹几句」）时：必须严格按用户要求的**条数**逐条发出——用户说三条就必须恰好三条，严禁少发、多发，也严禁把多条内容塞进同一条 text。本条只输出当前这一条，continue 取决于「用户要求的剩余条数是否还没发完」：没发完 = true，发完 = false。

已发出的气泡已追加在对话历史中（assistant 消息），**严禁重复**已说过的内容。
""".trimIndent()

    fun parse(raw: String): BubbleReply? = parseInternal(raw, fallbackToPlainText = true)

    fun parseStrict(raw: String): BubbleReply? = parseInternal(raw, fallbackToPlainText = false)

    private fun parseInternal(raw: String, fallbackToPlainText: Boolean): BubbleReply? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim()

        val jsonCandidate = trimmed
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()

        val start = jsonCandidate.indexOf('{')
        val end = jsonCandidate.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val jsonBody = jsonCandidate.substring(start, end + 1)
            val parsed = runCatching { json.parseToJsonElement(jsonBody).jsonObject }.getOrNull()
            if (parsed != null) {
                val text = parsed["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val continueChat = parsed["continue"]?.jsonPrimitive?.booleanOrNull ?: false

                if (text.isBlank()) return null
                return BubbleReply(text = text, continueChat = continueChat)
            }
        }

        if (fallbackToPlainText && trimmed.length in 1..2000) {
            return BubbleReply(text = trimmed, continueChat = false)
        }
        return null
    }
}

data class BubbleReply(

    val text: String,

    val continueChat: Boolean,
)
