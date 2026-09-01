package com.lianyu.ai.network.bubble

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 气泡连发 JSON 协议。
 *
 * 架构背景（用户定稿，2026-07）：
 * 不再由客户端用启发式规则（MessageSegmenter）对 AI 输出做语义分句——
 * 「机器不认识情绪」，改为让 LLM 自己决定输出长度与是否继续：
 * 每轮对话 = 多次独立 API 调用，每次调用输出**一条气泡**，
 * 已发出的气泡以 assistant 消息追加回历史，AI 依据角色性格决定 {继续} 还是 {结束}。
 *
 * 协议格式（仿工具调用 JSON，防格式漂移）：
 *   {"text":"本条气泡的内容","continue":true}
 *
 * - text：本条气泡的完整内容（口语化、一句话、完整收尾）
 * - continue：true = 还有同一话题的话没说完，继续连发；false = 话说完了，到此为止
 *
 * 硬性约束：
 * - 每轮最多 [BubbleLoopRunner.MAX_BUBBLES] 条气泡（App 侧卡死，{continue} 只是提议）
 * - 格式漂移（解析失败）→ 重新生成，最多 [BubbleLoopRunner.MAX_RETRIES] 次；
 *   仍失败则停止连发，已生成气泡全部保留（不补齐、不重试）
 */
object BubbleJsonProtocol {

    /** 宽容解析配置：容忍多余字段（ignoreUnknownKeys） */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 追加到 system prompt 末尾的气泡协议指令。
     * 声明优先级最高，覆盖其他输出格式规则（分段/分块指令已移除）。
     */
    fun systemRules(): String = """
=== 微信气泡连发协议（本条优先级最高，覆盖上面任何输出格式规则）===
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
6. 用户明确要求你发多条消息（例如「多发几条」「说三条」「多弹几句」）时：必须逐条发出——本条只输出当前这一条，continue:true，直到把用户要求的内容全部发完才 continue:false。严禁把多条内容塞进同一条 text。

已发出的气泡已追加在对话历史中（assistant 消息），**严禁重复**已说过的内容。
""".trimIndent()

    /**
     * 宽容解析器：容忍 ```json 代码块包裹、首尾多余文本、缺 continue 字段等情况。
     * 无法解析时降级为「整段视为一条气泡」（保证至少交付）。
     *
     * @return 解析成功且 text 非空返回 [BubbleReply]；空/超长返回 null
     */
    fun parse(raw: String): BubbleReply? = parseInternal(raw, fallbackToPlainText = true)

    /**
     * 严格解析器（循环连发用）：仅接受有效 JSON 且 text 非空。
     * 与 [parse] 的区别：非法 JSON 一律返回 null（触发格式漂移重试），
     * 不做降级兜底——符合用户定稿「不符合规范 → 重新生成，最多 3 次」。
     */
    fun parseStrict(raw: String): BubbleReply? = parseInternal(raw, fallbackToPlainText = false)

    private fun parseInternal(raw: String, fallbackToPlainText: Boolean): BubbleReply? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim()
        // 去掉 ```json ... ``` 代码块包裹
        val jsonCandidate = trimmed
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()

        // 提取第一个 { 到最后一个 }（容忍前后缀杂音）
        val start = jsonCandidate.indexOf('{')
        val end = jsonCandidate.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val jsonBody = jsonCandidate.substring(start, end + 1)
            val parsed = runCatching { json.parseToJsonElement(jsonBody).jsonObject }.getOrNull()
            if (parsed != null) {
                val text = parsed["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val continueChat = parsed["continue"]?.jsonPrimitive?.booleanOrNull ?: false
                // JSON 有效但 text 为空 → 格式漂移，返回 null 触发重试（不降级为原文）
                if (text.isBlank()) return null
                return BubbleReply(text = text, continueChat = continueChat)
            }
        }

        // 降级兜底：宽容模式下完全无法解析 → 整段视为一条气泡并结束；
        // 严格模式（循环连发）下非法 JSON 必须返回 null 触发重试
        if (fallbackToPlainText && trimmed.length in 1..2000) {
            return BubbleReply(text = trimmed, continueChat = false)
        }
        return null
    }
}

/** 一条气泡协议回复 */
data class BubbleReply(
    /** 本条气泡的正文 */
    val text: String,
    /** true = 模型提议继续连发（App 侧仍受硬上限约束） */
    val continueChat: Boolean,
)
