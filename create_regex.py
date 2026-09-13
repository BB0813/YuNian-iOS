#!/usr/bin/env python3
# -*- coding: utf-8 -*-

content = '''package com.yunian.ai.network.transformers

import com.yunian.ai.network.Message
import com.yunian.ai.network.transformers.TransformerContext
import java.util.regex.Pattern

/**
 * Regex output transformer.
 */
class RegexOutputTransformer : MessageTransformer {
    override val id = "regex_output_transform"
    override val isInput = false
    override val priority = 100

    companion object {
        const val DEFAULT_RULE_SET = "default"
    }

    data class ReplacementRule(
        val pattern: String,
        val replacement: String,
        val flags: Int = 0,
        val description: String = "",
        val visualOnly: Boolean = true,
        val enabled: Boolean = true
    )

    data class RuleSet(
        val name: String,
        val rules: List<ReplacementRule>,
        val enabled: Boolean = true
    )

    private val builtInRuleSets = mapOf(
        DEFAULT_RULE_SET to RuleSet(
            name = DEFAULT_RULE_SET,
            rules = listOf(
                ReplacementRule(
                    pattern = "\\\\n{3,}",
                    replacement = "\\n\\n",
                    description = "Merge empty lines",
                    visualOnly = true,
                    enabled = true
                ),
                ReplacementRule(
                    pattern = "(.+)",
                    replacement = "$1",
                    description = "Normalize",
                    visualOnly = true,
                    enabled = true
                ),
            ],
            enabled = true
        )
    )

    private val runtimeRuleSets = mutableMapOf<String, RuleSet>()

    init {
        runtimeRuleSets.putAll(builtInRuleSets)
    }

    fun getRuleSet(name: String): RuleSet = runtimeRuleSets.getOrPut(name) {
        RuleSet(name, emptyList())
    }

    fun setRuleSet(ruleSet: RuleSet) {
        runtimeRuleSets[ruleSet.name] = ruleSet
    }

    fun addRule(ruleSetName: String, rule: ReplacementRule) {
        val current = getRuleSet(ruleSetName)
        runtimeRuleSets[ruleSetName] = current.copy(rules = current.rules + rule)
    }

    fun removeRule(ruleSetName: String, pattern: String) {
        val current = getRuleSet(ruleSetName)
        runtimeRuleSets[ruleSetName] = current.copy(
            rules = current.rules.filter { it.pattern != pattern }
        )
    }

    override suspend fun transform(
        context: TransformerContext,
        messages: List<Message>
    ): List<Message> {
        val lastAssistantIndex = messages.indexOfLast { msg: Message -> msg.role == "assistant" }
        if (lastAssistantIndex < 0) return messages

        val lastMessage = messages[lastAssistantIndex]
        val transformedContent = applyRules(lastMessage.content ?: "", context)

        if (transformedContent == lastMessage.content) return messages

        val result = messages.toMutableList()
        val newMetadata = (lastMessage.metadata ?: mutableMapOf<String, String>()).toMutableMap()
        newMetadata["original_content"] = lastMessage.content ?: ""
        result[lastAssistantIndex] = lastMessage.copy(
            content = transformedContent,
            metadata = newMetadata
        )
        return result
    }

    private fun applyRules(text: String, context: TransformerContext): String {
        var result = text

        val ruleSetName = when {
            context.isGroupChat -> "group"
            context.sessionId > 0 -> "companion_${context.sessionId}"
            else -> DEFAULT_RULE_SET
        }

        val ruleSet = getRuleSet(ruleSetName)
        if (!ruleSet.enabled) return result

        for (rule in ruleSet.rules) {
            if (!rule.enabled) continue
            try {
                val pattern = Pattern.compile(rule.pattern, rule.flags)
                val matcher = pattern.matcher(result)
                result = matcher.replaceAll(rule.replacement)
            } catch (e: Exception) {
            }
        }

        return result
    }
}

/** 扩展：支持在 Message metadata 中存储原始内容 */
fun Message.getOriginalContent(): String? {
    return metadata?.get("original_content") as? String
}
'''

with open('/h/susu/core/network/src/main/java/com/yunian/ai/network/transformers/RegexOutputTransformer.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print('File written successfully')