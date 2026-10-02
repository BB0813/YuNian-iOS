package com.yunian.ai.feature.qqbot.channel

import com.yunian.ai.domain.ChannelKeys
import com.yunian.ai.domain.channel.ChannelAdapter
import com.yunian.ai.domain.channel.ChannelCapabilities
import com.yunian.ai.domain.channel.ChannelConnectionState
import com.yunian.ai.domain.channel.ChannelOutbound
import com.yunian.ai.domain.channel.ChannelSendResult
import com.yunian.ai.domain.channel.ChannelSession
import com.yunian.ai.feature.qqbot.data.QQBotMessageRepository
import com.yunian.ai.feature.qqbot.data.model.QQInboundEvent
import com.yunian.ai.feature.qqbot.data.model.SendMessageResponse
import com.yunian.ai.feature.qqbot.data.network.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ 机器人通道适配器（[ChannelAdapter] 的首个真实实现）。
 *
 * ## 与既有代码的关系：**只读包装，零行为改动**
 *
 * 本类不新建任何连接、心跳、重连或收发逻辑——它把既有的 [QQBotMessageRepository]
 * 包一层，接到 [ChannelAdapter] 契约上。QQ 的连接与消息时序完全不变
 * （QQBotWebSocketClient / QQBotChatBridge / QQBotForegroundService 一行未动）。
 *
 * 包装的接缝是 [QQBotChannelSender]：仓库经 [QQBotChannelSender.fromRepository]
 * 适配成窄接口，因此本类可在纯 JVM 单测里用替身驱动（无需 Android Context）。
 *
 * ## capabilities 是**诚实声明**（不是理想值）
 *
 * | 位 | 值 | 依据 |
 * |---|---|---|
 * | inboundText | true | 文本入站已接（extractText 被桥接层消费） |
 * | inboundImage | **false** | extractImageUrl **零调用**，入站 attachments 无人消费 → 用户发图 = 发空文本 |
 * | outboundText | true | sendTextMessage 被桥接层消费 |
 * | outboundImage | true | sendImageMessage（上传 file_info → msg_type=7）已被桥接层消费 |
 * | typingIndicator | **false** | 协议枚举 msg_type=6（INPUT_NOTIFY）已声明但**全仓从未发送过** |
 * | proactiveSend | **false** | [ChannelSession.send] 100% 被动：`SendTextRequest.msgId = event.raw.id`，**必须**带锚点。⚠️ QQ 的**主动发送**能力**不走这一位**——它经 `ChannelOutboundEvents.REQUEST` 插件事件由 [QQBotChannelPlugin] 作为订阅方处理（见 §17.6 裁定） |
 * | deliveryReceipt | **false** | 只校验 HTTP 2xx，**没有任何 ack** |
 * | stickers | **false** | feature:qqbot 全模块无 StickerManager 引用，无表情通路 |
 *
 * deliveryReceipt = false 是本次最重要的诚实声明：它让框架能区分
 * 「发出去就算成功」与「能确认对方收到」——QQ 只属于前者。
 *
 * ## 惰性
 *
 * [senderSupplier] **只在 [start] 被调用时求值**：装配期（插件 setup）不会触碰 QQ 仓库、
 * TokenStore、DataStore 或任何网络，因此接通装配链路对运行时行为零影响。
 *
 * @param senderSupplier 出站窄接口的供应函数（生产 = 既有仓库的只读包装）。
 * @param sessionFactory 会话工厂（默认 [QQBotChannelSession]；单测可注入替身）。
 */
class QQBotChannelAdapter(
    private val senderSupplier: () -> QQBotChannelSender = {
        throw IllegalStateException(
            "QQ 通道出站供应函数未注入：生产由 YuNianApplication 传入既有 " +
                "QQBotMessageRepository 的包装；本任务只接装配链路，不启动真实会话"
        )
    },
    private val sessionFactory: (QQBotChannelSender) -> ChannelSession = { sender ->
        QQBotChannelSession(sender)
    },
) : ChannelAdapter {

    override val channelKey: String = KEY

    override val displayName: String = "QQ 机器人"

    override val capabilities: ChannelCapabilities = CAPABILITIES

    override suspend fun start(): ChannelSession = sessionFactory(senderSupplier())

    companion object {
        /** 通道稳定标识（唯一来源 [ChannelKeys.QQBOT]，禁止散落字面量）。 */
        const val KEY: String = ChannelKeys.QQBOT

        /** 诚实能力声明（逐位依据见类注释表格）。 */
        val CAPABILITIES: ChannelCapabilities = ChannelCapabilities(
            inboundText = true,
            inboundImage = false,
            outboundText = true,
            outboundImage = true,
            typingIndicator = false,
            proactiveSend = false,
            deliveryReceipt = false,
            stickers = false,
        )

        /** 用**既有仓库**构造适配器（生产入口：只包装，不接管生命周期）。 */
        fun ofRepository(repository: QQBotMessageRepository): QQBotChannelAdapter =
            QQBotChannelAdapter(
                senderSupplier = { QQBotChannelSender.fromRepository(repository) },
            )
    }
}

/**
 * QQ 出站窄接口（适配器与既有仓库之间的接缝）。
 *
 * 只暴露契约真正需要的东西，使 [QQBotChannelSession] 可在纯 JVM 单测里用替身驱动。
 */
interface QQBotChannelSender {

    /** 连接状态（唯一事实来源仍是既有 [QQBotMessageRepository.connectionState]）。 */
    val connectionState: StateFlow<ConnectionState>

    /** 入站事件流（既有 [QQBotMessageRepository.incomingEvents]，只读订阅）。 */
    val incomingEvents: Flow<QQInboundEvent>

    /** 按锚点（QQMessageEvent.id）发文本（既有 [QQBotMessageRepository.sendTextMessage]）。 */
    suspend fun sendText(event: QQInboundEvent, text: String): Result<SendMessageResponse?>

    companion object {
        /** 把既有仓库适配成窄接口（**只转发，不加任何逻辑**）。 */
        fun fromRepository(repository: QQBotMessageRepository): QQBotChannelSender =
            object : QQBotChannelSender {
                override val connectionState: StateFlow<ConnectionState> =
                    repository.connectionState

                override val incomingEvents: Flow<QQInboundEvent> =
                    repository.incomingEvents

                override suspend fun sendText(
                    event: QQInboundEvent,
                    text: String,
                ): Result<SendMessageResponse?> = repository.sendTextMessage(event, text)
            }
    }
}

/**
 * QQ 通道会话：把既有连接状态映射到契约的 [ChannelConnectionState]，并转发文本出站。
 *
 * 刻意**不**持有连接生命周期：close() 只把本会话标记为已关闭，**不**断开
 * [QQBotMessageRepository]——连接归既有 QQBotForegroundService / 桥接层管，
 * 本类改动它们的行为就等于改动运行时行为（本任务明确禁止）。
 *
 * @param sender 出站窄接口（生产 = 既有仓库的只读包装；单测 = 替身）。
 * @param scope 用于订阅连接状态与入站事件的作用域（默认自建，close() 时取消）。
 */
class QQBotChannelSession(
    private val sender: QQBotChannelSender,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) : ChannelSession {

    private val _state = MutableStateFlow(ChannelConnectionState.DISCONNECTED)

    /** 入站事件按 raw.id 建索引：出站锚点即该 id（与既有 msg_id 语义一致）。 */
    private val inboundById = ConcurrentHashMap<String, QQInboundEvent>()

    @Volatile
    private var closed = false

    override val state: StateFlow<ChannelConnectionState> = _state.asStateFlow()

    init {
        scope.launch {
            sender.connectionState.collect { _state.value = it.toChannelConnectionState() }
        }
        scope.launch {
            sender.incomingEvents.collect { rememberInbound(it) }
        }
    }

    /** 记录一条入站事件（供出站锚点解析）。 */
    fun rememberInbound(event: QQInboundEvent) {
        if (inboundById.size >= MAX_CACHED_INBOUND) inboundById.clear()
        inboundById[event.raw.id] = event
    }

    /**
     * 发送一条出站消息（**被动回复**，[ChannelOutbound] 只有这一个变体）。
     *
     * 任何前置状态不满足（会话已关闭 / 文本为空 / 锚点缺失）都返回
     * [ChannelSendResult.Failed] 并给出具体原因，**绝不静默丢弃**。
     *
     * ⚠️ **主动发送不走这里**（§17.6 裁定：`send(outbound)` 是传输契约、把通道当被调用方，
     * 与投影模型形状不符）。主动发送经 `ChannelOutboundEvents.REQUEST` 插件事件、
     * 由 [QQBotChannelPlugin] 作为**订阅方**处理。
     */
    override suspend fun send(outbound: ChannelOutbound): ChannelSendResult {
        if (closed) return ChannelSendResult.Failed("通道会话已关闭")
        if (outbound.text.isBlank()) return ChannelSendResult.Failed("出站文本为空")

        val text = outbound as? ChannelOutbound.Text
            ?: return ChannelSendResult.Failed("不支持的出站形状：" + outbound.javaClass.simpleName)

        val event = inboundById[text.replyTo]
            ?: return ChannelSendResult.Failed(
                "找不到锚点对应的入站消息：" + text.replyTo + "（QQ 被动回复必须带锚点）"
            )

        return sender.sendText(event, text.text).fold(
            onSuccess = { ChannelSendResult.Sent(messageRef = it?.id) },
            onFailure = { ChannelSendResult.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }

    override fun close() {
        closed = true
        scope.cancel()
        _state.value = ChannelConnectionState.DISCONNECTED
    }

    private companion object {
        /** 会话本地缓存上限（与既有 seenMessageIds 的 1000 条同量级，防无界增长）。 */
        const val MAX_CACHED_INBOUND = 1000
    }
}

/** 既有 [ConnectionState] → 契约 [ChannelConnectionState] 的映射（纯函数，可单测）。 */
internal fun ConnectionState.toChannelConnectionState(): ChannelConnectionState = when (this) {
    ConnectionState.DISCONNECTED -> ChannelConnectionState.DISCONNECTED
    ConnectionState.CONNECTING -> ChannelConnectionState.CONNECTING
    ConnectionState.CONNECTED -> ChannelConnectionState.CONNECTED
    ConnectionState.RECONNECTING -> ChannelConnectionState.RECONNECTING
    ConnectionState.AUTH_FAILED -> ChannelConnectionState.FAILED
}
