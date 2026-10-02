package com.yunian.ai.feature.wechat.channel

import com.yunian.ai.domain.ChannelKeys
import com.yunian.ai.domain.channel.ChannelAdapter
import com.yunian.ai.domain.channel.ChannelCapabilities
import com.yunian.ai.domain.channel.ChannelConnectionState
import com.yunian.ai.domain.channel.ChannelOutbound
import com.yunian.ai.domain.channel.ChannelSendResult
import com.yunian.ai.domain.channel.ChannelSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 微信通道适配器（[ChannelAdapter] 的第二个真实实现，与 `QQBotChannelAdapter` 对称）。
 *
 * ## 与既有代码的关系：**只读包装，零行为改动**
 *
 * 本类不新建任何轮询、心跳、重连或收发逻辑——它把既有的 [WeChatChannelSender] 接缝
 * （生产 = `SdkWeChatTransport` + `WeChatTokenStore` 的包装）接到 [ChannelAdapter] 契约上。
 * 微信的保活时序完全不变：FGS（`WeChatPollingService`）/ WakeLock / watchdog /
 * `WeChatRestartWorker` / `WeChatOutboxDropLog` **一行未动**。
 *
 * ## capabilities 是**诚实声明**（不是理想值）
 *
 * | 位 | 值 | 依据 |
 * |---|---|---|
 * | inboundText | true | 文本入站已接（`WeChatMessageRepository.extractText` 被桥接层消费） |
 * | inboundImage | true | 入站图片**已消费**：`WeChatChatBridge.handleImageMessage` 下载 CDN 图片并走 AI 视觉回复（与 QQ 的 false 相反） |
 * | outboundText | true | `WeChatTransportPort.sendText` 已被桥接层 / outbox / 本通道消费 |
 * | outboundImage | true | `WeChatTransportPort.sendImage`（outbox 的 IMAGE kind、表情物化）已被消费 |
 * | typingIndicator | **false** | 全模块无 typing 接缝（iLink 协议无该通路） |
 * | proactiveSend | **false** | [ChannelSession.send] 是**传输契约**且只能被动回复（锚点 = 收件人的 wechatUserId + 其 context_token）。⚠️ 微信的**主动发送**能力**不走这一位**——它经 `ChannelOutboundEvents.REQUEST` 插件事件由 [WeChatChannelPlugin] 作为**订阅方**处理（§17.6 裁定，与 QQ 的处理一致） |
 * | deliveryReceipt | **false** | 出站只解析 `ret/errcode`，**无 msg_id、无 ack**：发了不等于对方收到 |
 * | stickers | **true** | 表情走图片通道（`WeChatStickerMaterializer` → `enqueueImageOutbound`） |
 *
 * ## 惰性
 *
 * [senderSupplier] **只在 [start] 被调用时求值**：装配期（插件 `setup`）不会触碰
 * TokenStore、DataStore、Room 或任何网络，因此接通装配链路对运行时行为零影响。
 *
 * @param senderSupplier 出站窄接口的供应函数（生产 = [weChatChannelSender]）。
 * @param sessionFactory 会话工厂（默认 [WeChatChannelSession]；单测可注入替身）。
 */
class WeChatChannelAdapter(
    private val senderSupplier: () -> WeChatChannelSender = {
        throw IllegalStateException(
            "微信通道出站供应函数未注入：生产由 YuNianApplication 传入既有 SdkWeChatTransport 的包装；" +
                "本任务只接装配链路，不启动真实会话",
        )
    },
    private val sessionFactory: (WeChatChannelSender) -> ChannelSession = { sender ->
        WeChatChannelSession(sender)
    },
) : ChannelAdapter {

    override val channelKey: String = KEY

    override val displayName: String = "微信"

    override val capabilities: ChannelCapabilities = CAPABILITIES

    override suspend fun start(): ChannelSession = sessionFactory(senderSupplier())

    companion object {
        /** 通道稳定标识（唯一来源 [ChannelKeys.WECHAT]，禁止散落字面量）。 */
        const val KEY: String = ChannelKeys.WECHAT

        /** 诚实能力声明（逐位依据见类注释表格）。 */
        val CAPABILITIES: ChannelCapabilities = ChannelCapabilities(
            inboundText = true,
            inboundImage = true,
            outboundText = true,
            outboundImage = true,
            typingIndicator = false,
            proactiveSend = false,
            deliveryReceipt = false,
            stickers = true,
        )
    }
}

/**
 * 微信通道会话：把**登录态**投影到契约的 [ChannelConnectionState]，并转发文本出站。
 *
 * ## 刻意**不**持有连接生命周期
 *
 * `close()` 只把本会话标记为已关闭，**不**断开任何东西——微信的连接 / 保活 / 重连归
 * 既有的 `WeChatPollingService`（FGS + WakeLock + watchdog）管，
 * 本类改动它们的行为就等于改动运行时行为（本任务明确禁止）。
 *
 * ## 状态只投影**登录态**，且不承诺「已连接」
 *
 * 微信没有对外可订阅的「连接态」事实（`WeChatChannelRuntime` 是进程内的静态计数器，
 * 且它的读法属于保活链路）。本会话因此只投影**唯一既有的可订阅事实**：
 * `WeChatTokenStore.accountFlow`（经 [WeChatChannelSender.loggedIn]）。
 * 登录即 [ChannelConnectionState.CONNECTED]（有账号即可发送），登出即
 * [ChannelConnectionState.DISCONNECTED]。**不**声称 CONNECTING / RECONNECTING / FAILED。
 *
 * ## 出站是**被动回复**语义
 *
 * [send] 只接受 [ChannelOutbound.Text]，其 `replyTo` 被解释为**收件人的 wechatUserId**
 * （iLink 协议只能回复「对方先发来消息」的那个会话，锚点就是该用户 + 其 context_token）。
 * 前置状态不满足一律返回 [ChannelSendResult.Failed] 并给出具体原因，**绝不静默丢弃**。
 *
 * ⚠️ **主动发送不走这里**（§17.6 裁定）：主动发送经 `ChannelOutboundEvents.REQUEST`
 * 插件事件、由 [WeChatChannelPlugin] 作为订阅方处理。
 *
 * @param sender 出站窄接口（生产 = 既有传输的包装；单测 = 替身）。
 * @param scope 订阅登录态用的作用域（默认自建，[close] 时取消）。
 */
class WeChatChannelSession(
    private val sender: WeChatChannelSender,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) : ChannelSession {

    private val _state = MutableStateFlow(ChannelConnectionState.DISCONNECTED)

    @Volatile
    private var closed = false

    override val state: StateFlow<ChannelConnectionState> = _state.asStateFlow()

    init {
        scope.launch {
            sender.loggedIn.collect { loggedIn ->
                _state.value = if (loggedIn) {
                    ChannelConnectionState.CONNECTED
                } else {
                    ChannelConnectionState.DISCONNECTED
                }
            }
        }
    }

    override suspend fun send(outbound: ChannelOutbound): ChannelSendResult {
        if (closed) return ChannelSendResult.Failed("通道会话已关闭")
        if (outbound.text.isBlank()) return ChannelSendResult.Failed("出站文本为空")

        val text = outbound as? ChannelOutbound.Text
            ?: return ChannelSendResult.Failed("不支持的出站形状：" + outbound.javaClass.simpleName)

        val target = text.replyTo.trim()
        if (target.isEmpty()) {
            return ChannelSendResult.Failed(
                "缺少被动锚点：replyTo 为空（微信出站的锚点是收件人的 wechatUserId）",
            )
        }

        // 契约要求：实现**不得**抛异常表示失败，一律返回 ChannelSendResult。
        return try {
            when (val outcome = sender.sendText(target, text.text)) {
                is WeChatSendOutcome.Sent -> ChannelSendResult.Sent(messageRef = outcome.messageRef)
                is WeChatSendOutcome.Failed -> ChannelSendResult.Failed(outcome.reason)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            ChannelSendResult.Failed(
                "微信出站异常：" + (failure.message ?: failure.javaClass.simpleName),
            )
        }
    }

    override fun close() {
        closed = true
        scope.cancel()
        _state.value = ChannelConnectionState.DISCONNECTED
    }
}
