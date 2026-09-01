package com.lianyu.ai.network.transformers

import com.lianyu.ai.domain.PlaceholderProvider
import com.lianyu.ai.network.Message
import com.lianyu.ai.network.transformers.TransformerContext

/**
 * 占位符展开转换器（输入转换器）。
 *
 * 将消息内容中的 {{key}} / {key} 模板变量替换为实际值。
 * 在 PromptInjectionTransformer 之后执行，确保注入的内容也能被占位符替换。
 */
class PlaceholderTransformer : MessageTransformer {
    override val id = "placeholder_expansion"
    override val isInput = true
    override val priority = 90  // 在注入之后、其他转换器之前

    override suspend fun transform(
        context: TransformerContext,
        messages: List<Message>
    ): List<Message> {
        val provider = context.placeholderProvider ?: return messages

        return messages.map { msg ->
            val originalContent = msg.content ?: ""
            val resolvedContent = provider.resolve(originalContent, context)
            if (resolvedContent == originalContent) msg else msg.copy(content = resolvedContent)
        }
    }
}

/**
 * 占位符提供者扩展：支持带上下文的解析。
 */
fun PlaceholderProvider.resolve(text: String, context: TransformerContext): String {
    var result = text

    // 内置占位符（优先使用上下文提供的值）
    val charName = context.characterName ?: resolve("{{char}}") ?: "角色"
    val userName = context.userNickname ?: resolve("{{user}}") ?: "用户"
    val curDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        .format(java.util.Date(context.currentTimeMillis))
    val curTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(context.currentTimeMillis))
    val modelId = context.modelId ?: resolve("{{model_id}}") ?: ""
    val modelName = context.modelName ?: resolve("{{model_name}}") ?: ""

    // 替换常用占位符（支持 {{key}} 和 {key} 两种格式，不区分大小写）
    result = result
        .replace("{{char}}", charName).replace("{char}", charName)
        .replace("{{CHAR}}", charName).replace("{CHAR}", charName)
        .replace("{{user}}", userName).replace("{user}", userName)
        .replace("{{USER}}", userName).replace("{USER}", userName)
        .replace("{{cur_date}}", curDate).replace("{cur_date}", curDate)
        .replace("{{cur_time}}", curTime).replace("{cur_time}", curTime)
        .replace("{{model_id}}", modelId).replace("{model_id}", modelId)
        .replace("{{model_name}}", modelName).replace("{model_name}", modelName)

    // 其余占位符委托给提供者实现
    val placeholderRegex = "\\{\\{([^}]+)\\}\\}|\\{([^}]+)\\}".toRegex()
    return placeholderRegex.replace(result) { matchResult ->
        val key = matchResult.groupValues[1]?.ifBlank { matchResult.groupValues[2] }?.lowercase() ?: ""
        resolve(key)?.let { it } ?: matchResult.value
    }
}