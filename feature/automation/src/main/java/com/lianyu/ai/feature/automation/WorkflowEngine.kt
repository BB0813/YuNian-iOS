package com.lianyu.ai.feature.automation

import android.content.Context
import com.lianyu.ai.common.ContentFilter
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.repository.ChatRepository
import com.lianyu.ai.database.repository.MessageWriteCoordinator
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.feature.automation.data.Automation
import com.lianyu.ai.feature.automation.data.WorkflowEdge
import com.lianyu.ai.feature.automation.data.WorkflowNode
import com.lianyu.ai.feature.automation.data.WorkflowNodeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 工作流执行引擎。
 * 按 nodes/edges 构成的有向图执行，支持：
 * - START / END
 * - ACTION（发送伴侣消息 / 系统通知，文案可含 {{变量}}）
 * - AI_GENERATE（调用 AI 生成内容；**自动注入巡检快照**——最近聊天 + 距用户上次回复时长，
 *   让 AI 基于真实状态决定说什么，如"吃醋巡检"真的发现冷落才吃醋）
 * - CONDITION（简单条件分支：按变量内容含 true/是/yes 走 label=true 出边）
 */
class WorkflowEngine(
    private val context: Context,
    private val aiService: AiServiceProvider,
    private val messageWriter: MessageWriteCoordinator,
    private val chatRepository: ChatRepository? = null
) {

    /** 执行结果 */
    sealed class Result {
        data class Success(val message: String) : Result()
        data class Failure(val reason: String) : Result()
    }

    /** 执行一次工作流 */
    suspend fun execute(automation: Automation): Result = withContext(Dispatchers.IO) {
        if (!automation.isWorkflow) {
            return@withContext Result.Success(runAction(automation, null, emptyMap()))
        }
        val nodeMap = automation.nodes.associateBy { it.id }
        val startNode = automation.nodes.firstOrNull { it.type == WorkflowNodeType.START }
            ?: return@withContext Result.Failure("工作流缺少 START 节点")
        val variables = mutableMapOf<String, String>()
        val visited = mutableSetOf<String>()
        // 巡检上下文全图只查一次：多个 AI_GENERATE 节点复用，避免重复查库
        val patrolContext = if (automation.nodes.any { it.type == WorkflowNodeType.AI_GENERATE }) {
            buildPatrolContext(automation.companionId)
        } else ""
        var current = startNode
        var outputMessage = ""

        try {
            // 整图总预算：多 AI_GENERATE 节点串行 + 慢模型不能拖爆 worker(10min)/tick(60s) 窗口
            withTimeoutOrNull(TimeoutBudgets.AUTOMATION_WORKFLOW_TOTAL_MS) {
                while (true) {
                    if (current.id in visited) break
                    visited.add(current.id)

                    when (current.type) {
                        WorkflowNodeType.START -> { /* 继续 */ }
                        WorkflowNodeType.END -> break
                        WorkflowNodeType.ACTION -> {
                            outputMessage = runAction(automation, current, variables)
                        }
                        WorkflowNodeType.AI_GENERATE -> {
                            val enrichedPrompt = if (patrolContext.isBlank()) {
                                current.prompt
                            } else {
                                "$patrolContext\n\n【你的任务】${current.prompt}"
                            }
                            // 单节点业务超时：OkHttp 读超时 20s 兜底，45s 覆盖慢模型单次生成
                            val generated = withTimeoutOrNull(TimeoutBudgets.AUTOMATION_AI_TIMEOUT_MS) {
                                aiService.callGeneration(enrichedPrompt)
                            } ?: ""
                            variables[current.outputVar.ifBlank { "result" }] = generated
                            outputMessage = generated
                        }
                        WorkflowNodeType.CONDITION -> { /* 由出边 label 决定 */ }
                    }

                    val outEdges = automation.edges.filter { it.source == current.id }
                    val nextId = if (current.type == WorkflowNodeType.CONDITION) {
                        val conditionResult = evaluateCondition(current, variables)
                        outEdges.firstOrNull { it.label == conditionResult }?.target
                            ?: outEdges.firstOrNull()?.target
                    } else {
                        outEdges.firstOrNull()?.target
                    }

                    current = nextId?.let { nodeMap[it] } ?: break
                }

                Result.Success(outputMessage.ifBlank { "工作流执行完成" })
            } ?: Result.Failure("工作流执行超时")
        } catch (e: Exception) {
            SecureLog.e("WorkflowEngine", "execute failed", e)
            Result.Failure(e.message ?: "工作流执行异常")
        }
    }

    /**
     * 巡检快照：最近聊天 + 冷落时长，供 AI 判断真实状态。
     * 查询失败返回空串（不阻断生成，仅失去上下文）。
     */
    private suspend fun buildPatrolContext(companionId: Long): String {
        val repo = chatRepository ?: return ""
        return runCatching {
            val recent = repo.getRecentMessagesSync(companionId, 10)
                .filter { it.content.replace("\u200B", "").isNotBlank() }
            if (recent.isEmpty()) return@runCatching ""

            val sb = StringBuilder("【巡检快照 · ${formatNow()}】\n最近对话：\n")
            recent.takeLast(6).forEach { msg ->
                val speaker = if (msg.isFromUser) "用户" else "伴侣"
                sb.append("$speaker：${msg.content.take(60)}\n")
            }
            val lastUser = recent.lastOrNull { it.isFromUser }
            if (lastUser != null) {
                val idleMinutes = (System.currentTimeMillis() - lastUser.timestamp) / 60_000L
                val idleText = when {
                    idleMinutes < 10 -> "用户 $idleMinutes 分钟前刚回复，互动正常"
                    idleMinutes < 60 -> "用户已经 ${idleMinutes} 分钟没回消息了"
                    idleMinutes < 24 * 60 -> "用户已经 ${idleMinutes / 60} 小时没回消息了"
                    else -> "用户已经 ${idleMinutes / 60 / 24} 天没回消息了"
                }
                sb.append("冷落判断：$idleText\n")
            } else {
                sb.append("冷落判断：聊天记录里还没有用户的消息\n")
            }
            sb.toString()
        }.getOrElse { e ->
            SecureLog.w("WorkflowEngine", "buildPatrolContext failed: ${e.message}")
            ""
        }
    }

    private fun formatNow(): String {
        val cal = java.util.Calendar.getInstance()
        return "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日 " +
            "${cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')}:${cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')}"
    }

    private suspend fun runAction(
        automation: Automation,
        node: WorkflowNode?,
        variables: Map<String, String>
    ): String {
        val rawMessage = node?.message?.takeIf { it.isNotBlank() } ?: automation.message
        val message = replaceVariables(rawMessage, variables)
        if (message.isBlank()) return ""

        val outputSafety = ContentFilter.checkOutputSafety(message)
        if (!outputSafety.isSafe) {
            SecureLog.w("WorkflowEngine", "workflow message blocked: ${outputSafety.reason}")
            return ""
        }

        val actionType = node?.actionType ?: "companion"
        if (actionType == "notification") {
            AutomationNotifier.show(context, automation.title, message, automation.companionId)
        } else {
            runCatching {
                messageWriter.enqueueChat(
                    ChatMessage(
                        companionId = automation.companionId,
                        content = message,
                        isFromUser = false
                    )
                )
            }.onFailure { SecureLog.e("WorkflowEngine", "write chat message failed", it) }
        }
        return message
    }

    private fun evaluateCondition(node: WorkflowNode, variables: Map<String, String>): String {
        // 简单语义：变量值含 true/是/yes → 走 label=true 出边；否则 label=false
        val value = variables.values.joinToString(" ").lowercase()
        val matched = value.contains("true") || value.contains("是") || value.contains("yes")
        return if (matched) "true" else "false"
    }

    private fun replaceVariables(template: String, variables: Map<String, String>): String {
        var result = template
        variables.forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
