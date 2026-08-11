package com.lianyu.ai.feature.automation.data

import kotlinx.serialization.Serializable

enum class AutomationType { ONCE, DAILY, WEEKLY }

/** 工作流节点类型 */
enum class WorkflowNodeType {
    /** 起始节点 */
    START,
    /** 结束节点 */
    END,
    /** 动作：发送固定消息或通知 */
    ACTION,
    /** 调用 AI 生成内容，结果写入变量 */
    AI_GENERATE,
    /** 条件分支 */
    CONDITION
}

/** 工作流节点 */
@Serializable
data class WorkflowNode(
    val id: String,
    val type: WorkflowNodeType,
    /** 节点标题（展示用） */
    val title: String = "",
    /** AI 生成/判断用的提示词 */
    val prompt: String = "",
    /** 输出变量名（AI_GENERATE 节点用） */
    val outputVar: String = "",
    /** 动作节点的消息文案（可包含 {{变量}}） */
    val message: String = "",
    /** 动作类型：companion / notification */
    val actionType: String = "companion",
    /** 条件表达式（占位，目前用 simple keyword 匹配） */
    val conditionExpr: String = ""
)

/** 工作流边 */
@Serializable
data class WorkflowEdge(
    val id: String,
    val source: String,
    val target: String,
    /** 条件分支标签，CONDITION 节点出边用 */
    val label: String = ""
)

/** 执行统计 */
@Serializable
data class AutomationStats(
    val fireCount: Int = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val lastFiredAt: Long = 0L,
    /**
     * 上次「计划触发」已处理的计划时刻（= 当时 triggerAtMillis）。
     * 与 [lastFiredAt]（含手动执行）解耦：shouldFire 幂等判定用本字段，
     * 手动执行只更新 [lastFiredAt]，不影响定时判定；否则手动执行一次后
     * `lastFiredAt < triggerAtMillis` 永假，DAILY/WEEKLY 调度永久静默失效。
     */
    val lastScheduledFiredAt: Long = 0L,
    /** 上次执行结果：success / failure */
    val lastResult: String? = null,
    /** 上次执行输出的消息摘要（展示用，取前 40 字） */
    val lastMessage: String = ""
)

@Serializable
data class Automation(
    val id: String,
    val title: String,
    val companionId: Long,
    val type: AutomationType,
    val triggerAtMillis: Long,
    val hourOfDay: Int,
    val minuteOfHour: Int,
    val dayOfWeek: Int? = null,
    /** 普通定时提醒的固定文案；工作流中可缺省 */
    val message: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    /** 是否是工作流（截图中的节点流程图模式） */
    val isWorkflow: Boolean = false,
    /** 工作流节点 */
    val nodes: List<WorkflowNode> = emptyList(),
    /** 工作流连线 */
    val edges: List<WorkflowEdge> = emptyList(),
    /** 执行统计 */
    val stats: AutomationStats = AutomationStats()
)
