package com.lianyu.ai.network

/**
 * ResponsePostProcessor — AI 响应后处理纯函数集合。
 * 从 AiService 解耦, 无状态依赖。
 */
object ResponsePostProcessor {

    private val THINK_BLOCK_PATTERNS = listOf(
        // XML/HTML 风格思考标签
        Regex("""(?is)<think[^>]*>([\s\S]*?)</think\s*>"""),
        Regex("""(?is)<thinking[^>]*>([\s\S]*?)</thinking\s*>"""),
        Regex("""(?is)<thought[^>]*>([\s\S]*?)</thought\s*>"""),
        Regex("""(?is)<reflection[^>]*>([\s\S]*?)</reflection\s*>"""),
        // Markdown 风格思考标题（## 思考 / ## Thinking 等）
        Regex("""(?im)^#{1,3}\s*(思考|思维|推理|分析|Thinking|Reasoning|Analysis|Thought)\s*\n([\s\S]*?)(?=\n#{1,3}\s|$)"""),
        // 【思考】/【推理】等方括号包裹的思考块
        Regex("""(?is)【(思考|思维|推理|分析)】([\s\S]*?)【/\1】"""),
        // 行内 [思考] ... [/思考] 格式
        Regex("""(?is)\[(思考|思维|推理|分析|thought|thinking)]\s*([\s\S]*?)\[/\1]""")
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
        for (pattern in THINK_BLOCK_PATTERNS) {
            result = pattern.replace(result) { match ->
                val body = match.groupValues
                    .drop(1)
                    .lastOrNull { it.isNotBlank() && !it.matches(Regex("思考|思维|推理|分析|Thinking|Reasoning|Analysis|Thought|thought|thinking")) }
                    ?: match.groupValues.getOrNull(1)
                    ?: ""
                val cleanedBody = body.trim()
                if (cleanedBody.isNotBlank()) {
                    extracted += cleanedBody
                }
                ""
            }
        }
        val reasoning = extracted.joinToString("\n\n").trim().ifBlank { null }
        return result.trim() to reasoning
    }

    /**
     * 去除模型输出中的思考过程标签内容。
     * 支持 XML/HTML 风格、Markdown 标题、方括号包裹、行内标签等多种格式。
     */
    fun stripThinkingContent(content: String): String = extractThinkingContent(content).first

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
}
