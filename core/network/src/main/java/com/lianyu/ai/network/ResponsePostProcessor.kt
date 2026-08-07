package com.lianyu.ai.network

/**
 * ResponsePostProcessor — AI 响应后处理纯函数集合。
 * 从 AiService 解耦, 无状态依赖。
 *
 * 职责：把模型「思考过程」从用户可见正文中剥离，并尽量保留真实回复。
 * 覆盖：闭合/未闭合标签、LM_THINK、markdown 标题、方括号块、纯文本 CoT 前缀。
 */
object ResponsePostProcessor {

    private val LABEL_ONLY = Regex(
        "思考|思维|推理|分析|Thinking|Reasoning|Analysis|Thought|thought|thinking|LM_THINK|redacted_reasoning",
        RegexOption.IGNORE_CASE,
    )

    private val CLOSED_THINK_BLOCK_PATTERNS = listOf(
        // XML/HTML 风格思考标签（含常见别名）
        Regex("""(?is)<think[^>]*>([\s\S]*?)</think\s*>"""),
        Regex("""(?is)<thinking[^>]*>([\s\S]*?)</thinking\s*>"""),
        Regex("""(?is)<thought[^>]*>([\s\S]*?)</thought\s*>"""),
        Regex("""(?is)<reflection[^>]*>([\s\S]*?)</reflection\s*>"""),
        Regex("""(?is)<reasoning[^>]*>([\s\S]*?)</reasoning\s*>"""),
        Regex("""(?is)<LM_THINK[^>]*>([\s\S]*?)</LM_THINK\s*>"""),
        Regex("""(?is)<redacted_reasoning[^>]*>([\s\S]*?)</redacted_reasoning\s*>"""),
        // Markdown 代码围栏
        Regex("""(?is)```(?:thinking|thought|reasoning|analysis|think)\s*([\s\S]*?)```"""),
        // Markdown 风格思考标题（## 思考 / ## Thinking 等）
        Regex("""(?im)^#{1,3}\s*(思考|思维|推理|分析|Thinking|Reasoning|Analysis|Thought)\s*\n([\s\S]*?)(?=\n#{1,3}\s|$)"""),
        // 【思考】/【推理】等方括号包裹的思考块
        Regex("""(?is)【(思考|思维|推理|分析)】([\s\S]*?)【/\1】"""),
        // 行内 [思考] ... [/思考] 格式
        Regex("""(?is)\[(思考|思维|推理|分析|thought|thinking)]\s*([\s\S]*?)\[/\1]"""),
    )

    /** 未闭合标签：从开标签一直吃到文末（模型常漏写结束标签）。 */
    private val UNCLOSED_THINK_OPEN_PATTERNS = listOf(
        Regex("""(?is)<think[^>]*>[\s\S]*$"""),
        Regex("""(?is)<thinking[^>]*>[\s\S]*$"""),
        Regex("""(?is)<thought[^>]*>[\s\S]*$"""),
        Regex("""(?is)<reflection[^>]*>[\s\S]*$"""),
        Regex("""(?is)<reasoning[^>]*>[\s\S]*$"""),
        Regex("""(?is)<LM_THINK[^>]*>[\s\S]*$"""),
        Regex("""(?is)<redacted_reasoning[^>]*>[\s\S]*$"""),
        Regex("""(?is)```(?:thinking|thought|reasoning|analysis|think)\s*[\s\S]*$"""),
    )

    /** 行首/段首纯文本思考前缀（无标签时的常见泄露）。 */
    private val PLAINTEXT_THINK_PREFIX = Regex(
        """(?im)^(?:思考过程|思维链|推理过程|内心独白|角色思考|分析过程|我的思考|最终回复|Final\s*Answer|Answer|Reasoning|Thought\s*Process)\s*[:：]\s*""",
    )

    /** 仅匹配明显的「元推理开场」，避免误伤「理解你…」「分析一下这道题」等正常口语。 */
    private val PLAINTEXT_LEADING_COT = Regex(
        """(?is)^\s*(?:好的[，,]?\s*)?(?:我(?:现在|先)?(?:需要|得|要|应该)|让我(?:先|来)?|首先|接下来)\s*(?:分析|理解|判断|思考|推理)(?:一下|下)?(?:用户|对方)?(?:的)?(?:意图|意思|问题|消息|说的)[：:]?\s*""",
    )

    private val COT_SENTENCE_MARKERS = listOf(
        "思考过程", "思维链", "推理过程", "内心独白", "用户意图", "用户想表达",
        "我应该回复", "我需要回复", "我得回复", "作为AI", "作为助手",
        "不能让任何人知道", "系统提示", "根据人设", "按照人设",
        "先分析", "先理解用户", "最终回复应该", "输出格式",
    )

    /**
     * 从模型输出中提取思考过程，并返回清洗后的正文。
     *
     * @return Pair(cleanedContent, extractedReasoning)
     *         extractedReasoning 在未匹配到思考块时为 null。
     */
    fun extractThinkingContent(content: String): Pair<String, String?> {
        if (content.isBlank()) return content to null
        val extracted = mutableListOf<String>()
        var result = content

        for (pattern in CLOSED_THINK_BLOCK_PATTERNS) {
            result = pattern.replace(result) { match ->
                val body = pickThinkBody(match.groupValues)
                if (body.isNotBlank()) extracted += body
                ""
            }
        }

        // 未闭合标签：整段视为思考（常见于流式截断 / 模型漏写结束标签）
        for (pattern in UNCLOSED_THINK_OPEN_PATTERNS) {
            val match = pattern.find(result) ?: continue
            val openEnd = match.value.indexOf('>').takeIf { it >= 0 }?.plus(1)
                ?: match.value.indexOf('\n').takeIf { it >= 0 }?.plus(1)
                ?: 0
            val body = match.value.substring(openEnd.coerceAtMost(match.value.length)).trim()
            if (body.isNotBlank()) extracted += body
            result = result.removeRange(match.range)
        }

        // 去掉残留的孤立结束标签
        result = result
            .replace(Regex("""(?is)</(?:think|thinking|thought|reflection|reasoning|LM_THINK|redacted_reasoning)\s*>"""), "")

        // 纯文本前缀 / 段首 CoT
        val beforePlain = result
        result = stripPlaintextThinking(result)
        if (result != beforePlain) {
            val removed = beforePlain.removePrefix(result).trim().ifBlank {
                beforePlain.replace(result, "").trim()
            }
            if (removed.isNotBlank() && removed.length >= 4) {
                extracted += removed
            }
        }

        val reasoning = extracted.joinToString("\n\n").trim().ifBlank { null }
        return result.trim() to reasoning
    }

    /**
     * 去除模型输出中的思考过程标签内容。
     * 支持 XML/HTML 风格、Markdown 标题、方括号包裹、行内标签、未闭合标签、纯文本 CoT 前缀。
     */
    fun stripThinkingContent(content: String): String = extractThinkingContent(content).first

    /**
     * 判断文本是否仍像「思考过程」而非可展示回复（用于空正文兜底，避免把 CoT 写回气泡）。
     */
    fun looksLikeThinkingLeak(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.contains(Regex("""(?is)</?(?:think|thinking|thought|reflection|reasoning|LM_THINK|redacted_reasoning)\b"""))) {
            return true
        }
        if (PLAINTEXT_THINK_PREFIX.containsMatchIn(t)) return true
        val hit = COT_SENTENCE_MARKERS.count { t.contains(it) }
        if (hit >= 2) return true
        if (hit >= 1 && t.length > 80) return true
        return false
    }

    /**
     * 闲聊情绪宣泄且未求方案时，强制「单次单动作」：
     * 裁掉护理包/方案/推荐，并只保留第一个完整主焦点句。
     * 求方案、安全、非情绪闲聊原样返回。
     *
     * 策略（仅 idle emotion）：
     * - 按句扫描，护理/派活/第二动作句一律丢；
     * - 只保留第一个非护理主焦点（接情绪/追问/表态），且保留整句完整口语；
     * - 单句尾部塞护理清单时，从护理标记前截断，不按逗号把前半句切成半截；
     * - 多动作邮件按句丢后续，不按字数/逗号数无脑截断。
     */
    fun trimIdleEmotionOverDelivery(response: String, lastUserMessage: String): String {
        val raw = response.trim()
        if (raw.isEmpty()) return raw
        if (!AiContextTools.isIdleEmotionVent(lastUserMessage)) return raw

        // 先按空行保留模型主动分块，再按句扫描；单动作≠单气泡
        val blocks = raw
            .split(Regex("""\n\s*\n"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val parts = if (blocks.size >= 2) {
            blocks.flatMap { block ->
                block.split(Regex("""(?<=[。！？!?～…])"""))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .ifEmpty { listOf(block) }
            }
        } else {
            raw.split(Regex("""(?<=[。！？!?～…\n])"""))
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }
        if (parts.size <= 1) {
            // 单句但尾部塞护理/第二动作：从标记处截，保留完整前半意图
            return enforceSingleActionFocus(trimSinglePackedSentence(raw))
        }

        val kept = mutableListOf<String>()
        for (part in parts) {
            if (isCarePackageClause(part) || isSecondaryActionClause(part) || hasAdviceDispatch(part)) {
                // 护理/方案/第二动作：闲聊情绪轮一律丢；已有主焦点则停止，避免后半再粘回来
                if (kept.isNotEmpty()) break
                continue
            }
            if (looksLikePreachOrLecture(part)) {
                if (kept.isNotEmpty()) break
                continue
            }
            if (kept.isEmpty()) {
                kept += part
                continue
            }
            // 允许同一意图的自然多气泡（短接/追问/语气），不是多意图邮件
            if (kept.size >= IDLE_MAX_SPOKEN_SEGMENTS) break
            if (canKeepAsSameIntentBubble(kept, part)) {
                kept += part
            } else {
                break
            }
        }

        val result = kept.joinToString("\n\n").trim()
        if (result.isNotBlank()) {
            // 各段再压一次尾部护理；保留多段空行，由上层整体作为一条气泡交付
            val tightenedParts = kept.map { enforceSingleActionFocus(trimSinglePackedSentence(it)) }
                .map { it.trim() }
                .filter { it.isNotBlank() && !isCarePackageClause(it) }
            val tightened = tightenedParts.joinToString("\n\n").trim()
            return tightened
        }
        // 极端：全是护理包。尽量截出非护理前缀；仍不行则返回空（由上层空白兜底）
        val fallback = parts
            .asSequence()
            .map { enforceSingleActionFocus(trimSinglePackedSentence(it)) }
            .firstOrNull { it.isNotBlank() && !isCarePackageClause(it) }
            .orEmpty()
        return fallback
    }

    /** 闲聊情绪轮：同一意图最多保留几条口语气泡（与分段软上限对齐）。 */
    private const val IDLE_MAX_SPOKEN_SEGMENTS = 3

    /** 说教/总结腔：第二动作，不与首个接住句并成多气泡。 */
    private fun looksLikePreachOrLecture(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val markers = listOf(
            "固然重要", "革命的本钱", "身体是", "要注意", "你应该", "你必须",
            "说到底", "归根结底", "总而言之", "综上所述", "我总结", "其实你要",
        )
        return markers.any { t.contains(it) }
    }

    /**
     * 已有主焦点后，下一段是否仍属同一社交意图的自然连发气泡。
     * 允许：短句、问句、弱续接；拒绝：长独立陈述/新动作。
     */
    private fun canKeepAsSameIntentBubble(kept: List<String>, next: String): Boolean {
        val n = next.trim()
        if (n.isEmpty()) return false
        if (isCarePackageClause(n) || isSecondaryActionClause(n) || hasAdviceDispatch(n)) return false
        if (looksLikePreachOrLecture(n)) return false
        val isQuestion = n.contains('？') || n.contains('?') ||
            n.endsWith("吗") || n.endsWith("吧") || n.endsWith("呢")
        val coreLen = n.trimEnd('。', '！', '？', '!', '?', '～', '…', '.', ' ').length
        if (coreLen <= 12) return true
        if (isQuestion && coreLen <= 36) return true
        // 首段很短（嗯/咋了）时，允许再跟一条完整口语
        val firstCore = kept.first().trimEnd('。', '！', '？', '!', '?', '～', '…', '.', ' ')
        if (firstCore.length <= 8 && coreLen <= 40) return true
        return false
    }

    /**
     * 单次单动作兜底：只在出现护理包/第二动作/明确多动作打包时收束。
     * 保留完整一句口语（含逗号停顿）；绝不因「逗号多/略超 30 字」切成半截话。
     */
    private fun enforceSingleActionFocus(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        if (containsCarePackageMarker(t) || CARE_DISPATCH_PREFIX.containsMatchIn(t) || isSecondaryActionClause(t)) {
            return trimSinglePackedSentence(t)
        }
        if (!hasExplicitMultiActionPack(t)) return t

        // 多动作邮件：按句保留第一个完整主焦点，不按逗号切碎
        val sentences = t
            .split(Regex("""(?<=[。！？!?～…])"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (sentences.size <= 1) {
            // 单句内硬塞多动作：尽量从第二动作标记前截断，保留完整前半
            return trimAtSecondAction(t)
        }
        val first = sentences.firstOrNull { s ->
            !isCarePackageClause(s) && !isSecondaryActionClause(s)
        }.orEmpty()
        return first.ifBlank { "" }
    }

    /** 明确的多动作打包痕迹（不是自然口语里的逗号停顿）。 */
    private fun hasExplicitMultiActionPack(text: String): Boolean {
        val packHints = listOf(
            "建议你", "我建议", "可以试试", "不如先", "推荐你",
            "方案", "第一步", "第二步", "首先", "其次", "最后记得",
            "要不要泡", "要不要听", "要不要睡", "要不要喝", "要不要躺",
        )
        val hit = packHints.count { text.contains(it) }
        if (hit >= 1 && (containsCarePackageMarker(text) || text.length > 48)) return true
        if (hit >= 2) return true
        // 两句以上且后句像派活/建议
        val sentences = text.split(Regex("""(?<=[。！？!?～…])""")).map { it.trim() }.filter { it.isNotBlank() }
        if (sentences.size >= 2 && sentences.drop(1).any { isCarePackageClause(it) || isSecondaryActionClause(it) || hasAdviceDispatch(it) }) {
            return true
        }
        return false
    }

    private fun hasAdviceDispatch(text: String): Boolean {
        val t = text.trim()
        return t.contains("建议") || t.contains("可以试试") || t.contains("不如") ||
            t.contains("要不要") || t.startsWith("记得") || t.contains("别忘了")
    }

    private fun trimAtSecondAction(text: String): String {
        val markers = listOf(
            "建议你", "我建议", "可以试试", "不如先", "推荐你",
            "第一步", "第二步", "首先", "其次",
            "要不要泡", "要不要听", "要不要睡", "要不要喝", "要不要躺",
            "另外", "顺便", "还有啊",
        )
        var cut = Int.MAX_VALUE
        for (m in markers) {
            val i = text.indexOf(m)
            if (i >= 8 && i < cut) cut = i
        }
        val careCut = firstCareMarkerIndex(text)
        if (careCut >= 8 && careCut < cut) cut = careCut
        if (cut == Int.MAX_VALUE) return text
        val head = text.take(cut).trimEnd('，', ',', '；', ';', '、', ' ', '。', '.', '！', '!', '？', '?').trim()
        if (head.length < 4) return ""
        return ensureTerminalPunctuation(head)
    }

    private fun trimSinglePackedSentence(text: String): String {
        if (!containsCarePackageMarker(text) &&
            !CARE_DISPATCH_PREFIX.containsMatchIn(text) &&
            !isSecondaryActionClause(text)
        ) {
            return text
        }
        val clauses = text
            .split(Regex("""[，,；;、]"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (clauses.size <= 1) {
            // 整句都是护理语义则尽量截到第一个护理标记前；前缀过短则丢空
            val cut = firstCareMarkerIndex(text)
            return if (cut > 8) {
                ensureTerminalPunctuation(
                    text.take(cut).trimEnd('，', ',', '；', ';', '、', ' ').trim(),
                )
            } else {
                ""
            }
        }
        // 保留护理标记前的全部口语小句，拼成完整一句；不要只留第一个逗号片段
        val kept = mutableListOf<String>()
        for (c in clauses) {
            if (isCarePackageClause(c) || isSecondaryActionClause(c)) break
            kept += c
        }
        if (kept.isEmpty()) {
            val cut = firstCareMarkerIndex(text)
            return if (cut > 8) {
                ensureTerminalPunctuation(
                    text.take(cut).trimEnd('，', ',', '；', ';', '、', ' ').trim(),
                )
            } else {
                ""
            }
        }
        return ensureTerminalPunctuation(kept.joinToString("，"))
    }

    private fun ensureTerminalPunctuation(text: String): String {
        val joined = text.trim()
        if (joined.isEmpty()) return joined
        val needsEnd = !joined.endsWith("。") && !joined.endsWith("！") &&
            !joined.endsWith("？") && !joined.endsWith("～") && !joined.endsWith("…") &&
            !joined.endsWith("?") && !joined.endsWith("!")
        return if (needsEnd) "$joined。" else joined
    }

    private fun containsCarePackageMarker(text: String): Boolean =
        CARE_PACKAGE_MARKERS.any { text.contains(it) }

    private fun firstCareMarkerIndex(text: String): Int {
        var min = Int.MAX_VALUE
        for (m in CARE_PACKAGE_MARKERS) {
            val i = text.indexOf(m)
            if (i >= 0 && i < min) min = i
        }
        val dispatch = CARE_DISPATCH_PREFIX.find(text)?.range?.first
        if (dispatch != null && dispatch < min) min = dispatch
        return if (min == Int.MAX_VALUE) -1 else min
    }

    private fun isCarePackageClause(text: String): Boolean {
        if (CARE_PACKAGE_MARKERS.any { text.contains(it) }) return true
        // 「先去…」「要不要…」「过来抱抱…」类派活
        if (CARE_DISPATCH_PREFIX.containsMatchIn(text)) return true
        return false
    }

    private fun isSecondaryActionClause(text: String): Boolean {
        if (isCarePackageClause(text)) return true
        return SECONDARY_ACTION_MARKERS.any { text.contains(it) }
    }

    /**
     * 护理/方案/安顿类标记。覆盖真实 UI 改写形态（肩膀/眯一会儿/搂着睡/小夜灯等），
     * 仅用于 idle emotion 裁剪，不影响求方案/安全轮。
     */
    private val CARE_PACKAGE_MARKERS = listOf(
        // 经典护理清单
        "泡脚", "听歌", "早睡", "揉肩", "太阳穴", "洗头", "洗澡", "躺沙发", "去沙发",
        "喝热水", "喝杯水", "热牛奶", "早点睡", "别熬夜", "不许熬夜", "当夜猫", "夜猫",
        "捏捏", "按摩", "敷眼", "休息五分钟", "躺五分钟", "眯五分钟", "人一会儿", "靠着睡",
        "先去躺", "先去睡", "先去洗", "给你揉", "给你捏", "给你按",
        "要不要泡", "要不要听", "要不要睡", "要不要喝", "要不要躺",
        // 真实失败改写：肢体安顿 / 补觉 / 陪护
        "肩膀", "靠我肩", "靠着我", "眯一会儿", "眯一会", "闭一会儿", "闭一会",
        "补回来", "补觉", "陪着你", "搂着你", "抱着你睡", "过来抱抱", "过来让我",
        "小夜灯", "开灯陪", "沙发上", "躺一会儿", "躺一会", "躺一下", "去躺",
        "揉揉", "按按", "捏一捏", "拍拍背", "盖好被子", "喝口水", "休息一下",
        "不许说不",
    )

    private val CARE_DISPATCH_PREFIX = Regex(
        """(?:现在)?先(?:去|把|来|让|给)|过来(?:让我|给我|抱抱|靠)?|不许再|今晚不许|要是敢|把脑袋靠|靠过来|闭上眼|先眯|先躺|先睡|先休息""",
    )

    private val SECONDARY_ACTION_MARKERS = listOf(
        "另外", "还有啊", "对了", "顺便", "顺便说", "再说一件", "还有一件",
        "你还记得", "我们改天", "明天记得", "别忘了",
        "不过看你", "不过你", "话说回来",
    )

    /**
     * 检查响应体是否为 HTML（非 JSON）并抛出明确错误。
     * 某些服务商（如讯飞星火）在鉴权失败时返回 HTTP 200 + HTML 错误页,
     * 若不拦截会导致 JSON 解析崩溃并产生难以理解的异常。
     */
    fun ensureNotHtml(body: String, response: okhttp3.Response) {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("<!") || trimmed.startsWith("<html", ignoreCase = true)) {
            val hint = when {
                response.code == 401 || response.code == 403 ->
                    " (请检查API密钥/APIPassword是否正确)"
                response.code == 404 ->
                    " (请检查API地址和模型名是否正确)"
                else -> " (HTTP ${response.code}，请检查API配置)"
            }
            throw Exception("服务器返回了网页而非API响应$hint")
        }
    }

    private fun pickThinkBody(groupValues: List<String>): String {
        val body = groupValues
            .drop(1)
            .lastOrNull { it.isNotBlank() && !LABEL_ONLY.matches(it.trim()) }
            ?: groupValues.getOrNull(1)
            ?: ""
        return body.trim()
    }

    private fun stripPlaintextThinking(text: String): String {
        var result = text.trim()
        if (result.isEmpty()) return result

        // 多轮剥行首标签式前缀
        repeat(3) {
            val next = PLAINTEXT_THINK_PREFIX.replace(result, "").trim()
            if (next == result) return@repeat
            result = next
        }

        // 若整段以「我现在需要分析用户…」类 CoT 开头，尝试只保留末段短回复
        if (PLAINTEXT_LEADING_COT.containsMatchIn(result) || COT_SENTENCE_MARKERS.any { result.contains(it) }) {
            val paragraphs = result.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotBlank() }
            if (paragraphs.size >= 2) {
                val last = paragraphs.last()
                val head = paragraphs.dropLast(1).joinToString("\n\n")
                val headLooksCot = COT_SENTENCE_MARKERS.any { head.contains(it) } ||
                    PLAINTEXT_LEADING_COT.containsMatchIn(head) ||
                    PLAINTEXT_THINK_PREFIX.containsMatchIn(head)
                if (headLooksCot && last.length in 2..120 && !looksLikeThinkingLeak(last)) {
                    return last
                }
            }

            // 单段：去掉开头 CoT 引导语，保留后半
            val strippedLead = PLAINTEXT_LEADING_COT.replace(result, "").trim()
            if (strippedLead.length in 2 until result.length && !looksLikeThinkingLeak(strippedLead)) {
                val markerHits = COT_SENTENCE_MARKERS.count { strippedLead.contains(it) }
                if (markerHits == 0) {
                    result = strippedLead
                } else if (markerHits >= 2 && strippedLead.length > 60) {
                    result = ""
                }
            } else if (looksLikeThinkingLeak(result) && paragraphs.size < 2) {
                // 整段都是思考、没有可分离回复
                result = ""
            }
        }

        return result.trim()
    }
}
