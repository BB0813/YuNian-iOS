package com.yunian.ai.domain

import java.util.concurrent.ConcurrentHashMap

interface AiTool {

    val name: String

    val description: String

    val parametersJsonSchema: String

    suspend fun execute(argumentsJson: String): String

    fun systemPrompt(): String = ""

    val requiresConfirmation: Boolean get() = false

    fun summarizeArguments(argumentsJson: String): String = argumentsJson.take(120)
}

object ToolRegistry {
    private val tools = ConcurrentHashMap<String, AiTool>()

    fun register(tool: AiTool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun get(name: String): AiTool? = tools[name]

    fun all(): List<AiTool> = tools.values.toList()

    fun isNotEmpty(): Boolean = tools.isNotEmpty()

    fun toolDefinitionsJson(): String {
        if (tools.isEmpty()) return "[]"
        val sb = StringBuilder("[")
        tools.values.forEachIndexed { index, tool ->
            if (index > 0) sb.append(",")
            sb.append("{\"type\":\"function\",\"function\":{")
            sb.append("\"name\":\"").append(escapeJson(tool.name)).append("\",")
            sb.append("\"description\":\"").append(escapeJson(tool.description)).append("\",")
            sb.append("\"parameters\":").append(tool.parametersJsonSchema)
            sb.append("}}")
        }
        sb.append("]")
        return sb.toString()
    }

    fun clear() {
        tools.clear()
    }

    fun systemPromptSection(): String {
        val parts = tools.values
            .map { it.systemPrompt() }
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        return parts.joinToString("\n\n")
    }

    /**
     * Agent 行动指令 + 工具速览（OpenMinis 模式）：
     * 明确告知模型"你具备执行能力，任务匹配工具时必须直接调用"，
     * 解决聊天型人设下模型不主动调用、口头询问甚至拒绝的问题。
     */
    fun agentDirectiveSection(): String {
        if (tools.isEmpty()) return ""
        return buildString {
            appendLine("═══ 执行能力（重要）═══")
            appendLine("你不只是聊天对象——你拥有下列可实际执行的工具，可以直接操作手机与完成任务：")
            appendLine("查记忆/回忆、联网搜索、搜索并安装技能商店里的技能、创建与执行自动化、打开应用与网页、读写剪贴板、预填闹钟、发通知、查电量、控制手机屏幕（读屏/点击/滑动）、检查并使用 Shizuku 特权等。")
            appendLine()
            appendLine("行动准则：")
            appendLine("1. 用户的请求只要能用某个工具完成（哪怕只沾一点边），必须立即直接调用工具，禁止只用文字描述做法、禁止反问『需要我帮你…吗』、禁止以『我只是聊天助手』等理由拒绝。")
            appendLine("2. 先行动，后汇报：调用工具拿到结果后，再用一两句自然的话把结果讲出来。")
            appendLine("3. 多个工具能完成时选最直接的一个；敏感操作（点击屏幕、滑动）系统会先征求用户确认，你只管发起。")
            appendLine("4. 安装技能先试国内技能商店：调用 skillhub_search 用中/英文关键词搜索 → 从结果里挑最匹配的一条，把它的 slug 与 version 传给 skill_install 安装。")
            appendLine("   **若 skillhub_search 返回『商店暂时连不上』，不要反复重试商店**，立刻改走 GitHub 路线：用 web_fetch 访问 https://api.github.com/search/repositories?q=<关键词> 找仓库，从返回 JSON 读取 full_name 与 default_branch，用它们拼出 https://raw.githubusercontent.com/<full_name>/<default_branch>/SKILL.md 交给 skill_install（raw 链接会自动尝试 jsDelivr 镜像）。严禁凭空编造 URL；raw 404 时改用 https://api.github.com/repos/<full_name>/contents/ 列出真实文件名，不要反复猜。任何情况下都不要向用户索要链接。")
            appendLine("5. 一次只做一件事：需要多个技能时逐个安装，不要把十几二十个工具调用堆在同一轮里，否则会超出本轮时间预算而被中断。")
            appendLine("6. 确实没有任何工具适用的纯聊天场景，才正常聊天回复。")
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
