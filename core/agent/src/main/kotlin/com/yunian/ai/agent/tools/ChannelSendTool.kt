package com.yunian.ai.agent.tools

import com.yunian.ai.common.ContentFilter
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ChannelKeys
import com.yunian.ai.domain.channel.ChannelOutboundEvents
import com.yunian.ai.domain.channel.ChannelOutboundRequest
import com.yunian.ai.domain.channel.ChannelOutboundResult
import com.yunian.ai.domain.plugin.PluginContext

/**
 * Agent **主动发送**工具：把一条文本经指定消息通道发出去。
 *
 * ## 这不是「调用通道的 send()」，而是**派发一次插件事件**
 *
 * §17.6 的架构裁定把这条边界定死了：[com.yunian.ai.domain.channel.ChannelSession.send]
 * 是**传输契约**，`send(outbound)` 把通道当**被调用方**，与投影模型（通道是**订阅方**）形状不符。
 * 因此本工具的 [execute] 里**没有**任何 `ChannelAdapter` / `ChannelSession` / `send()`：
 * 它只把请求表达为 [ChannelOutboundEvents.REQUEST] 事件、经 [PluginContext.bail] 派发，
 * 由通道插件作为订阅方认领并应答。
 *
 * ## 三条不可协商的语义
 *
 * 1. **没人认领 = 失败**：派发结果 [com.yunian.ai.domain.plugin.PluginEventResult.isBailed]
 *    为 `false` 即「没有任何通道插件处理这个请求」，返回
 *    [ChannelOutboundResult.NoChannel]（「该通道未启用」）——**绝不假装成功**。
 * 2. **出站文本必须先过安全过滤**：[ContentFilter.checkOutputSafety] 判定不安全即**拒绝发送**
 *    并返回原因，文本不会进入事件派发。
 * 3. **结果不得表达「对方已收到」**：QQ 与微信的
 *    [com.yunian.ai.domain.channel.ChannelCapabilities.deliveryReceipt] 都是 `false`，
 *    成功结果里 `delivery_receipt` 恒为 `false`，并附 `delivery_note` 说明。
 *
 * ## 渠道隔离（安全关键）
 *
 * [appLocalOnly] = `true`：本工具能操纵**通道出站**，一旦出现在 QQ / 微信等外部桥接会话的
 * 工具列表里，远端联系人就能借模型之手操纵通道（给群发消息、给宿主发消息）。
 * 标记为 true 后它在非本机会话里**连名字都看不到**（`ToolRegistry.availableTools` 默认排除），
 * 执行侧还有第二道闸（`AgentToolHost.allowAppLocalTools`，不可变构造参数）。
 *
 * [requiresConfirmation] = `true`（用户裁定 Q3：默认需确认，授权后自主）：
 * 本工具只在 App 内可见，而 App 内单聊 / 群聊**有确认 UI**，因此确认门能正常弹卡，
 * 不会被 `AgentConfirmGuard` 在无确认界面的通道上 fail-closed 自动拒绝。
 *
 * ## 参数**不参与授权判定**
 *
 * [parametersJsonSchema] 里的 `channelKey` / `target` 是**模型给出的参数**，
 * 只用于「发给谁」的路由。授权只能来自宿主不可变的构造上下文（见 `AiTool.appLocalOnly`
 * 的红线）——本工具**没有**、也**不得有**「按参数放行」的分支。
 *
 * @param ctx 装配期拿到的插件上下文（派发事件用）。工具与插件同生共死：
 *   插件卸载 → 工具注销，因此不存在「工具还在、事件总线已没了」的僵尸窗口。
 * @param safetyCheck 出站文本安全门（默认 = 真 [ContentFilter.checkOutputSafety]）。
 *   抽成函数类型只为**可测性**：`ContentFilter` 内部走 `android.util.Log` 与语义检测器，
 *   在纯 JVM 单测里是抛 `RuntimeException("Stub!")` 的空壳（本仓库无 Robolectric）。
 *   **生产默认值就是真过滤器**，不存在「测试用假门、生产忘记接真门」的缝隙。
 */
class ChannelSendTool(
    private val ctx: PluginContext,
    private val safetyCheck: (String) -> ContentFilter.OutputSafetyResult = {
        ContentFilter.checkOutputSafety(it)
    },
) : AiTool {

    companion object {
        /**
         * 工具名——**对外契约，一次定死不得再改**（见 `Plugin.kt`：工具名即对外契约，
         * 迁移/重构不得改变）。
         */
        const val NAME: String = "send_channel_message"

        /** 工具归属的工具集（通道出站能力）。 */
        val TOOLSET: Set<String> = setOf("channel")

        /** 出站文本长度上限（与通道侧单条消息的现实上限同量级；超限明确失败而不是截断）。 */
        const val MAX_TEXT_LENGTH: Int = 2000

        /**
         * 合法通道标识白名单（唯一来源 [ChannelKeys]，禁止散落字面量）。
         *
         * [ChannelKeys.UNSPECIFIED] **刻意不在白名单里**：它是「调用方没声明自己是谁」的
         * 占位值，不是一个可发送的通道——拿它去派发只会得到「该通道未启用」，
         * 不如在参数校验阶段就明确拒绝。
         */
        val KNOWN_CHANNEL_KEYS: Set<String> = setOf(
            ChannelKeys.QQBOT,
            ChannelKeys.WECHAT,
            ChannelKeys.APP_CHAT,
            ChannelKeys.APP_GROUPCHAT,
        )
    }

    override val name: String = NAME

    override val description: String =
        "通过予念里已启用的消息通道（如 QQ 机器人），把一条文本消息发给用户本人或指定目标。" +
            "当用户说「你给我 QQ 发条消息」「在 QQ 群里 @ 我一下」这类请求时使用。" +
            "channelKey 指定通道（qqbot / wechat）；target 省略或留空表示发给用户本人，" +
            "发到最近一次与机器人说话的 QQ 群用 target=group；仅当调用方已经持有平台内部标识时" +
            "才使用 \"group:<群 openid>\"。" +
            "发送前会做内容安全过滤，不安全的内容会被拒绝。" +
            "本工具只在予念 App 内的会话中可用；结果表示「已交给通道发出」，" +
            "**不代表对方已收到**（这些通道没有投递回执）。"

    override val parametersJsonSchema: String = """
        {"type":"object","properties":{
          "channelKey":{"type":"string","description":"目标通道标识：qqbot=QQ 机器人，wechat=微信。必须与予念里已启用的通道一致"},
          "target":{"type":"string","description":"目标标识：省略或留空=发给用户本人；QQ 最近群聊传 group（推荐，不需要知道内部 ID）；仅已持有群 openid 时传 group:<群 openid>；单聊可传 user_openid。本字段只决定发给谁，不参与权限判定"},
          "text":{"type":"string","description":"要发送的文本内容（必填，不能为空）"}
        },"required":["channelKey","text"],"additionalProperties":false}
    """.trimIndent()

    override val toolsets: Set<String> = TOOLSET

    /** **安全关键**：只在 App 内本机会话可见可执行；外部桥接会话里连名字都看不到。 */
    override val appLocalOnly: Boolean = true

    /** 默认走确认门（用户在「工具授权」页显式授权后才允许自主）。 */
    override val requiresConfirmation: Boolean = true

    /**
     * **恒可用**（刻意不表达「通道是否已连接」）。
     *
     * `ToolRegistry.availableTools` 会按 30s TTL 缓存本方法的结果、并有 60s flake 容忍窗口，
     * 用缓存值表达连接状态必然产生最长 90s 的滞后与误判。
     * 连接 / 登录 / 目标存在性一律在 [execute] 内部**实时**再判一次，
     * 由通道插件如实返回失败原因（见 [ChannelOutboundResult.Failed]）。
     */
    override fun isAvailable(): Boolean = true

    override fun systemPrompt(): String =
        "需要给用户在外部消息通道（QQ / 微信）上发消息时，调用 " + NAME +
            " 并指定 channelKey；用户说「给我发条 QQ」时 target 留空即发给本人；" +
            "用户说发到 QQ 群时，target=group 表示最近一次与机器人说话的群。" +
            "工具结果 send_succeeded=true 且 status=sent 表示通道已接受发送，必须按成功处理，" +
            "不得因为 delivery_status=unknown（通道无送达回执）改口称发送失败；" +
            "只有 ok=false/status=failed 才能称失败。若返回 no_channel，说明通道未启用。"

    override fun summarizeArguments(argumentsJson: String): String {
        val channel = readString(argumentsJson, "channelKey").orEmpty()
        val target = readString(argumentsJson, "target")
        val text = readString(argumentsJson, "text").orEmpty()
        val preview = if (text.length > 60) text.take(60) + "…" else text
        return "channelKey=" + channel +
            (if (target.isNullOrBlank()) " target=<本人>" else " target=" + target) +
            " text=" + preview
    }

    override suspend fun execute(argumentsJson: String): String {
        val obj = FlatJsonObject.parse(argumentsJson.ifBlank { "{}" })
            ?: return failure("malformed_arguments", "参数不是合法 JSON 对象")

        // ── 参数校验（在派发之前，参数错误不产生任何出站副作用）──
        val channelKey = obj.string("channelKey").orEmpty()
        if (channelKey.isEmpty()) {
            return failure("missing_channel", "缺少参数 channelKey（目标通道标识）")
        }
        if (channelKey !in KNOWN_CHANNEL_KEYS) {
            return failure(
                "unknown_channel",
                "未知通道：" + channelKey + "（可用：" +
                    KNOWN_CHANNEL_KEYS.sorted().joinToString(",") + "）",
            )
        }

        val text = obj.string("text").orEmpty()
        if (text.isEmpty()) {
            return failure("missing_text", "缺少参数 text（要发送的文本内容）")
        }
        if (text.length > MAX_TEXT_LENGTH) {
            return failure(
                "text_too_long",
                "文本过长：" + text.length + " 字，上限 " + MAX_TEXT_LENGTH + " 字（请分段发送）",
            )
        }

        // target 为空白 = 未指定 = 本通道绑定的宿主（用户本人）。
        val target = obj.string("target")?.ifEmpty { null }

        // ── 出站文本安全门（不通过则**不派发**，文本不进入任何通道）──
        val safety = try {
            safetyCheck(text)
        } catch (_: Exception) {
            // 安全门自身故障时 fail-closed：拒绝发送，绝不放行未经检查的文本。
            return failure("safety_check_failed", "内容安全过滤不可用，已拒绝发送")
        }
        if (!safety.isSafe) {
            return failure(
                "unsafe_content",
                "出站内容未通过安全过滤（" + safety.level + "：" + safety.reason + "），已拒绝发送",
            )
        }

        // ── 派发为插件事件：通道插件作为**订阅方**认领 ──
        val dispatched = ctx.bail(
            ChannelOutboundEvents.REQUEST,
            ChannelOutboundRequest(channelKey = channelKey, target = target, text = text),
        )

        val result = if (!dispatched.isBailed) {
            // 没有任何通道插件处理这个请求：该通道未启用 / 未装载。
            ChannelOutboundResult.NoChannel(channelKey)
        } else {
            dispatched.bailValue as? ChannelOutboundResult
                ?: return failure(
                    "bad_channel_response",
                    "通道 " + channelKey + " 返回了无法识别的应答（" +
                        (dispatched.bailValue?.javaClass?.simpleName ?: "null") + "）",
                )
        }

        return when (result) {
            is ChannelOutboundResult.Sent -> successJson(channelKey, target, result.messageRef)
            is ChannelOutboundResult.Failed -> failure("send_failed", result.reason)
            is ChannelOutboundResult.NoChannel ->
                failure(
                    "no_channel",
                    "该通道未启用：" + result.channelKey +
                        "（没有任何通道插件处理这次请求；请先在予念里启用该通道）",
                )
        }
    }

    /**
     * 成功应答：把「发送调用成功」放在最强、无歧义字段中；送达能力另用枚举表达。
     *
     * 旧契约同时返回 `ok=true`、`delivery_receipt=false` 和“不代表收到”。真机调度记录证明
     * 模型会抓住 `false`/否定措辞，把已经成功的发送误述为失败。因此成功结果中不再放
     * 布尔 false 或失败式措辞；`delivery_status=unknown` 仅表示通道没有对端送达回执。
     */
    private fun successJson(channelKey: String, target: String?, messageRef: String?): String =
        buildJson {
            put("ok", true)
            put("send_succeeded", true)
            put("status", "sent")
            put("channelKey", channelKey)
            put("target", target ?: "bound_host")
            put("messageRef", messageRef)
            put("delivery_status", "unknown")
            put(
                "model_instruction",
                "发送调用已成功；请向用户确认已发出。delivery_status=unknown 只表示无对端送达回执。",
            )
        }

    private fun failure(code: String, reason: String): String = buildJson {
        put("ok", false)
        put("status", "failed")
        put("error", code)
        put("reason", reason)
    }

    /**
     * 极小的 JSON 对象写出器（**不依赖 org.json**）。
     *
     * 不用 `org.json.JSONObject` 的理由：它在纯 JVM 单测里是抛 `RuntimeException("Stub!")`
     * 的 Android 空壳（本仓库无 Robolectric）。这里只写**扁平的字符串 / 布尔 / null**
     * 字段，转义规则与 JSON 规范一致，避免手写拼接时漏转义。
     */
    private class JsonWriter {
        private val sb = StringBuilder("{")
        private var first = true

        fun put(key: String, value: String?) {
            comma()
            sb.append(quote(key)).append(':')
            if (value == null) sb.append("null") else sb.append(quote(value))
        }

        fun put(key: String, value: Boolean) {
            comma()
            sb.append(quote(key)).append(':').append(value)
        }

        private fun comma() {
            if (!first) sb.append(',')
            first = false
        }

        private fun quote(raw: String): String {
            val out = StringBuilder(raw.length + 8)
            out.append('"')
            for (c in raw) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    c < ' ' -> out.append("\\u%04x".format(c.code))
                    else -> out.append(c)
                }
            }
            out.append('"')
            return out.toString()
        }

        override fun toString(): String = sb.append('}').toString()
    }

    private fun buildJson(block: JsonWriter.() -> Unit): String =
        JsonWriter().apply(block).toString()

    /** 从扁平 JSON 对象里读一个字符串字段（供 [summarizeArguments] 用）。 */
    private fun readString(argumentsJson: String, field: String): String? =
        FlatJsonObject.parse(argumentsJson.ifBlank { "{}" })?.string(field)
}

/**
 * 极小的**扁平 JSON 对象**读取器（只支持本工具 schema 声明的字符串字段）。
 *
 * ## 为什么不用现成的 JSON 库
 *
 * - `org.json.JSONObject` 是 Android 平台的 java-stub，在纯 JVM 单测里抛
 *   `RuntimeException("Stub!")`（本仓库无 Robolectric，`:core:agent` 也未开
 *   `unitTests.isReturnDefaultValues`）——一旦用它，本文件的全部单测都跑不起来；
 * - `kotlinx.serialization.json` 不在 `:core:agent` 的依赖里（该模块只依赖
 *   `:core:domain` / `:core:database` / coroutines / JNA），为了一个三字段的参数解析
 *   给核心模块加依赖不划算（本批约束：不得引入第三方依赖）。
 *
 * ## 支持范围（刻意最小）
 *
 * 顶层必须是对象；只解析**字符串值**字段（含 \\n \\t \\r \\b \\f \\" \\\\ \\/ \\uXXXX 转义）；
 * 嵌套对象 / 数组 / 数字 / 布尔 / null 值一律**跳过**（按 schema 它们不该出现）。
 * 任何解析异常都返回 null，由调用方转成明确的参数错误——**绝不抛给上层**。
 */
internal class FlatJsonObject private constructor(private val values: Map<String, String>) {

    /** 读一个字符串字段：缺失 / 非字符串 / 空白 → null。 */
    fun string(field: String): String? = values[field]?.trim()?.ifEmpty { null }

    companion object {
        fun parse(json: String): FlatJsonObject? = try {
            Parser(json).parseObject()
        } catch (_: Exception) {
            null
        }
    }

    private class Parser(private val src: String) {
        private var i = 0

        fun parseObject(): FlatJsonObject? {
            skipWs()
            if (read() != '{') return null
            val map = LinkedHashMap<String, String>()
            skipWs()
            if (peek() == '}') {
                i++
                return FlatJsonObject(map)
            }
            while (true) {
                skipWs()
                val key = readStringLiteral() ?: return null
                skipWs()
                if (read() != ':') return null
                skipWs()
                val value = readValue()
                if (value is Value.Str) map[key] = value.text else skipValue(value)
                skipWs()
                when (read()) {
                    ',' -> continue
                    '}' -> return FlatJsonObject(map)
                    else -> return null
                }
            }
        }

        private sealed class Value {
            class Str(val text: String) : Value()
            object Other : Value()
        }

        private fun readValue(): Value = when (peek()) {
            '"' -> Value.Str(readStringLiteral() ?: throw IllegalStateException("bad string"))
            else -> Value.Other
        }

        /** 跳过非字符串值（嵌套对象 / 数组 / 数字 / 布尔 / null），保证括号配对。 */
        private fun skipValue(value: Value) {
            if (value !is Value.Other) return
            when (peek()) {
                '{' -> skipBalanced('{', '}')
                '[' -> skipBalanced('[', ']')
                else -> while (i < src.length && src[i] !in ",}]") i++
            }
        }

        private fun skipBalanced(open: Char, close: Char) {
            var depth = 0
            while (i < src.length) {
                val c = src[i]
                when {
                    c == '"' -> readStringLiteral()
                    c == open -> { depth++; i++ }
                    c == close -> {
                        depth--
                        i++
                        if (depth == 0) return
                    }
                    else -> i++
                }
            }
        }

        private fun readStringLiteral(): String? {
            if (read() != '"') return null
            val sb = StringBuilder()
            while (i < src.length) {
                val c = src[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= src.length) return null
                        when (val esc = src[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'u' -> {
                                if (i + 4 > src.length) return null
                                val code = src.substring(i, i + 4).toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return null
        }

        private fun peek(): Char = if (i < src.length) src[i] else '\u0000'

        private fun read(): Char = if (i < src.length) src[i++] else '\u0000'

        private fun skipWs() {
            while (i < src.length && src[i].isWhitespace()) i++
        }
    }
}
