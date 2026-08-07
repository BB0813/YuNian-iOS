package com.lianyu.ai.feature.chat.ui.viewmodel

import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.domain.AiChatMessage
import com.lianyu.ai.domain.AiCompanionInfo
import com.lianyu.ai.domain.AiResponse
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.domain.AiTool
import com.lianyu.ai.domain.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * AI 工具调用执行循环（从 ChatViewModel 抽取，方案B：独立类 + 委托存根）。
 *
 * 流程：AI 返回 tool_calls → 执行本地工具 → 把结果追加到 history → 重新调用 AI。
 * 最多 [maxRounds] 轮，防死循环。最后一轮若仍为 tool_calls，转为提示文本。
 *
 * 安全约束：createOrder 等涉及支付的工具需用户确认——已实现确认门控机制，
 * 需要确认的工具执行前经 [ConfirmationGate] 等待用户确认，超时或拒绝则返回"用户已取消操作"。
 *
 * @param aiService AI 服务网关，用于 [sendMessage] 调用
 */
/** 工具执行前的用户确认门控；返回 true 继续执行，false 表示用户拒绝。 */
fun interface ConfirmationGate {
    suspend fun requestConfirmation(toolName: String, argumentsJson: String): Boolean
}

class AiToolLoopRunner(
    private val aiService: AiServiceProvider,
    private val confirmationGate: ConfirmationGate? = null
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    suspend fun executeWithToolLoop(
        companionInfo: AiCompanionInfo,
        history: List<AiChatMessage>,
        stickerProbability: Int,
        ntpTimeEnabled: Boolean,
        tools: List<AiTool>,
        groupId: Long? = null,
        maxRounds: Int = 3
    ): AiResponse {
        if (tools.isEmpty()) {
            return aiService.sendMessage(companionInfo, history, stickerProbability, ntpTimeEnabled)
        }

        // 用可变列表承载 history，工具调用中间轮次追加消息但不入库
        val mutableHistory = history.toMutableList()
        var currentResponse = aiService.sendMessage(companionInfo, mutableHistory.toList(), stickerProbability, ntpTimeEnabled, tools)

        var round = 0
        while (!currentResponse.toolCalls.isNullOrEmpty() && round < maxRounds) {
            round++
            val activeToolCalls = currentResponse.toolCalls!!
            ChatDebugLog.log("[ToolLoop] round $round: ${activeToolCalls.size} calls")

            for (toolCall in activeToolCalls) {
                val tool = ToolRegistry.get(toolCall.name)
                val arguments = argumentsForTool(toolCall.name, toolCall.arguments, companionInfo, groupId)
                val result = when {
                    tool == null -> "工具 ${toolCall.name} 不存在"
                    tool.requiresConfirmation && confirmationGate != null -> {
                        val gate = confirmationGate
                        val confirmed = try {
                            withTimeoutOrNull(TimeoutBudgets.AUTOMATION_CONFIRM_TIMEOUT_MS) {
                                gate.requestConfirmation(toolCall.name, arguments)
                            } ?: false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false
                        }
                        if (confirmed) {
                            executeTool(tool, arguments)
                        } else {
                            "用户已取消操作"
                        }
                    }
                    else -> executeTool(tool, arguments)
                }
                ChatDebugLog.log("[ToolLoop] ${toolCall.name} processed, resultLen=${result.length}")

                // 架构：工具结果必须标记 TOOL（序列化为 user 侧），禁止写成 assistant 导致自言自语
                mutableHistory.add(
                    com.lianyu.ai.domain.AiDialogueHistoryPolicy.toolResultMessage(
                        toolName = toolCall.name,
                        result = result,
                        companionId = companionInfo.id
                    )
                )
            }

            // 重新调用 AI，让它基于工具结果生成回复
            currentResponse = aiService.sendMessage(
                companionInfo,
                mutableHistory.toList(),
                stickerProbability,
                ntpTimeEnabled,
                tools
            )
        }

        // 超过最大轮次仍是 tool_calls → 转为提示
        if (!currentResponse.toolCalls.isNullOrEmpty()) {
            ChatDebugLog.log("[ToolLoop] reached max rounds $maxRounds")
            return AiResponse(
                content = "我已经帮你处理了相关操作，但还有部分工具调用未能完成。你可以告诉我具体想做什么，我来帮你。",
                reasoningContent = null,
                toolCalls = null,
                finishReason = "stop"
            )
        }

        return currentResponse
    }

    private suspend fun executeTool(tool: AiTool, arguments: String): String =
        try {
            withTimeoutOrNull(TimeoutBudgets.MCP_READ_MS) {
                tool.execute(arguments)
            } ?: "工具执行超时"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "工具执行失败: ${e.message}"
        }

    private fun argumentsForTool(
        toolName: String,
        argumentsJson: String,
        companionInfo: AiCompanionInfo,
        groupId: Long?
    ): String {
        if (toolName != "recall_memory" && toolName != "automation_create") return argumentsJson
        val obj = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull() ?: return argumentsJson
        return JsonObject(
            buildJsonObject {
                obj.forEach { (key, value) ->
                    if (key != "companionId" && key != "groupId") put(key, value)
                }
                if (groupId != null && toolName == "recall_memory") {
                    put("groupId", groupId)
                } else {
                    put("companionId", companionInfo.id)
                }
            }
        ).toString()
    }
}

