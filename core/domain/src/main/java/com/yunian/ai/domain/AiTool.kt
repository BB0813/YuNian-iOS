package com.yunian.ai.domain

import java.util.concurrent.ConcurrentHashMap

interface AiTool {

    val name: String

    val description: String

    val parametersJsonSchema: String

    /**
     * 工具归属的工具集（对齐 Hermes TOOLSETS 分组）。
     * 如 `setOf("chat")` / `setOf("memory")` / `setOf("commerce")`。
     * 空 = 归属「通用」工具集（domain）。装配 Agent 工具列表时按工具集展开/过滤。
     */
    val toolsets: Set<String> get() = emptySet()

    /**
     * 本机敏感工具标记：`true` 表示该工具只允许在予念 App 内的本机会话中使用。
     *
     * 这类工具暴露的是**手机主人本人的本机数据**（如个人资料）。一旦它出现在 QQ / 微信
     * 等外部桥接会话的工具列表中，远端联系人就能借模型之手读到本机主人的私人信息。
     *
     * 默认必须是 `false`（普通工具）；[ToolRegistry] 的所有枚举 / 序列化入口默认
     * **排除**本机敏感工具，只有宿主显式声明 `includeAppLocal = true` 时才可见，
     * 执行侧还有第二道闸（AgentToolHost）。
     *
     * 授权只能来自宿主代码的不可变上下文，禁止依据模型给出的参数或 context JSON 判定。
     */
    val appLocalOnly: Boolean get() = false

    /**
     * 可用性检查（对齐 Hermes check_fn）：按当前环境实时判断工具是否可用。
     * 默认恒可用；实现类可覆盖（如检测网络 / API 配置 / 权限 / 开关）。
     * [ToolRegistry.availableTools] 会按 30s TTL 缓存结果，60s 内容忍抖动（flake）。
     */
    fun isAvailable(): Boolean = true

    suspend fun execute(argumentsJson: String): String

    fun systemPrompt(): String = ""

    val requiresConfirmation: Boolean get() = false

    fun summarizeArguments(argumentsJson: String): String = argumentsJson.take(120)
}

/**
 * 将工具列表序列化为 OpenAI tools 数组 JSON 字符串。
 *
 * 格式：[{"type":"function","function":{"name","description","parameters":{...}}}]
 *
 * 与 [ToolRegistry.toolDefinitionsJson] 的区别：这里用**调用方显式传入的**工具列表，
 * 而非全局注册池——标准 Agent 模式下每个会话注入自己的工具集（如气泡连发只注入 emit_bubble），
 * 避免全局注册表污染其他对话。
 */
fun List<AiTool>.toToolDefinitionsJson(): String {
    if (isEmpty()) return "[]"
    val sb = StringBuilder("[")
    forEachIndexed { index, tool ->
        if (index > 0) sb.append(",")
        sb.append("{\"type\":\"function\",\"function\":{")
        sb.append("\"name\":\"").append(tool.name.escapeJson()).append("\",")
        sb.append("\"description\":\"").append(tool.description.escapeJson()).append("\",")
        sb.append("\"parameters\":").append(tool.parametersJsonSchema)
        sb.append("}}")
    }
    sb.append("]")
    return sb.toString()
}

internal fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

object ToolRegistry {
    private val tools = ConcurrentHashMap<String, AiTool>()

    /** 可用性快照（check_fn 结果 + 检查时间戳） */
    private data class Availability(val available: Boolean, val checkedAt: Long)

    /** check_fn 结果 TTL（30s）：TTL 内直接用缓存，不重复求值 */
    private const val AVAILABILITY_TTL_MS = 30_000L

    /** flake 容忍窗口（60s）：上次可用、本次求值不可用时，窗口内容忍为可用 */
    private const val FLAKE_GRACE_MS = 60_000L

    private val availabilityCache = ConcurrentHashMap<String, Availability>()

    /** 注册工具（同名覆盖） */
    fun register(tool: AiTool) {
        tools[tool.name] = tool
        availabilityCache.remove(tool.name)
    }

    /** 注销工具 */
    fun unregister(name: String) {
        tools.remove(name)
        availabilityCache.remove(name)
    }

    /**
     * 原始单点查询：**不做渠道校验**（执行侧必须由宿主授权门判定 `AiTool.appLocalOnly`，
     * 见 `AgentToolHost`）。面向模型的列表装配请用 [availableTools] / [toolDefinitionsJson]。
     */
    fun get(name: String): AiTool? = tools[name]

    /**
     * 注册池快照：默认**排除**本机敏感工具（未知渠道 = 不可见），
     * 只有确定的本机 App 会话才可传 `includeAppLocal = true`。
     */
    fun all(includeAppLocal: Boolean = false): List<AiTool> =
        tools.values.filter { includeAppLocal || !it.appLocalOnly }

    /** 仅表示注册池非空（存活探针），不反映渠道可见性；列表装配请用 [availableTools]。 */
    fun isNotEmpty(): Boolean = tools.isNotEmpty()

    /**
     * 当前可用的工具列表（对齐 Hermes check_fn + memo 缓存）。
     *
     * 对每个工具求值 [AiTool.isAvailable]，结果按 30s TTL 缓存；
     * 上次可用、本次不可用且距上次检查 < 60s 时容忍为可用（避免瞬时抖动
     * 把工具从模型工具列表里抖掉，破坏 prompt 缓存稳定性）。
     *
     * 装配 Agent 工具列表时应使用本方法而非 [all]。
     *
     * 渠道隔离：`includeAppLocal = false`（默认 / 未知渠道）时本机敏感工具根本不参与枚举，
     * 也不求值其 [AiTool.isAvailable]——**可用性 ≠ 授权**，本机工具的可见性不经过
     * [availabilityCache]，因此不存在「授权被可用性缓存二次确认」的路径。
     */
    fun availableTools(includeAppLocal: Boolean = false): List<AiTool> {
        val now = System.currentTimeMillis()
        return tools.values.filter { tool ->
            if (tool.appLocalOnly && !includeAppLocal) {
                // 本机敏感工具：非本机会话直接不参与枚举（模型连名字都看不到）。
                // 注意不写 availabilityCache —— 授权结果绝不能混进可用性缓存。
                return@filter false
            }
            val cached = availabilityCache[tool.name]
            when {
                cached == null -> {
                    val fresh = tool.isAvailable()
                    availabilityCache[tool.name] = Availability(fresh, now)
                    fresh
                }
                now - cached.checkedAt < AVAILABILITY_TTL_MS -> cached.available
                else -> {
                    val fresh = tool.isAvailable()
                    val final = if (!fresh && cached.available && now - cached.checkedAt < FLAKE_GRACE_MS) {
                        true // flake 容忍：窗口内保持可用
                    } else {
                        fresh
                    }
                    availabilityCache[tool.name] = Availability(final, now)
                    final
                }
            }
        }
    }

    /** 清空可用性缓存（注册/配置变化后主动失效用） */
    fun invalidateAvailabilityCache() {
        availabilityCache.clear()
    }

    /**
     * 查询归属指定工具集（TOOLSETS）的工具（对齐 Hermes toolsets 分组）。
     * 空工具集名 = 通用集：返回未声明任何工具集的工具。
     */
    fun toolsInToolset(toolset: String, includeAppLocal: Boolean = false): List<AiTool> {
        val visible = tools.values.filter { includeAppLocal || !it.appLocalOnly }
        return if (toolset.isBlank()) {
            visible.filter { it.toolsets.isEmpty() }
        } else {
            visible.filter { toolset in it.toolsets }
        }
    }

    /** 当前注册的全部工具集名（不含空通用集）；默认不含本机敏感工具的工具集 */
    fun toolsetNames(includeAppLocal: Boolean = false): Set<String> =
        tools.values.filter { includeAppLocal || !it.appLocalOnly }.flatMap { it.toolsets }.toSet()

    /**
     * 全部工具的 OpenAI tools 定义 JSON。
     * **默认排除**本机敏感工具；只有确定的本机 App 会话才可传 `includeAppLocal = true`。
     */
    fun toolDefinitionsJson(includeAppLocal: Boolean = false): String =
        tools.values.filter { includeAppLocal || !it.appLocalOnly }.toToolDefinitionsJson()

    fun clear() {
        tools.clear()
        availabilityCache.clear()
    }

    fun systemPromptSection(includeAppLocal: Boolean = false): String {
        val parts = tools.values
            .filter { includeAppLocal || !it.appLocalOnly }
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
    fun agentDirectiveSection(includeAppLocal: Boolean = false): String {
        val visible = tools.values.filter { includeAppLocal || !it.appLocalOnly }
        if (visible.isEmpty()) return ""
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
}
