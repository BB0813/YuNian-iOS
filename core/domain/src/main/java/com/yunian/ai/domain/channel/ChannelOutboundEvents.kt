package com.yunian.ai.domain.channel

/**
 * 通道出站**请求 / 应答**的事件契约（通道无关，零第三方依赖）。
 *
 * ## 为什么是事件，而不是 [ChannelSession.send] 的扩展
 *
 * §17.6 的架构裁定把这条边界定死了：
 * - [ChannelAdapter] / [ChannelSession.send] 是**传输契约**，与投影模型**形状不符**——
 *   `send(outbound)` 把通道当**被调用方**，而投影模型里通道是**订阅方**；
 * - 「Agent 想主动发一条」缺少**请求/应答语义**，在 Cordis 里那是 `waterfall` / `bail`
 *   的用途，**不是一个公开的 `send()`**。
 *
 * 于是主动发送被表达成一次**插件事件派发**：
 * 1. 请求方（`core:agent` 的发起方工具插件）调
 *    [com.yunian.ai.domain.plugin.PluginContext.bail] 派发 [ChannelOutboundEvents.REQUEST]，
 *    payload 是 [ChannelOutboundRequest]；
 * 2. 通道插件作为**订阅方**用
 *    [com.yunian.ai.domain.plugin.PluginContext.onBail] 订阅：**只有 `channelKey` 匹配自己时**
 *    才处理并 bail 出 [ChannelOutboundResult]，否则返回 null **放行给下一个订阅者**；
 * 3. 派发返回值 [com.yunian.ai.domain.plugin.PluginEventResult.isBailed] 为 `false`
 *    即「**没有任何通道处理这个请求**」——请求方必须据此**明确失败**，绝不假装成功。
 *
 * ## 为什么放在 core:domain
 *
 * 请求/结果类型要同时被 `core:agent`（发起方）与 `feature:qqbot`（订阅方）使用，
 * 而 `feature` 之间不得互相依赖、`core` 也不得依赖 `feature`；
 * 唯一合法的共同落点就是零依赖的 `core:domain`。本文件**只**用 Kotlin 标准库，
 * 不引入 kotlinx.serialization 之外的任何东西（本模块的既有依赖约束见 [ChannelAdapter]）。
 */

/**
 * 出站请求/应答事件的**事件名与键名常量**（唯一来源，禁止各处散落字符串字面量）。
 */
object ChannelOutboundEvents {

    /**
     * 事件名：派发一次「请把这条文本经某通道发出去」的请求。
     *
     * 用 [com.yunian.ai.domain.plugin.PluginContext.bail] 派发（同步顺序 + 首个 bail 短路）：
     * 通道插件之间是**互斥**的（一个请求只该由一个通道处理），
     * 而不是 [com.yunian.ai.domain.plugin.PluginContext.parallel] 那种「全部都要跑」的广播。
     *
     * 选 `bail` 而不选 `waterfall` 的理由：`waterfall` 的语义是**把监听器包在 inner 外面**
     * （首个注册的在最外层，可前后加料、可改写下游结果），适合「过滤器链 / 组合中间件」；
     * 而本请求是**一次性路由**——谁认领谁处理，不需要「包一层再放行」的组合语义。
     * `bail` 的「顺序调用、遇 bail 值停止」正是路由所需的语义，且与
     * `isBailed == false` 天然表达了「无人认领」这个必须被如实上报的状态。
     */
    const val REQUEST: String = "channel.outbound.request"
}

/**
 * 一次出站请求（**通道无关**）。
 *
 * 三个字段刻意都是通道无关的：契约层**不知道** QQ 的 `msg_id` / 微信的 outbox /
 * ilink 的 ret 码，这些协议细节一律留在各自 `feature` 模块内。
 *
 * @param channelKey 目标通道标识（取值见 [com.yunian.ai.domain.ChannelKeys]；
 *   禁止散落字面量）。**只有 [channelKey] 与自己的 [ChannelAdapter.channelKey] 相等的
 *   通道插件才得处理本请求**，其余必须放行（返回 null，不 bail）。
 * @param target 目标标识；`null` = **本通道绑定的宿主，即用户本人**。
 *   非 `null` 时由**通道插件**按自己的协议解释（QQ：`user_openid`，
 *   或以 `group:` 前缀指定 `group_openid`）——契约层不解释它的内容。
 *
 *   ⚠️ [target] 与 [channelKey] 都是**模型给出的参数**，
 *   **绝不得**用它们做授权判定（见 `AiTool.appLocalOnly` 的红线：
 *   授权只能来自宿主不可变的构造上下文）。
 * @param text 要发送的文本（非空白；已由请求方做过内容安全过滤）。
 */
data class ChannelOutboundRequest(
    val channelKey: String,
    val target: String?,
    val text: String,
)

/**
 * 出站请求的应答结果。
 *
 * ## 语义红线：**不得**表达「对方已收到」
 *
 * QQ 与微信的 [ChannelCapabilities.deliveryReceipt] **都是 `false`**（出站只校验
 * HTTP 2xx / `ret==0`，没有任何 ack）。因此本类型里**没有**任何「已送达 / 已读」的表达，
 * [Sent] 的语义严格是「**已交给通道发出，且通道侧未报错**」。
 * 想表达送达就必须先有回执通路，而不是在这里加一个字段。
 */
sealed class ChannelOutboundResult {

    /**
     * 已交给通道发出（通道侧未报错）。
     *
     * [messageRef] 是通道侧的消息标识（QQ 的响应 `id`）；`null` = 通道不返回标识。
     */
    data class Sent(val messageRef: String? = null) : ChannelOutboundResult()

    /**
     * 失败（原因必须具体、可读，**禁止静默失败**）。
     *
     * 覆盖三类：通道已启用但前置状态不满足（未登录 / 未连接 / 没有可用目标）、
     * 目标不合法、通道侧报错。
     */
    data class Failed(val reason: String) : ChannelOutboundResult()

    /**
     * **没有任何通道插件认领这个请求**（即该通道未启用 / 未装载）。
     *
     * 这不是 [Failed] 的一种：它描述的是「事件派发根本没人 bail」，
     * 请求方据 [com.yunian.ai.domain.plugin.PluginEventResult.isBailed] 为 `false` 得出。
     * 单列一个变体是为了让「**该通道未启用**」与「通道说发送失败了」在上层可区分——
     * 两者的用户可见处置不同（前者去启用通道，后者看失败原因），
     * 但**两者都必须让工具返回失败**，绝不允许把「没人处理」当成成功。
     */
    data class NoChannel(val channelKey: String) : ChannelOutboundResult()
}
