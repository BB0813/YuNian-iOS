package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.common.ChatConstants
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.AiCompanionInfo
import com.yunian.ai.domain.AiResponse
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

fun interface ConfirmationGate {
    suspend fun requestConfirmation(toolName: String, argumentsJson: String): Boolean
}

/** 工具调用状态（OpenMinis 风格过程可视化数据源） */
@Serializable
enum class ToolStatus {
    @SerialName("running") RUNNING,
    @SerialName("done") DONE,
    @SerialName("failed") FAILED,
}

/**
 * 单次工具调用的活动记录：RUNNING 先发射，结束后以同 id 再发射终态。
 * 可序列化以便持久化为 TOOL_ACTIVITY 消息（见 [ToolActivityCodec]）。
 */
@Serializable
data class ToolActivity(
    val id: Long,
    val toolName: String,
    val argsSummary: String,
    val status: ToolStatus,
    val resultSummary: String? = null,
    val startedAtMs: Long,
)

private val TOOL_FAILED_PREFIXES = arrayOf(
    "工具执行失败",
    "工具执行超时",
    "用户已取消",
    "工具执行被拒绝",
)

/** 工具结果 JSON 解析器（宽松，容忍非 JSON 文本）。 */
private val toolResultJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * 判定工具是否失败：
 * 1) 前缀约定（工具执行失败/超时/用户已取消/被拒绝）；
 * 2) 工具结果的 {ok:false} JSON 约定（如 skill_install / web_fetch / search_web）。
 * 用 JSON 解析而非字符串前缀，可兼容 `{"ok": false}`（冒号后带空格）、多行 pretty JSON 等写法；
 * 解析失败不抛异常，退回前缀判定；`{"ok":true,"empty":true}` 不会误判为失败。
 */
private fun isToolFailure(result: String): Boolean {
    if (TOOL_FAILED_PREFIXES.any { result.startsWith(it) }) return true
    val okFlag = runCatching {
        (toolResultJson.parseToJsonElement(result).jsonObject["ok"] as? JsonPrimitive)?.booleanOrNull
    }.getOrNull()
    return okFlag == false
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
        maxRounds: Int = ChatConstants.CHAT_TOOL_LOOP_MAX_ROUNDS,
        onToolActivity: ((ToolActivity) -> Unit)? = null,
    ): AiResponse {
        if (tools.isEmpty()) {
            return aiService.sendMessage(companionInfo, history, stickerProbability, ntpTimeEnabled)
        }

        val mutableHistory = history.toMutableList()
        var currentResponse = aiService.sendMessage(companionInfo, mutableHistory.toList(), stickerProbability, ntpTimeEnabled, tools)

        var round = 0
        // 本轮已执行过的工具名（按执行顺序，去重保序），用于轮次耗尽收尾时给出动作摘要，
        // 避免用户看到一句没有信息量的兜底模板。
        val executedTools = LinkedHashSet<String>()
        var skillInstallSucceeded = false
        val activityClock = AtomicLong(System.currentTimeMillis())
        while (!currentResponse.toolCalls.isNullOrEmpty() && round < maxRounds) {
            round++
            val activeToolCalls = currentResponse.toolCalls!!
            ChatDebugLog.log("[ToolLoop] round $round: ${activeToolCalls.size} calls")

            for (toolCall in activeToolCalls) {
                val tool = ToolRegistry.get(toolCall.name)
                val arguments = argumentsForTool(toolCall.name, toolCall.arguments, companionInfo, groupId)
                val activityId = activityClock.incrementAndGet()
                onToolActivity?.invoke(
                    ToolActivity(
                        id = activityId,
                        toolName = toolCall.name,
                        argsSummary = tool?.summarizeArguments(arguments) ?: arguments.take(120),
                        status = ToolStatus.RUNNING,
                        startedAtMs = System.currentTimeMillis(),
                    ),
                )
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
                executedTools.add(toolCall.name)
                if (toolCall.name == "skill_install" && result.contains("\"ok\":true")) {
                    skillInstallSucceeded = true
                }
                onToolActivity?.invoke(
                    ToolActivity(
                        id = activityId,
                        toolName = toolCall.name,
                        argsSummary = tool?.summarizeArguments(arguments) ?: arguments.take(120),
                        status = if (isToolFailure(result)) ToolStatus.FAILED else ToolStatus.DONE,
                        resultSummary = result.take(140),
                        startedAtMs = System.currentTimeMillis(),
                    ),
                )

                mutableHistory.add(
                    com.yunian.ai.domain.AiDialogueHistoryPolicy.toolResultMessage(
                        toolName = toolCall.name,
                        result = result,
                        companionId = companionInfo.id
                    )
                )
            }

            currentResponse = aiService.sendMessage(
                companionInfo,
                mutableHistory.toList(),
                stickerProbability,
                ntpTimeEnabled,
                tools
            )
        }

        if (!currentResponse.toolCalls.isNullOrEmpty()) {
            ChatDebugLog.log("[ToolLoop] reached max rounds $maxRounds")
            // 轮次耗尽：不再输出没有信息量的兜底模板，而是保留模型已产出的内容
            // 并附上「本轮已完成的动作摘要」，让用户知道实际发生了什么。
            // 注意：工具副作用（如 skill_install 已落盘）在调用时即已发生，不会因轮次耗尽被丢弃。
            val modelText = currentResponse.content?.takeIf { it.isNotBlank() }
            val actionSummary = buildActionSummary(executedTools, skillInstallSucceeded)
            val content = buildString {
                append(modelText ?: "我尝试执行了你的请求，但这轮工具调用较多，还没能全部跑完。")
                if (actionSummary.isNotBlank()) {
                    append("\n\n")
                    append(actionSummary)
                }
            }
            return AiResponse(
                content = content,
                reasoningContent = currentResponse.reasoningContent,
                toolCalls = null,
                finishReason = "stop"
            )
        }

        return currentResponse
    }

    /** 轮次耗尽时的动作摘要：列出已执行/已成功的工具，尤其标注技能是否安装成功。 */
    private fun buildActionSummary(executedTools: Set<String>, skillInstallSucceeded: Boolean): String {
        if (executedTools.isEmpty()) return ""
        val names = executedTools.joinToString("、")
        return buildString {
            append("本轮已执行：")
            append(names)
            append("。")
            if (skillInstallSucceeded) {
                append("其中技能已安装成功，可以在能力中心或后续对话中直接使用。")
            }
        }
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
