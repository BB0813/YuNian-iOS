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

    /** 抠取残缺 JSON 中 "text" 字段值（支持转义）的正则。 */
    private val TEXT_FIELD_REGEX = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

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

    /**
     * 协议模式下模型输出非法/畸形 JSON 时的宽容提取：
     * 优先用正则从残缺 JSON 中抠出 "text" 字段的真实内容；若无 text 字段但整体像 JSON 残片，
     * 剥掉 JSON 结构字符（`{}[]`、`"continue": true/false`、多余的引号）；都不像 JSON 则原样返回。
     * 目标：绝不把 `{"text":"…","continue":` 这类残片展示给用户。
     */
    fun extractTextLenient(raw: String): String {
        if (raw.isBlank()) return raw

        // 1. 直接从残缺 JSON 中抠出 "text" 字段（含基本反转义）。
        val match = TEXT_FIELD_REGEX.find(raw)
        if (match != null) {
            val extracted = unescapeJsonString(match.groupValues[1]).trim()
            if (extracted.isNotEmpty()) return extracted
        }

        // 1b. 防御式兜底：若 JSON 骨架已被上游（extractDirectReply 引号抽取）打散成
        //     "text\n<正文>\ncontinue" 形态，则按行剔骨取正文，避免骨架泄漏给用户。
        val lines = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 2 && lines.first() == "text") {
            val body = if (lines.last() == "continue") lines.subList(1, lines.size - 1) else lines.drop(1)
            val joined = body.joinToString("\n").trim()
            if (joined.isNotEmpty()) return joined
        }

        // 2. 无 text 字段：若形如 JSON 残片则剥结构字符，避免把协议骨架暴露给用户。
        val trimmed = raw.trimStart()
        val looksLikeJson = raw.contains("\"continue\"") || trimmed.startsWith("{") || trimmed.startsWith("[")
        if (looksLikeJson) {
            var stripped = raw
                .replace(Regex("\"continue\"\\s*:\\s*(true|false)"), "")
                .replace(Regex("[{}\\[\\]]"), "")
                .replace(Regex("^[\"\\s]+"), "")
                .replace(Regex("[\"\\s]+$"), "")
                .trim()
            // 处理 `"text" : 内容` 前缀残留（例如缺失闭合引号的情形）。
            stripped = stripped.replace(Regex("^\"?text\"?\\s*:\\s*"), "").trim()
            stripped = stripped.trim('"').trim()
            if (stripped.isNotEmpty()) return stripped
        }

        // 3. 都不成立 → 原样返回（不制造空消息）。
        return raw
    }

    /** JSON 字符串内容的基本反转义（`\n` `\t` `\r` `\b` `\f` `\"` `\\` `\/` `\uXXXX`）。 */
    private fun unescapeJsonString(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'u' -> {
                        val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6) else ""
                        val code = hex.toIntOrNull(16)
                        if (hex.length == 4 && code != null) {
                            sb.append(code.toChar())
                            i += 6
                        } else {
                            sb.append(n)
                            i += 2
                        }
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }

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
