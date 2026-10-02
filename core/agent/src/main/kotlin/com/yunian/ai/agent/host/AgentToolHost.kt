package com.yunian.ai.agent.host

import android.content.Context
import android.util.Log
import com.yunian.ai.agent.AgentFacade
import com.yunian.ai.agent.audit.ToolCallRecord
import com.yunian.ai.agent.sticker.StickerPreferenceFacade
import com.yunian.ai.agent.uniffi.ToolHost
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.domain.plugin.PluginEventPublisher
import com.yunian.ai.domain.plugin.PluginHost
import com.yunian.ai.domain.plugin.ToolFinishStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Rust `ToolHost` 回调实现：Agent 回合中会话级工具的执行副作用桥。
 *
 * Rust 侧契约（`agent-native/src/agent.rs`）：
 * - 内置聊天工具（emit_segmented / send_sticker / emit_bubble）在 Rust 内执行，**不回调本类**；
 * - 会话级工具（request.tools 传入的）经本回调执行，`contextJson` 含
 *   `{companion_id, group_id, recent_history_summary}`。
 *
 * 职责边界（对齐「决策在 Rust、Kotlin 纯 IO」原则）：
 * - 记忆工具（recall_memory / save_memory / consolidate_memory）：分派到
 *   [AgentFacade.executeMemoryTool]（Rust `MemorySelector` 决策，副作用经 `MemoryStore` 回调落库）；
 * - 其余会话工具：查 [ToolRegistry]（feature 层注册的全局领域工具）分派执行；
 * - 未注册工具：返回错误文本（Rust 会将其作为工具结果回灌模型）。
 *
 * 注意：商业/支付类工具若需用户确认，应在 feature 层接入确认策略后再放行到 ToolRegistry，
 * 本类不做确认决策（决策在 Rust `ConfirmPolicy` / feature 层）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentToolHost(
    context: Context,
    /**
     * 本机敏感工具通道开关（**不可变**，只能由宿主在构造会话时传入）：
     * - `false`（默认）= QQ / 微信等外部桥接、无人值守 Worker、委派 / 评测等一切未知渠道：
     *   `AiTool.appLocalOnly` 工具**拒绝执行**（稳定 refusal 文本，不回显任何资料内容）；
     * - `true` = 只由 **App 内单聊 / 群聊**的装配点显式打开。
     *
     * 授权来源仅此一个构造参数：模型侧伪造 `argumentsJson` / `contextJson`（哪怕写着
     * `"channel":"app"`）都不影响判定；不使用 ThreadLocal，也不使用可变全局开关。
     */
    private val allowAppLocalTools: Boolean = false,
    /** 仅用于关联订阅者自己的会话；随机值不包含 companion/user 信息。 */
    val lifecycleStreamId: String = UUID.randomUUID().toString(),
    /** 默认取生产 Cordis 宿主；null（尚未装配/无订阅端）时工具执行照常。 */
    private val eventPublisher: PluginEventPublisher? =
        ServiceRegistry.get(PluginHost::class.java) as? PluginEventPublisher,
) : ToolHost {

    companion object {
        private const val TAG = "AgentToolHost"

        /** 多 Agent 编排：委派工具（主回合发起子任务）。 */
        private const val DELEGATE_TASK_TOOL = "delegate_task"
        /** 多 Agent 编排：汇聚工具（主回合查询委派结果）。 */
        private const val FETCH_DELEGATION_TOOL = "fetch_delegation_result"

        /**
         * 工具执行超时（P1-5）：单个会话级工具执行超过此时长即判定失败并回灌模型。
         * 注意：超时后底层任务仍会在池中跑完（无法安全取消），但结果不再回灌。
         */
        private const val TOOL_TIMEOUT_MS = 20_000L

        /** 工具执行线程池大小（有界，避免并发工具打爆 IO/主线程）。 */
        private const val TOOL_POOL_SIZE = 2

        /**
         * 应用级工具执行作用域（受限并行 [TOOL_POOL_SIZE]）：不随回合/宿主实例创建，避免线程池泄漏；
         * 每次调用是独立子协程，超时可真正取消——挂起型工具在取消点立即中止，
         * 不再出现「回灌超时错误、后台却继续写副作用」。
         */
        private val toolScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO.limitedParallelism(TOOL_POOL_SIZE)
        )

        /** Rust `MemorySelector` 注册的记忆工具名集合。 */
        private val MEMORY_TOOLS = setOf("recall_memory", "save_memory", "consolidate_memory")

        /** 技能渐进式披露 L2 工具：按需加载技能完整正文。 */
        private const val LOAD_SKILL_TOOL = "load_skill"

        /** Rust `builtin_send_sticker` 内部回调：按标签预选实际发送的表情包。 */
        private const val STICKER_PICK_TOOL = "sticker_pick"
    }

    private val appContext: Context = context.applicationContext
    private val lifecycleReporter = ToolLifecycleReporter(eventPublisher, lifecycleStreamId)

    /**
     * 本回合工具调用明细（线程安全，Rust 回调线程写入；回合结束后由调用方读取
     * 并传入 AgentFacade.recordDispatchLog 落调度日志）。
     */
    private val toolCalls = CopyOnWriteArrayList<ToolCallRecord>()

    /** 回合结束后读取全部工具调用明细（时间正序）。 */
    fun collectedToolCalls(): List<ToolCallRecord> = toolCalls.toList()

    override fun execute(toolName: String, argumentsJson: String, contextJson: String): String {
        val startedAt = System.currentTimeMillis()
        Log.i(TAG, "tool call: name=$toolName args=$argumentsJson ctx=$contextJson")
        var ok = true
        var result: String? = null
        var cancellation: CancellationException? = null
        withToolLifecycle(lifecycleReporter, toolName) { markFinished ->
            try {
                val task = toolScope.async { executeToolSuspending(toolName, argumentsJson, contextJson) }
                result = try {
                    // Rust 回调线程同步等待；超时则取消子协程（挂起型工具在取消点真正中止）
                    runBlocking { withTimeout(TOOL_TIMEOUT_MS) { task.await() } }
                } catch (timeout: TimeoutCancellationException) {
                    task.cancel(CancellationException("tool timeout: $toolName"))
                    ok = false
                    markFinished(ToolFinishStatus.TIMED_OUT)
                    Log.w(TAG, "tool timeout: $toolName >${TOOL_TIMEOUT_MS / 1000}s (cancelled)")
                    "错误：工具 $toolName 执行超时（超过 ${TOOL_TIMEOUT_MS / 1000} 秒）已请求取消，结果未知——请先核实状态，不要重复执行有副作用的操作"
                }
            } catch (cancelled: CancellationException) {
                ok = false
                markFinished(ToolFinishStatus.CANCELLED)
                cancellation = cancelled
            } catch (t: Throwable) {
                ok = false
                markFinished(ToolFinishStatus.FAILED)
                Log.e(TAG, "execute $toolName failed", t)
                result = "错误：工具 $toolName 执行失败：${t.message ?: t.javaClass.simpleName}"
            }
        }
        cancellation?.let { throw it }
        val finalResult = requireNotNull(result)
        val elapsed = System.currentTimeMillis() - startedAt
        // 本机敏感工具结果（本机用户资料）不进 ToolCallRecord / 日志明文，统一用固定占位替换；
        // 其他工具的日志行为保持不变（result 截断 120 字符）。
        toolCalls.add(
            ToolCallRecord(
                name = toolName,
                args = argumentsJson,
                result = AppLocalToolGate.resultForRecord(toolName, finalResult),
                elapsedMs = elapsed,
                ok = ok,
            )
        )
        Log.i(TAG, "tool done: name=$toolName elapsed=${elapsed}ms ok=$ok result=${AppLocalToolGate.resultForLog(toolName, finalResult)}")
        return finalResult
    }


    /**
     * 实际工具执行（在 [toolScope] 受限并行调度器上运行）：记忆工具 / load_skill /
     * sticker_pick / ToolRegistry 全局工具分派。
     */
    private suspend fun executeToolSuspending(toolName: String, argumentsJson: String, contextJson: String): String =
        when {
            toolName in MEMORY_TOOLS ->
                withContext(Dispatchers.IO) {
                    AgentFacade.executeMemoryTool(appContext, toolName, argumentsJson, contextJson)
                }

            toolName == LOAD_SKILL_TOOL -> executeLoadSkill(argumentsJson, contextJson)

            toolName == STICKER_PICK_TOOL -> executeStickerPick(argumentsJson, contextJson)

            toolName == DELEGATE_TASK_TOOL -> executeDelegateTask(argumentsJson, contextJson)

            toolName == FETCH_DELEGATION_TOOL -> executeFetchDelegation(argumentsJson)

            // ToolRegistry 全局工具：唯一分派点，必须经过本机敏感工具授权门。
            // 注意 contextJson **刻意不传**：它是模型可见的上下文，不能作为授权依据。
            else -> dispatchRegistryTool(toolName, argumentsJson, allowAppLocalTools)
        }

    /**
     * delegate_task：创建委派（PENDING）。
     * argumentsJson 契约：`{"role": "analyst|helper", "prompt": "任务说明"}`；
     * contextJson 含 companionId（"companion_id"）。
     */
    private suspend fun executeDelegateTask(argumentsJson: String, contextJson: String): String {
        val args = try {
            org.json.JSONObject(argumentsJson)
        } catch (e: Exception) {
            return "错误：delegate_task 参数解析失败（需要 JSON {\"role\": \"...\", \"prompt\": \"...\"}）"
        }
        val role = args.optString("role", "helper")
        val prompt = args.optString("prompt", "")
        if (prompt.isBlank()) return "错误：delegate_task 缺少 prompt（任务说明）"
        val companionId = try {
            org.json.JSONObject(contextJson).optLong("companion_id", 0L).takeIf { it > 0L }
        } catch (e: Exception) {
            null
        }
        val coordinator = com.yunian.ai.domain.ServiceRegistry.get(com.yunian.ai.domain.delegation.DelegationCoordinator::class.java)
        if (coordinator == null) return "错误：委派协调器未初始化"
        val record = coordinator.create(role, prompt, companionId)
        return org.json.JSONObject().apply {
            put("delegation_id", record.id)
            put("role", role)
            put("status", "PENDING")
            put("message", "委派已创建，稍后可用 fetch_delegation_result 查询结果")
        }.toString()
    }

    /**
     * fetch_delegation_result：查询委派结果。
     * argumentsJson 契约：`{"delegation_id": 1}`。
     */
    private suspend fun executeFetchDelegation(argumentsJson: String): String {
        val args = try {
            org.json.JSONObject(argumentsJson)
        } catch (e: Exception) {
            return "错误：fetch_delegation_result 参数解析失败（需要 JSON {\"delegation_id\": 1}）"
        }
        val id = args.optLong("delegation_id", 0L)
        if (id <= 0L) return "错误：缺少 delegation_id"
        val coordinator = com.yunian.ai.domain.ServiceRegistry.get(com.yunian.ai.domain.delegation.DelegationCoordinator::class.java)
        if (coordinator == null) return "错误：委派协调器未初始化"
        val record = coordinator.get(id) ?: return "错误：委派记录不存在（id=$id）"
        return org.json.JSONObject().apply {
            put("delegation_id", record.id)
            put("role", record.role)
            put("status", record.status.name)
            put("result", record.result)
            put("error", record.error)
        }.toString()
    }

    /**
     * load_skill 工具：渐进式披露 L2——按需读取技能完整正文回灌模型。
     *
     * argumentsJson 契约：`{"skill_id": "..."}`，可选 `companion_id`。
     * 返回技能正文；技能不存在/已禁用返回明确错误文本（模型会收到工具结果并调整）。
     */
    private suspend fun executeLoadSkill(argumentsJson: String, contextJson: String): String {
        val args = try {
            org.json.JSONObject(argumentsJson)
        } catch (e: Exception) {
            return "错误：load_skill 参数解析失败（需要 JSON {\"skill_id\": \"...\"}）"
        }
        val skillId = args.optString("skill_id").ifBlank { return "错误：load_skill 缺少 skill_id 参数" }
        // contextJson 契约为 {companion_id, group_id, recent_history_summary}
        val companionId = try {
            val ctx = org.json.JSONObject(contextJson)
            if (ctx.has("companion_id") && !ctx.isNull("companion_id")) {
                ctx.getLong("companion_id")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
        val content = withContext(Dispatchers.IO) {
            AgentFacade.loadSkillContent(appContext, skillId, companionId)
        }
        if (content == null) {
            return "错误：技能 $skillId 不存在或已禁用"
        }
        Log.i(TAG, "load_skill ok: skill=$skillId len=${content.length}")
        return content
    }

    /**
     * sticker_pick 工具：Rust `builtin_send_sticker` 命中后内部回调，预选实际发送的表情包。
     *
     * argumentsJson 契约：`{"tags":["开心","抱抱"]}`。
     * 流程：偏好引擎 OR 采样 1 张 → DB 取条目 → 返回 JSON：
     * `{"ok":true,"entryId":123,"fileName":"sticker_xxx.png","description":"开心"}`。
     * 无候选 / 参数非法 / 异常 → `{"ok":false}`（Rust 回退事件透传，落地侧兜底采样）。
     *
     * 注意：仅预选不记录使用（recordUsage 由落地成功时执行，避免重复计数）。
     */
    private suspend fun executeStickerPick(argumentsJson: String, contextJson: String): String {
        val args = try {
            org.json.JSONObject(argumentsJson)
        } catch (e: Exception) {
            return "{\"ok\":false}"
        }
        val tags = args.optJSONArray("tags")
            ?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).trim().takeIf { it.isNotEmpty() }
                }
            }
            ?: emptyList()
        if (tags.isEmpty()) return "{\"ok\":false}"
        return try {
            val entry = StickerPreferenceFacade.sampleCandidates(appContext, 1, tags)
                .firstOrNull()
                ?.let { AppDatabase.getDatabase(appContext).stickerEntryDao().getById(it) }
            if (entry == null) {
                "{\"ok\":false}"
            } else {
                val out = org.json.JSONObject()
                out.put("ok", true)
                out.put("entryId", entry.id)
                out.put("fileName", entry.fileName.orEmpty())
                out.put("description", entry.description.orEmpty())
                out.toString()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.e(TAG, "sticker_pick failed", t)
            "{\"ok\":false}"
        }
    }
}

/**
 * 本机敏感工具授权门（执行侧强制，渠道隔离的第二道闸）。
 *
 * 与 [ToolRegistry] 的可见性过滤彼此独立：注册表侧解决「模型看不到」，
 * 本门解决「即使被叫到名字也执行不了」——伪造工具名、伪造参数、伪造上下文，
 * 或某个装配点漏传 `includeAppLocal`，都拿不到 `AiTool.appLocalOnly` 工具的结果。
 *
 * 纯函数、无状态：判定输入只有「注册表里该工具自身的静态归属」与「宿主构造时传入的
 * 不可变开关」，因此可在 JVM 单测中直接验证；刻意不接收 contextJson，模型的上下文
 * 自述渠道一律不作为授权依据。
 */
internal object AppLocalToolGate {

    /** 本机敏感工具结果在 ToolCallRecord / Log 中的固定占位（不含任何资料内容）。 */
    const val REDACTED_RESULT = "[本机敏感工具结果已脱敏]"

    /** 该工具名是否指向已注册的本机敏感工具（未注册 / 普通工具 = false）。 */
    fun isAppLocalTool(toolName: String): Boolean =
        ToolRegistry.get(toolName)?.appLocalOnly == true

    /** 稳定拒绝文本：不回显参数、不回显资料内容、不泄漏工具内部信息。 */
    fun rejectionText(toolName: String): String = "错误：工具 $toolName 在本会话不可用"

    /** ToolCallRecord.result：本机敏感工具一律用 [REDACTED_RESULT] 占位。 */
    fun resultForRecord(toolName: String, result: String): String =
        if (isAppLocalTool(toolName)) REDACTED_RESULT else result

    /** 日志 result 字段：本机敏感工具脱敏；其余保持既有截断行为（120 字符）。 */
    fun resultForLog(toolName: String, result: String): String =
        if (isAppLocalTool(toolName)) REDACTED_RESULT else result.take(120)
}

/**
 * [ToolRegistry] 全局工具的**唯一**分派入口（含执行侧授权门）。
 *
 * - `allowAppLocalTools = false`（默认 / 未知渠道）：本机敏感工具直接返回
 *   [AppLocalToolGate.rejectionText]，**不调用其 execute**，因此不可能回显资料内容；
 * - `allowAppLocalTools = true`：App 内本机会话，正常执行；
 * - 未注册工具：保持既有「错误：未注册的工具 X」文本。
 *
 * 刻意**不接受** `contextJson` 参数（授权只能来自宿主构造标志）。
 */
internal suspend fun dispatchRegistryTool(
    toolName: String,
    argumentsJson: String,
    allowAppLocalTools: Boolean,
): String {
    val tool = ToolRegistry.get(toolName) ?: return "错误：未注册的工具 $toolName"
    if (!allowAppLocalTools && tool.appLocalOnly) return AppLocalToolGate.rejectionText(toolName)
    return tool.execute(argumentsJson)
}
