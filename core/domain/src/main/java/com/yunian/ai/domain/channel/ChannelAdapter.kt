package com.yunian.ai.domain.channel

import kotlinx.coroutines.flow.StateFlow

/**
 * 消息通道契约（对齐 [com.yunian.ai.domain.plugin.PluginKind.ADAPTER]：**一个消息通道一个插件**）。
 *
 * 本文件是「通道」那一半框架的**唯一契约来源**，刻意与具体通道无关：
 * 这里**不得**出现 QQ / 微信 / ilink / Android 的任何类型。通道自身的协议细节
 * （QQ 的 msg_id/msg_seq、微信的 outbox、ilink 的 ret/errcode）一律留在各自的
 * feature 模块内，经 [ChannelSession] 的实现适配到本契约。
 *
 * 依赖约束：本模块只允许 kotlinx.coroutines 与 kotlinx.serialization
 * （见 `core/domain/build.gradle.kts`）。
 *
 * ## 诚实声明原则
 *
 * [ChannelCapabilities] 描述的是**当前代码真的做到了什么**，不是协议理论上支持什么、
 * 也不是「以后会做」。例：
 * - QQ 的协议里存在 `msg_type=6`（INPUT_NOTIFY）枚举，但全仓**从未发送过**，
 *   因此 QQ 的 [ChannelCapabilities.typingIndicator] 必须是 `false`；
 * - 某通道的出站若**只能**被动回复（必须带 `event.raw.id` 作为 `msg_id`），
 *   则它的 [ChannelCapabilities.proactiveSend] 必须是 `false`；
 * - QQ 与微信的出站都**没有投递回执**（微信 `checkSendResponse` 只解析 `ret/errcode`，
 *   无 msg_id、无 ack），因此两者的 [ChannelCapabilities.deliveryReceipt] 必须是 `false`。
 *
 * [ChannelCapabilities.deliveryReceipt] 是本契约里最重要的一位：它让框架能区分
 * 「**发出去就算成功**」与「**能确认对方收到**」。缺了这一位，上层无法判断
 * [ChannelSendResult.Sent] 到底意味着什么，也就无法对「发了没反应」做出正确降级。
 */
interface ChannelAdapter {

    /**
     * 通道稳定标识（唯一来源 [com.yunian.ai.domain.ChannelKeys]，禁止散落字符串字面量）。
     *
     * 同时作为注册中心的主键（[ChannelRegistry.adapter]）；同一注册中心内不得重复。
     */
    val channelKey: String

    /** 展示名（设置页 / 日志用）。 */
    val displayName: String

    /**
     * 能力诚实声明（逐字段语义见 [ChannelCapabilities]）。
     *
     * 必须是**常量**：它是装载期的静态声明，不允许随连接状态变化——
     * 连接状态属于 [ChannelSession.state]。
     */
    val capabilities: ChannelCapabilities

    /**
     * 启动会话并返回会话句柄（对齐 Cordis 插件装配期只接线、不产生运行时副作用）。
     *
     * 约定：本方法**不得**在调用时阻塞等待网络就绪；连接进度经
     * [ChannelSession.state] 暴露。实现抛异常视为启动失败。
     */
    suspend fun start(): ChannelSession
}

/**
 * 通道能力声明（**诚实值**，见 [ChannelAdapter] 的诚实声明原则）。
 *
 * 八位各自独立：某一位为 `false` 表示「当前实现**没有**这条通路」，
 * 上层据此选择降级策略，而不是假设它可用。
 */
data class ChannelCapabilities(
    /** 能收到对端文本。 */
    val inboundText: Boolean,
    /**
     * 能收到对端图片。
     *
     * QQ 当前为 `false`：`QQBotMessageRepository.extractImageUrl` 零调用，
     * 入站 `attachments` 无人消费 → 用户发图等同发空文本。
     */
    val inboundImage: Boolean,
    /** 能发出文本。 */
    val outboundText: Boolean,
    /** 能发出图片。 */
    val outboundImage: Boolean,
    /**
     * 有「正在输入」提示通路。
     *
     * QQ 当前为 `false`：协议枚举 `msg_type=6`（INPUT_NOTIFY）已声明但从未使用；
     * 微信当前为 `false`：完全没有 typing 接缝。
     */
    val typingIndicator: Boolean,
    /**
     * [ChannelSession.send] 能否主动发起会话（**不需要**先收到对端消息）。
     *
     * 这一位只描述 **[ChannelSession.send] 这一条通路**：`true` 表示调用方可以构造一条
     * 不锚定任何入站消息的出站并期望 [ChannelSession.send] 不因「缺少被动锚点」而拒绝。
     *
     * ⚠️ **主动发送不等于本字段**。按 §17.6 的架构裁定，[ChannelSession.send] 是**传输契约**，
     * 与投影模型形状不符（`send(outbound)` 把通道当被调用方，而投影模型里通道是订阅方）。
     * 「Agent 想主动发一条」的请求/应答语义走**插件事件**（`bail` / `waterfall`），
     * 契约见 [ChannelOutboundEvents] / [ChannelOutboundRequest]——**不经过本字段**。
     * 因此本字段为 `false` 的通道**仍可能**具备主动发送能力（QQ 就是这种情况）。
     *
     * 与其余七位一样是**诚实值**：未实现即 `false`，不是「协议理论上支持」。
     * - QQ 当前为 `false`：[ChannelSession.send] 100% 被动（`msgId = event.raw.id`）；
     *   其主动发送能力经 [ChannelOutboundEvents] 事件通路提供，与本字段无关；
     * - 微信当前为 `false`：[ChannelSession.send] 只能**被动回复**
     *   （锚点 = 收件人的 `wechatUserId` + 其 `context_token`）；
     *   其**主动发送**能力经 [ChannelOutboundEvents] 事件通路提供，**不走**这一位
     *   （与 QQ 同构，见 [com.yunian.ai.domain.ChannelKeys.WECHAT] 的适配器实现）。
     */
    val proactiveSend: Boolean,
    /**
     * 能确认对方收到（投递回执）。
     *
     * 两个通道当前都是 `false`：出站只校验 HTTP 2xx / `ret==0`，**没有任何 ack**。
     * 因此 [ChannelSendResult.Sent] 的语义是「已交给通道发出」，**不是**「对方已收到」。
     */
    val deliveryReceipt: Boolean,
    /** 能发表情（微信走图片通道，故为 `true`；QQ 无表情通路，为 `false`）。 */
    val stickers: Boolean,
)

/** 通道连接状态（与具体协议的状态机解耦，只保留框架关心的五态）。 */
enum class ChannelConnectionState {
    /** 未连接（未启动 / 已主动断开）。 */
    DISCONNECTED,
    /** 正在建立连接（含握手 / 鉴权）。 */
    CONNECTING,
    /** 已连接可用。 */
    CONNECTED,
    /** 连接中断，正在重连（非终态）。 */
    RECONNECTING,
    /** 失败（鉴权失败 / 重试预算耗尽等终态）。 */
    FAILED,
}

/**
 * 出站消息（**[ChannelSession.send] 这一条通路**的唯一入参形状）。
 *
 * ⚠️ 本类型**刻意只有一个变体**。按 §17.6 的架构裁定，`send(outbound)` 是**传输契约**，
 * 与投影模型形状不符：它把通道当**被调用方**，而投影模型里通道是**订阅方**。
 * 因此「Agent 想主动发一条」**不得**表达为 `ChannelOutbound` 的新变体，也不得走
 * [ChannelSession.send]——那是 [ChannelOutboundEvents] 插件事件（`bail` / `waterfall`）
 * 的用途：请求方派发事件，通道插件作为**订阅方**应答。
 *
 * 本类因此只保留被动回复一种形状，契约层不解释通道协议细节，
 * 只保证 [Text.replyTo] 这个不透明令牌原样传回适配器。
 */
sealed class ChannelOutbound {

    /** 出站文本。 */
    abstract val text: String

    /**
     * 被动回复（锚定某条入站消息）。
     *
     * [replyTo] 是**被动回复锚点**：由通道适配器定义的、指向某条入站消息的不透明令牌
     * （QQ 实现在里面放 `QQMessageEvent.id`，即出站 `msg_id` 的来源）。
     * 契约层不解释它的内容，只保证它原样传回适配器。
     */
    data class Text(
        override val text: String,
        val replyTo: String,
    ) : ChannelOutbound()
}

/**
 * 出站结果。
 *
 * ⚠️ [Sent] **不代表对方已收到**：当 [ChannelCapabilities.deliveryReceipt] 为 `false`
 * （当前两个通道都是）时，它只表示「已交给通道发出且通道侧未报错」。
 */
sealed class ChannelSendResult {

    /**
     * 已发出（通道侧未报错）。
     *
     * [messageRef] 是通道侧的消息标识（QQ 的响应 `id`）；`null` 表示通道不返回标识。
     */
    data class Sent(val messageRef: String? = null) : ChannelSendResult()

    /** 发送失败（原因由通道给出，禁止静默失败）。 */
    data class Failed(val reason: String) : ChannelSendResult()
}

/**
 * 通道会话句柄（一次连接的生命周期）。
 *
 * [AutoCloseable.close] 必须幂等且不得阻塞：关闭后 [state] 应到达
 * [ChannelConnectionState.DISCONNECTED]，且后续 [send] 明确失败而不是静默丢弃。
 */
interface ChannelSession : AutoCloseable {

    /** 当前连接状态（唯一事实来源；上层据此做降级）。 */
    val state: StateFlow<ChannelConnectionState>

    /**
     * 发送一条出站消息。
     *
     * 实现**不得**抛异常表示失败，一律返回 [ChannelSendResult]；仅在调用方违反契约
     * （如 [ChannelOutbound.Text.replyTo] 无法解析）时才允许抛异常。
     */
    suspend fun send(outbound: ChannelOutbound): ChannelSendResult
}

/**
 * 通道注册中心（宿主装配期的可查询视图）。
 *
 * 内容**必须来源于** [com.yunian.ai.domain.plugin.PluginHost.pluginsOf]
 * `(PluginKind.ADAPTER)`——即「已装载的 ADAPTER 插件」，而不是任何旁路的全局表。
 * 卸载（[com.yunian.ai.domain.plugin.PluginHost.unload]）后对应适配器必须立即消失。
 */
interface ChannelRegistry {

    /** 全部可用通道适配器（按 [ChannelAdapter.channelKey] 排序，稳定顺序）。 */
    fun adapters(): List<ChannelAdapter>

    /** 按 [ChannelAdapter.channelKey] 取适配器；不存在返回 null。 */
    fun adapter(channelKey: String): ChannelAdapter?
}

/**
 * 可注册的通道注册中心（供 ADAPTER 插件在装配期把自己挂上去）。
 *
 * 与只读视图 [ChannelRegistry] 分离的理由：框架的**查询侧**（上层按通道取适配器）
 * 不应该拿到写能力；只有装配期（插件的 `setup`）需要 [register]，而撤销由
 * [com.yunian.ai.domain.plugin.PluginContext.effect] 承担——这正是 Cordis
 * 「卸载不留鸡毛」的落点。
 *
 * 实现方**必须**在 [register] 里回查宿主（[com.yunian.ai.domain.plugin.PluginHost.pluginsOf]
 * `(PluginKind.ADAPTER)` + `isLoaded`），拒绝「绕过插件宿主直接塞进来」的注册：
 * 注册中心的输出永远只能是「此刻真正已装载的 ADAPTER 插件」的函数。
 */
interface MutableChannelRegistry : ChannelRegistry {

    /**
     * 把一个通道适配器挂到注册中心（由 ADAPTER 插件的 setup 调用）。
     *
     * fail-closed：插件不在宿主的 ADAPTER 分派视图里、或不是 ADAPTER 类别时
     * **必须拒绝**（抛异常）。
     *
     * ⚠️ 实现**不得**在 [register] 里强校验「已装载」：
     * [com.yunian.ai.domain.plugin.PluginHost.load] 是在 setup **成功之后**才把插件
     * 标为已装载的，装配期的注册请求里必然还不是装载态。
     * 可见性由**查询侧**保证：[adapters] / [adapter] 只暴露「此刻在 ADAPTER 分派
     * 视图里**且**已装载」的适配器——未装载或装载失败的插件即使注册过也不可见。
     */
    fun register(pluginId: String, adapter: ChannelAdapter)

    /**
     * 摘掉一个通道适配器（由插件的卸载副作用调用）。
     *
     * 只允许摘掉**自己**注册的条目（插件 A 不得摘掉插件 B 的适配器）；
     * 返回是否真的摘掉了（幂等：重复调用返回 false）。
     */
    fun unregister(pluginId: String, channelKey: String): Boolean
}
