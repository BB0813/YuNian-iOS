package com.yunian.ai.feature.skills.tools

import com.yunian.ai.domain.AiTool
import com.yunian.ai.feature.skills.accessibility.AccessibilityBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * AI 控制手机工具集（无障碍，Cordis 插件版）。
 * 读屏/导航类低风险直接执行；触屏操作类（点击/滑动/按文本点击/输入文本）需用户确认后执行。
 * 服务未开启时**执行类工具（8 个）**返回开启指引；[AccessibilityStatusTool] 是唯一例外——
 * 它如实报告未开启状态（`{"ok":true,"enabled":false,...}`），不套就绪门控。
 *
 * ## 为什么是「迁移替换」而不是「新增一套」
 *
 * 迁移前这 7 个既有工具由全局函数 `registerAccessibilityTools()` 在 Application 启动时
 * **无条件**注册，并直接调用 [com.yunian.ai.feature.skills.accessibility.YuNianAccessibilityService]
 * 的静态单例。两个问题：
 * 1. 用户无法关闭「AI 控制手机」——它硬编码在启动路径里，不是可插拔能力；
 * 2. 工具层与 Android 无障碍实现直接耦合，无法在 JVM 单测里验证契约。
 *
 * 现在改由 Cordis 插件 `ui.assists`（[com.yunian.ai.feature.skills.plugin.AssistsUiPlugin]）
 * 提供这 9 个工具，构造函数注入 [AccessibilityBridge] 接缝：
 * - 插件装载 → 9 个工具进注册表；插件卸载 → 9 个工具立即消失（Cordis「卸载不留鸡毛」）；
 * - 工具层零 Android / 零 assists 依赖，可以纯 JVM 单测。
 *
 * **对外契约一个字不改**：既有 7 个工具的名字 / description / parametersJsonSchema /
 * systemPrompt() / 错误文案 / requiresConfirmation / toolsets（空 = 通用集）与迁移前逐字一致，
 * 只有「谁能提供它」变了。全局函数 `registerAccessibilityTools()` **已删除**。
 *
 * ## 最终 9 个工具与安全裁定
 *
 * | 工具 | requiresConfirmation | appLocalOnly |
 * |---|---|---|
 * | accessibility_status / screen_read / press_back / go_home | false | false |
 * | screen_tap / screen_swipe / screen_click_text | true | false |
 * | screen_input_text | true | **true** |
 * | screen_dump_ui | false | **true** |
 *
 * 命名上**只有一个 `screen_` 家族**：读屏是 [ScreenReadTool]（扁平文本），
 * 结构化节点树是 [ScreenDumpUiTool]，不引入 `ui_*` 之类的新前缀。
 *
 * 安全裁定（本文件是这些工具的唯一执行面）：
 * - 既有 7 个工具保持 `appLocalOnly = false`——它们已经存在于外部桥接会话的工具列表里，
 *   收窄可见性属于**行为变更**，不在本次迁移范围内；
 * - 新增的 [ScreenInputTextTool] 与 [ScreenDumpUiTool] 标记 `appLocalOnly = true`：
 *   `input_text` 会替用户往输入框里打字（可能冒充用户输入消息），
 *   `dump_ui` 会吐出**结构化**的整窗节点树（比扁平文本泄漏面大得多，
 *   含包名 / viewId / 坐标 / 是否密码框），因此只允许在予念 App 内的本机会话中使用；
 *   外部桥接会话连工具名都看不到（[com.yunian.ai.domain.ToolRegistry.availableTools] 第一道闸）。
 */
private val a11yJson = Json { ignoreUnknownKeys = true }

/** 参数 JSON 解析；非法 JSON 返回 null（调用方回 "Invalid arguments"）。 */
private fun a11yArgs(argumentsJson: String) =
    runCatching { a11yJson.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()

private fun a11yError(message: String): String = buildJsonObject {
    put("ok", false)
    put("error", message)
}.toString()

private fun a11yOk(vararg pairs: Pair<String, Any?>): String = buildJsonObject {
    put("ok", true)
    pairs.forEach { (k, v) -> when (v) {
        is String? -> put(k, v)
        is Int? -> put(k, v)
        is Boolean? -> put(k, v)
    } }
}.toString()

/** 服务未开启时**其余 8 个工具**（除 [AccessibilityStatusTool] 外的全部执行类工具）统一返回的引导文案。 */
private const val SERVICE_DISABLED_HINT =
    "无障碍服务未开启：请在系统设置 → 无障碍 → 已下载的应用 → 「予念助手控制服务」中开启后重试"

/**
 * 无障碍通道是否就绪；未就绪时返回引导错误，就绪时把 [AccessibilityBridge] 作为 receiver
 * 交给 [onReady]（工具内统一用 `withBridge { tap(...) }` 的写法调用接缝）。
 *
 * 仅用于**执行类**工具：它们做不到事就必须给用户开启指引。[AccessibilityStatusTool]
 * 是唯一的例外（它的职责正是如实报告「没开」），故**不套本门控**。
 */
private inline fun AccessibilityBridge.withBridge(onReady: AccessibilityBridge.() -> String): String {
    if (!isReady()) return a11yError(SERVICE_DISABLED_HINT)
    return onReady()
}

/**
 * 查询无障碍服务状态。
 *
 * **9 个工具里唯一不做就绪门控的工具**（唯一不套 [withBridge] 的一个）：它本身就是「查状态」，
 * 服务关着时返回 `{"ok":true,"enabled":false,"hint":"未开启，需用户在系统设置中授权"}`
 * ——`ok` 仍是 true，因为**如实报告未开启状态就是它的职责**，不是失败。
 * 其余 8 个是执行类（读屏 / 导航 / 触屏 / 节点树），未就绪时必须给开启指引，因而套 [withBridge]。
 */
class AccessibilityStatusTool(private val bridge: AccessibilityBridge) : AiTool {
    override val name = "accessibility_status"
    override val description = "查询 AI 控制手机的无障碍服务是否已开启（无参数）。"
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""
    override fun systemPrompt() = "accessibility_status: 查询手机控制通道状态。无参数。"
    override val requiresConfirmation = false

    override suspend fun execute(@Suppress("UNUSED_PARAMETER") argumentsJson: String): String {
        val ready = bridge.isReady()
        return a11yOk(
            "enabled" to ready,
            "hint" to if (ready) "手机控制通道可用" else "未开启，需用户在系统设置中授权",
        )
    }
}

/** 读取当前屏幕可见文本 */
class ScreenReadTool(private val bridge: AccessibilityBridge) : AiTool {
    override val name = "screen_read"
    override val description = "读取当前手机屏幕上的可见文本（无参数），用于理解当前界面内容。"
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""
    override fun systemPrompt() = "screen_read: 读取当前屏幕文本。无参数。先用它了解界面再操作。"
    override val requiresConfirmation = false

    override suspend fun execute(@Suppress("UNUSED_PARAMETER") argumentsJson: String): String =
        bridge.withBridge {
            val text = readScreenText()
            if (text.isBlank()) a11yError("未能读取屏幕内容（当前界面可能不支持读取）")
            else a11yOk("screen" to text)
        }
}

/** 按返回键 */
class PressBackTool(private val bridge: AccessibilityBridge) : AiTool {
    override val name = "press_back"
    override val description = "执行系统返回（无参数）。"
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""
    override fun systemPrompt() = "press_back: 按返回键。无参数。"
    override val requiresConfirmation = false

    override suspend fun execute(@Suppress("UNUSED_PARAMETER") argumentsJson: String): String =
        bridge.withBridge {
            if (back()) {
                a11yOk("action" to "back")
            } else a11yError("返回失败")
        }
}

/** 回到主屏幕 */
class GoHomeTool(private val bridge: AccessibilityBridge) : AiTool {
    override val name = "go_home"
    override val description = "回到手机主屏幕（无参数）。"
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""
    override fun systemPrompt() = "go_home: 回到主屏幕。无参数。"
    override val requiresConfirmation = false

    override suspend fun execute(@Suppress("UNUSED_PARAMETER") argumentsJson: String): String =
        bridge.withBridge {
            if (home()) {
                a11yOk("action" to "home")
            } else a11yError("返回主屏失败")
        }
}

/** 点击屏幕坐标（需用户确认） */
class ScreenTapTool(private val bridge: AccessibilityBridge) : AiTool {
    override val name = "screen_tap"
    override val description = "点击手机屏幕坐标。参数 {x: int, y: int}。先用 screen_read 了解界面再操作。"
    override val parametersJsonSchema = """
        {"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}},"required":["x","y"]}
    """.trimIndent()
    override fun systemPrompt() = "screen_tap: 点击屏幕坐标（需用户确认）。参数 {x: int, y: int}。"
    override val requiresConfirmation = true

    override suspend fun execute(argumentsJson: String): String {
        val args = a11yArgs(argumentsJson) ?: return a11yError("Invalid arguments")
        val x = args["x"]?.jsonPrimitive?.intOrNull ?: return a11yError("x 缺失")
        val y = args["y"]?.jsonPrimitive?.intOrNull ?: return a11yError("y 缺失")
        return bridge.withBridge {
            if (tap(x.toFloat(), y.toFloat())) a11yOk("tapped" to "$x,$y")
            else a11yError("点击失败")
        }
    }
}

/** 滑动手势（需用户确认） */
class ScreenSwipeTool(private val bridge: AccessibilityBridge) : AiTool {
    override val name = "screen_swipe"
    override val description = "在手机屏幕上滑动。参数 {x1,y1,x2,y2: int, durationMs?: int}。"
    override val parametersJsonSchema = """
        {"type":"object","properties":{"x1":{"type":"integer"},"y1":{"type":"integer"},"x2":{"type":"integer"},"y2":{"type":"integer"},"durationMs":{"type":"integer"}},"required":["x1","y1","x2","y2"]}
    """.trimIndent()
    override fun systemPrompt() = "screen_swipe: 滑动手势（需用户确认）。参数 {x1,y1,x2,y2: int, durationMs?: int}。"
    override val requiresConfirmation = true

    override suspend fun execute(argumentsJson: String): String {
        val args = a11yArgs(argumentsJson) ?: return a11yError("Invalid arguments")
        val x1 = args["x1"]?.jsonPrimitive?.intOrNull ?: return a11yError("x1 缺失")
        val y1 = args["y1"]?.jsonPrimitive?.intOrNull ?: return a11yError("y1 缺失")
        val x2 = args["x2"]?.jsonPrimitive?.intOrNull ?: return a11yError("x2 缺失")
        val y2 = args["y2"]?.jsonPrimitive?.intOrNull ?: return a11yError("y2 缺失")
        val duration = args["durationMs"]?.jsonPrimitive?.intOrNull ?: 300
        return bridge.withBridge {
            if (swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration.toLong())) {
                a11yOk("swiped" to "($x1,$y1)->($x2,$y2)")
            } else a11yError("滑动失败")
        }
    }
}

/** 按文本查找并点击（需用户确认） */
class ScreenClickTextTool(private val bridge: AccessibilityBridge) : AiTool {
    override val name = "screen_click_text"
    override val description = "在当前屏幕上按文本查找并点击对应元素。参数 {text: string}。"
    override val parametersJsonSchema = """
        {"type":"object","properties":{"text":{"type":"string","description":"要点击的按钮/元素的可见文本"}},"required":["text"]}
    """.trimIndent()
    override fun systemPrompt() = "screen_click_text: 按可见文本点击屏幕元素（需用户确认）。参数 {text: string}。"
    override val requiresConfirmation = true

    override suspend fun execute(argumentsJson: String): String {
        val args = a11yArgs(argumentsJson) ?: return a11yError("Invalid arguments")
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isBlank()) return a11yError("text 不能为空")
        return bridge.withBridge {
            if (clickText(text)) a11yOk("clicked" to text)
            else a11yError("屏幕上未找到可点击的\"$text\"")
        }
    }
}

/**
 * 向输入框输入文本（需用户确认，**本机敏感**）。
 *
 * 与 [ScreenClickTextTool] 的分工：点击负责「聚焦到哪个输入框」，输入负责「往里写什么」。
 * 目标选择的唯一性由 [AccessibilityBridge.inputText] 承担：
 * 优先当前聚焦的 editable；否则要求活动窗口内可编辑节点唯一；缺失或多义一律返回 false
 * ——**绝不猜输入目标**（猜错的代价是把内容打进搜索框 / 密码框 / 聊天框）。
 *
 * `appLocalOnly = true`：本工具能替用户往任意 App 的输入框写内容，
 * 外部桥接会话（QQ / 微信）不得借模型之手代打内容。
 */
class ScreenInputTextTool(private val bridge: AccessibilityBridge) : AiTool {
    override val name = "screen_input_text"
    override val description = "向当前屏幕上唯一确定的输入框输入文本。参数 {text: string}。需先用 screen_tap 或 screen_click_text 聚焦目标输入框。"
    override val parametersJsonSchema = """
        {"type":"object","properties":{"text":{"type":"string","description":"要输入到当前输入框的文本"}},"required":["text"]}
    """.trimIndent()
    override fun systemPrompt() = "screen_input_text: 向当前输入框输入文本（需用户确认）。参数 {text: string}。先聚焦输入框再输入。"
    override val requiresConfirmation = true
    override val appLocalOnly = true

    override suspend fun execute(argumentsJson: String): String {
        val args = a11yArgs(argumentsJson) ?: return a11yError("Invalid arguments")
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isBlank()) return a11yError("text 不能为空")
        return bridge.withBridge {
            if (inputText(text)) a11yOk("input" to text)
            else a11yError("未找到可编辑的输入框（请先点击输入框再输入）")
        }
    }
}

/**
 * 导出当前界面的结构化节点树 JSON（无参数，**本机敏感**）。
 *
 * 与 [ScreenReadTool] 的契约分工（已裁定，不要改）：`screen_read` 保持**扁平可见文本**
 * 输出契约不变（既有调用方依赖它）；需要结构（viewId / 坐标 / 可点击性 / 层级）时用本工具。
 *
 * 输出把节点树 JSON 放在 **`"ui"` 字符串字段**里（不再嵌套解析，避免把大对象塞进
 * 对话上下文时撑爆工具结果）；超过 [MAX_UI_CHARS] 字符即截断并置 `truncated: true`。
 *
 * `appLocalOnly = true`：结构化整窗节点树含包名 / viewId / 坐标 / 是否密码框，
 * 泄漏面远大于扁平文本，只允许予念 App 内本机会话使用。
 */
class ScreenDumpUiTool(private val bridge: AccessibilityBridge) : AiTool {
    override val name = "screen_dump_ui"
    override val description = "导出当前界面的结构化节点树 JSON（无参数），用于精确判断控件层级、viewId 与可点击性。"
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""
    override fun systemPrompt() = "screen_dump_ui: 导出当前界面节点树 JSON（无参数）。screen_read 给扁平文本，本工具给结构化节点树。"
    override val requiresConfirmation = false
    override val appLocalOnly = true

    override suspend fun execute(@Suppress("UNUSED_PARAMETER") argumentsJson: String): String =
        bridge.withBridge {
            val json = dumpNodeTreeJson()
            if (json.isBlank()) a11yError("未能读取界面节点树（当前界面可能不支持读取）")
            else a11yOk(
                "ui" to json.take(MAX_UI_CHARS),
                "truncated" to (json.length > MAX_UI_CHARS),
            )
        }

    companion object {
        /** `ui` 字段最大字符数；超出即截断并置 `truncated: true`。 */
        const val MAX_UI_CHARS: Int = 12000
    }
}
