package com.yunian.ai.feature.wechat.channel

import com.yunian.ai.wechat.ilink.IlinkAccount
import com.yunian.ai.wechat.ilink.IlinkSessionStore
import com.yunian.ai.wechat.outbox.WeChatOutboxCoordinator
import com.yunian.ai.wechat.transport.WeChatTransportPort
import kotlinx.coroutines.flow.Flow

/**
 * 微信**出站发送**的宿主接缝（通道插件与既有微信通路之间的唯一接缝）。
 *
 * ## 为什么不直接吃 WeChatMessageRepository / WeChatServiceLocator
 *
 * 那两者依赖 Android Context / Room / DataStore / Retrofit，而 `:feature:wechat` 的测试源集
 * 只有 junit、**没有 Robolectric**。把出站收敛成这个窄接口后，插件、适配器与会话的
 * 单测可以用内存替身驱动——**被测的插件代码本身是真代码**，不是复制品。
 *
 * ## 与 [WeChatTransportPort] 的分工
 *
 * - [WeChatTransportPort] 是 **core:wechat 的传输契约**（一次 sendText / sendImage），
 *   **不含**任何前置状态判定：token 缺失时它只会把 SDK 的异常包成 `Result.failure`；
 * - 本接口是**通道插件面向的出站语义**：先判「登录 / context_token 是否存在且在 24h 内」，
 *   不满足就返回**具体可读的失败**，满足才落到 transport。
 *
 * 两条通路刻意不共用类型：前置条件与失败模式不同（一个要目标 + 会话窗口，一个是纯传输）。
 *
 * ## 为什么**不**走 outbox
 *
 * [WeChatOutboxCoordinator.enqueue] 的返回值只证明「**已入队**」，不证明「已发出」；
 * 排完队再由 drain 异步发送时，插件无法在不读 Room 行状态的前提下如实回答
 * [com.yunian.ai.domain.channel.ChannelOutboundResult.Sent] 还是 `Failed`。
 * 而本仓的红线是「**禁止静默失败**」（见 `WeChatOutboundPortImpl` 静默返回 `SKIPPED` 的教训）。
 * 因此主动发送走 **一次同步的 transport 调用**：成功就是成功，失败带回具体原因。
 * （被动回复 / AI 自动回复仍走既有的 outbox 通路，本批一行未改。）
 */
interface WeChatChannelSender {

    /**
     * 登录态（唯一事实来源仍是既有 `WeChatTokenStore.accountFlow`）。
     *
     * 只用于 [WeChatChannelSession] 的连接态投影；发送前置判定走
     * [IlinkSessionStore.getSessionAccount]（**实时**读，不用这个可能滞后的流）。
     */
    val loggedIn: Flow<Boolean>

    /**
     * 解析本次出站的**收件人**（收件人语义的**唯一入口**）。
     *
     * ## 语义（用户裁定：iLink 只允许给**绑定的**微信账号发消息）
     *
     * - [target] 为空 / 空白 → 解析出**绑定的微信用户**。这不是「猜」：
     *   协议层只允许发给它，因此没有歧义——「你给我微信发条消息」是**主路径**；
     * - [target] 非空且**等于**绑定用户 → 解析出它；
     * - [target] 非空但**不等于**绑定用户 → [WeChatRecipientResolution.Failed]：
     *   iLink 协议**只允许**发给绑定的那个微信账号，无法发给其他人或群。
     *   **绝不静默忽略用户的显式指定**，也**绝不**改写成绑定用户后照发；
     * - 无法**唯一确定**绑定用户（未登录 / 绑定用户未知 / 候选不唯一）→
     *   [WeChatRecipientResolution.Failed]，**绝不静默挑一个**。
     *
     * 实现**不得抛异常表示失败**：一律返回 [WeChatRecipientResolution]。
     */
    suspend fun resolveRecipient(target: String?): WeChatRecipientResolution

    /**
     * 给指定微信用户发一条文本。
     *
     * [toUserId] 是**微信用户 id**（与 `IlinkAccount.ilinkUserId`、入站 `from_user_id`、
     * 出站 iLink `to_user_id` 同一 id 空间）。调用方应当先用 [resolveRecipient] 把用户
     * 给出的 target 解析成这个 id。
     *
     * 实现**不得抛异常表示失败**：一律返回 [WeChatSendOutcome]。
     * 目标必须**显式给出**——本接口没有、也不得有任何「未指定就群发」的语义
     * （「未指定 → 绑定的微信用户」这条规则**只**由 [resolveRecipient] 承担）。
     */
    suspend fun sendText(toUserId: String, text: String): WeChatSendOutcome

    companion object {

        /** 未登录的失败原因（具体、可读、可操作）。 */
        const val NOT_LOGGED_IN_REASON: String =
            "微信通道未登录：请先在予念的「设置 → 微信」里完成扫码登录，再重试发送"

        /**
         * **无法唯一确定**绑定微信账号时的失败原因。
         *
         * 该状态可达：`IlinkClientManager.pollLoginStatus` 用 `status.ilinkUserId.orEmpty()`
         * 兜底，服务器没返回 `ilink_user_id` 时绑定用户就是空串。此时本通道退到
         * 「本账号持有 context_token 的微信用户」这一候选集，**恰好一个**才认；
         * 0 个或多个一律走这条失败——**绝不静默挑一个**。
         */
        const val BOUND_USER_UNRESOLVED_REASON: String =
            "微信通道无法确定绑定的微信账号：ilink 协议只允许给绑定的微信账号发消息，" +
                "但当前会话没有可唯一确定的绑定用户" +
                "（扫码登录未返回 ilink_user_id，且本账号持有 context_token 的微信用户不是恰好一个）。" +
                "为避免猜错收件人，本次未发送任何消息；" +
                "请在予念「设置 → 微信」里重新扫码绑定后重试。"

        /**
         * 显式指定的收件人**不是**绑定微信账号时的失败原因。
         *
         * 三个要点缺一不可（安全红线）：说明「ilink 协议」「只允许」「绑定」，
         * 让模型 / 用户一眼看懂为什么不能发。
         *
         * @param maskedTarget 用户显式指定的收件人（**已脱敏**）。
         * @param maskedBound 绑定的微信账号（**已脱敏**）。
         */
        fun notBoundUserReason(maskedTarget: String, maskedBound: String): String =
            "微信通道不能发给 " + maskedTarget + "：ilink 协议只允许给绑定的微信账号（" +
                maskedBound + "）发消息，无法发给其他人或群。" +
                "本次未发送任何消息；若要给本人发消息，请把 target 留空" +
                "（留空即发给绑定的微信账号）。"

        /** 与既有日志一致的脱敏（失败原因会回到模型 / 用户可见处，不回显完整 id）。 */
        fun maskUserId(userId: String): String {
            if (userId.length <= 6) return "***"
            return userId.take(3) + "***" + userId.takeLast(2)
        }

        /**
         * 用**既有传输通路**构造出站接缝（生产入口：只包装，不接管生命周期）。
         *
         * **不**启动轮询、**不**发心跳、**不**碰重连——本函数只造一个对象。
         *
         * @param transport 既有传输实现（生产 = `SdkWeChatTransport`，与 outbox 用的是同一个类）。
         * @param sessionStore 既有会话存储（生产 = `WeChatTokenStore`，同时实现 `IlinkSessionStore`）。
         * @param loggedIn 登录态流（生产 = `tokenStore.accountFlow.map { it != null }`）。
         * @param nowMs 时钟（context_token 年龄判定用；可注入以便单测构造过期）。
         */
        fun fromTransport(
            transport: WeChatTransportPort,
            sessionStore: IlinkSessionStore,
            loggedIn: Flow<Boolean>,
            nowMs: () -> Long = { System.currentTimeMillis() },
        ): WeChatChannelSender = TransportWeChatChannelSender(
            transport = transport,
            sessionStore = sessionStore,
            loggedIn = loggedIn,
            nowMs = nowMs,
        )
    }
}

/** 一次微信出站发送的结果（通道侧语义，由插件 / 会话映射到各自的契约结果）。 */
sealed class WeChatSendOutcome {

    /** 已交给通道发出（[WeChatTransportPort] 不返回消息标识，故恒为 null）。 */
    data class Sent(val messageRef: String?) : WeChatSendOutcome()

    /** 失败（原因具体、可读，**禁止静默失败**）。 */
    data class Failed(val reason: String) : WeChatSendOutcome()
}

/**
 * [WeChatChannelSender] 的生产实现：**把静默跳过变成如实失败**。
 *
 * 收件人由 [resolveRecipient] **单独**解析（插件在调用 [sendText] 之前先解析）：
 * `target` 为空 → 绑定的微信用户；`target` 非空 → 必须等于绑定用户；
 * 无法唯一确定 → 如实失败。见 [resolveRecipient] 的 KDoc。
 *
 * [sendText] 自身的逐条前置判定（顺序即优先级）：
 * 1. 收件人为空 → 失败（**绝不放行成「发给所有人」**）；
 * 2. 文本为空白 → 失败；
 * 3. 未登录（`getSessionAccount() == null`）→ 失败并给出可操作提示
 *    （**不是** `WeChatOutboundPortImpl` 那种静默 `SKIPPED`）；
 * 4. `context_token` 缺失 → 失败（iLink 协议只能回复对方先发来的消息）；
 * 5. `context_token` 年龄 > 24h → 失败（与 outbox 的 `CONTEXT_TOKEN_MAX_AGE_MS` **同一常量**，
 *    且同样**不重试**：判死是协议事实，不是网络抖动）；
 * 6. 才落到 [WeChatTransportPort.sendText]，异常经 `Result` 转成失败原因。
 *
 * 第 5 步的边界与既有 outbox **逐字一致**：`getContextTokenSavedAt` 返回 null
 * （旧格式记录，`savedAtMs = 0`）时**不做**年龄判定——无法证明过期就不判死。
 */
internal class TransportWeChatChannelSender(
    private val transport: WeChatTransportPort,
    private val sessionStore: IlinkSessionStore,
    override val loggedIn: Flow<Boolean>,
    private val nowMs: () -> Long,
) : WeChatChannelSender {

    override suspend fun resolveRecipient(target: String?): WeChatRecipientResolution {
        val account = sessionStore.getSessionAccount()
            ?: return WeChatRecipientResolution.Failed(WeChatChannelSender.NOT_LOGGED_IN_REASON)

        val bound = boundWeChatUserId(account)
            ?: return WeChatRecipientResolution.Failed(
                WeChatChannelSender.BOUND_USER_UNRESOLVED_REASON,
            )

        val explicit = target?.trim().orEmpty()
        if (explicit.isNotEmpty() && explicit != bound) {
            // 用户显式指定了一个**不是绑定账号**的收件人：
            // ilink 协议不支持发给其他人 / 群，如实失败——不改写、不尝试、不静默忽略。
            return WeChatRecipientResolution.Failed(
                WeChatChannelSender.notBoundUserReason(
                    maskedTarget = WeChatChannelSender.maskUserId(explicit),
                    maskedBound = WeChatChannelSender.maskUserId(bound),
                ),
            )
        }

        // target 为空 / 空白，或恰好等于绑定账号 → 唯一的合法收件人。
        return WeChatRecipientResolution.Resolved(bound)
    }

    /**
     * 绑定的微信用户 id —— iLink 协议**唯一**允许发送的对象。
     *
     * ## 权威来源：`IlinkAccount.ilinkUserId`
     *
     * 它是扫码登录 `confirmed` 时服务器返回的 `ilink_user_id`，即**扫码绑定这台 bot 的
     * 那个微信用户**。本仓已把它写死成协议事实（`WeChatMessageRepository.isOutboundEcho`
     * 的注记 + `WeChatOutboundEchoTest`）：`ilink_user_id` 是**对话对端**、
     * 与入站 `from_user_id` 同值，`ilink_bot_id` 才是 bot 自己；
     * 官方 `bot.sendMessage` 的发送目标正是这个 userId。
     * 因此它与 [sendText] 的 `toUserId` **同一 id 空间**。
     *
     * ## 为什么不用别的候选
     *
     * - `getAllWechatUserMappings()` / `getWechatUserIdsForCompanionId()` 是**本地可编辑**的
     *   「微信用户 → 伴侣」映射（首次入站自动建、也可手工加），可多可旧，
     *   它回答的是「这个微信用户对应哪个伴侣」，**不是**「协议允许我发给谁」；
     * - `getContextTokens(accountId)` 是「谁有活跃会话 token」，既可能为空
     *   （用户还没发过消息）也可能有多个，**无法唯一确定**绑定关系，
     *   因此它只配当 [ilinkUserId][IlinkAccount.ilinkUserId] **缺失时**的兜底候选集。
     *
     * ## 空值兜底（该状态可达）
     *
     * `IlinkClientManager.pollLoginStatus` 用 `status.ilinkUserId.orEmpty()` 兜底，
     * 服务器没返回 `ilink_user_id` 时这里就是空串。那时取
     * 「本账号持有 context_token 的微信用户」候选集：**恰好一个**才认；
     * 0 个或多个一律返回 null（调用方转成 [WeChatChannelSender.BOUND_USER_UNRESOLVED_REASON]）
     * ——**绝不静默在多个候选里挑一个**。
     */
    private suspend fun boundWeChatUserId(account: IlinkAccount): String? {
        val declared = account.ilinkUserId.trim()
        if (declared.isNotEmpty()) return declared

        return sessionStore.getContextTokens(account.accountId)
            .keys
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .singleOrNull()
    }

    override suspend fun sendText(toUserId: String, text: String): WeChatSendOutcome {
        val target = toUserId.trim()
        if (target.isEmpty()) {
            return WeChatSendOutcome.Failed("微信出站缺少收件人：wechatUserId 为空，未发送任何消息")
        }
        if (text.isBlank()) {
            return WeChatSendOutcome.Failed("出站文本为空：未发送任何消息")
        }

        val account = sessionStore.getSessionAccount()
            ?: return WeChatSendOutcome.Failed(WeChatChannelSender.NOT_LOGGED_IN_REASON)

        val contextToken = sessionStore.getContextToken(account.accountId, target)
        if (contextToken.isNullOrBlank()) {
            return WeChatSendOutcome.Failed(
                WeChatOutboxCoordinator.SEND_BLOCKED_NO_TOKEN +
                    "（收件人 " + WeChatChannelSender.maskUserId(target) + "）",
            )
        }

        val savedAt = sessionStore.getContextTokenSavedAt(account.accountId, target)
        if (savedAt != null && nowMs() - savedAt > WeChatOutboxCoordinator.CONTEXT_TOKEN_MAX_AGE_MS) {
            return WeChatSendOutcome.Failed(
                WeChatOutboxCoordinator.SEND_BLOCKED_EXPIRED +
                    "（收件人 " + WeChatChannelSender.maskUserId(target) + "）",
            )
        }

        return transport.sendText(target, text, contextToken).fold(
            onSuccess = { WeChatSendOutcome.Sent(messageRef = null) },
            onFailure = { error ->
                WeChatSendOutcome.Failed(
                    "微信发送失败：" + (error.message ?: error.javaClass.simpleName),
                )
            },
        )
    }
}
