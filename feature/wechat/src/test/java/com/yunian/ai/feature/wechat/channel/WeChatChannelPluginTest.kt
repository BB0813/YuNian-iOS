package com.yunian.ai.feature.wechat.channel

import com.yunian.ai.domain.ChannelKeys
import com.yunian.ai.domain.channel.ChannelAdapter
import com.yunian.ai.domain.channel.ChannelCapabilities
import com.yunian.ai.domain.channel.ChannelConnectionState
import com.yunian.ai.domain.channel.ChannelOutbound
import com.yunian.ai.domain.channel.ChannelOutboundEvents
import com.yunian.ai.domain.channel.ChannelOutboundRequest
import com.yunian.ai.domain.channel.ChannelOutboundResult
import com.yunian.ai.domain.channel.ChannelSendResult
import com.yunian.ai.domain.channel.ChannelSession
import com.yunian.ai.domain.channel.MutableChannelRegistry
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginEventListener
import com.yunian.ai.domain.plugin.PluginEventResult
import com.yunian.ai.domain.plugin.PluginHost
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginLoadResult
import com.yunian.ai.domain.plugin.PluginServices
import com.yunian.ai.feature.wechat.ui.WeChatSettingsSection
import com.yunian.ai.uicommon.plugin.PluginSettingsCategory
import com.yunian.ai.uicommon.plugin.PluginSettingsPresentation
import com.yunian.ai.uicommon.plugin.PluginSettingsSections
import com.yunian.ai.wechat.ilink.IlinkAccount
import com.yunian.ai.wechat.ilink.IlinkSessionStore
import com.yunian.ai.wechat.outbox.WeChatOutboxCoordinator
import com.yunian.ai.wechat.transport.WeChatTransportPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `channel.wechat` 通道插件测试（P4-2）：微信侧的**订阅方**语义 + 适配器的诚实能力声明。
 *
 * ## 覆盖的验收点（逐条对应任务书）
 *
 * 1. **不匹配 `channelKey` 时返回 null 放行**——绝不吞掉 QQ 的请求；
 * 2. **`target` 为空 / 空白 → 发给绑定的微信用户**（修正后的主路径：
 *    用户说「你给我微信发条消息」时模型省略 `target`，必须成功）；
 * 3. **`target` 恰好等于绑定用户 → 成功**；
 * 4. **`target` 是非绑定用户 → Failed** 且**发送方零调用**（ilink 协议只允许发给绑定账号）；
 * 5. **绑定用户无法唯一确定（0 个 / 多个候选）→ Failed** 且发送方零调用（绝不静默挑一个）；
 * 6. **未登录 → Failed** 且原因具体；
 * 7. **`context_token` 缺失 / 过期 → Failed** 且原因具体（不得静默跳过）；
 * 8. **发送异常 → Failed**（不得让异常穿出成「无人认领」）；
 * 9. **插件卸载后订阅消失**（effect 生效）。
 *
 * ## 收件人语义（用户裁定，推翻旧前提）
 *
 * iLink 协议层**只允许给绑定的那个微信账号发消息**——「发给别人」和「群发」
 * 在协议上不存在，因此「`target` 为空时该发给谁」**没有歧义**。
 * 旧实现（`target` 为空 → `NEED_EXPLICIT_TARGET_REASON`）已被推翻并删除。
 * 「不得静默在多个候选里挑一个」这条原则**保留**，由
 * [WeChatChannelSender.resolveRecipient] 承担。
 *
 * 解析语义一律用 `realSender(...)`（= 真 [WeChatChannelSender.fromTransport]）
 * 驱动**真代码**验证；`RecordingSender` / `ThrowingSender` 这类替身的
 * `resolveRecipient` 只是最笨的直通实现，**不**用来证明解析规则。
 *
 * ## 替身策略（与 `QQBotChannelPluginTest` 同构）
 *
 * `:feature:wechat` 不能依赖 `:core:agent`（core 不得依赖 feature；feature 之间也不得
 * 互相依赖，而 `core:agent` 也不在 feature:wechat 的依赖里），因此这里用与
 * [PluginHost] / [MutableChannelRegistry] / `PluginEventBus` **语义同构**的最小替身
 * 驱动**真实的**插件、适配器与会话代码——被测代码本身是真代码。
 *
 * **发送链路上的替身只有两个**（都在 core:wechat 的契约层）：
 * [IlinkSessionStore] 与 [WeChatTransportPort]；插件与
 * [WeChatChannelSender.fromTransport] 都是真代码。
 *
 * ## 在旧实现下为什么失败
 *
 * 旧实现里 `WeChatChannelPlugin` / `WeChatChannelAdapter` / `WeChatChannelSender` 都不存在，
 * 本文件**根本编译不过**——编译期不可达，不是行为回归测试，如实说明。
 */
class WeChatChannelPluginTest {

    // ── 替身 1：事件总线（与 PluginEventBus.bail 同构）──

    /**
     * 宿主级共享订阅表的最小替身。
     *
     * 关键语义逐条对齐 `PluginEventBus`：**同步顺序**派发、**首个 bail 短路**、
     * 监听器返回 null 即放行、监听器抛异常按「无结果」处理（不掐断链）。
     * 共享同一张表才能验证「微信插件不吞掉 QQ 的请求」。
     */
    private class FakeBailBus {
        private val byEvent = LinkedHashMap<String, MutableList<PluginEventListener>>()

        fun subscribe(event: String, handler: PluginEventListener): () -> Unit {
            byEvent.getOrPut(event) { mutableListOf() } += handler
            return { byEvent[event]?.remove(handler) }
        }

        fun listenerCount(event: String): Int = byEvent[event]?.size ?: 0

        fun bail(event: String, payload: Any): PluginEventResult {
            for (handler in byEvent[event]?.toList().orEmpty()) {
                val raw = try {
                    handler.onEvent(payload)
                } catch (_: Throwable) {
                    null
                }
                when (raw) {
                    null -> continue
                    is PluginEventResult -> if (raw.isBailed) return raw
                    else -> return PluginEventResult.bail(raw)
                }
            }
            return PluginEventResult.none
        }
    }

    // ── 替身 2：插件上下文（服务表 + effect + 订阅）──

    private class FakeContext(
        private val services: Map<String, Any>,
        private val bus: FakeBailBus,
    ) : PluginContext {
        val effects = mutableListOf<Pair<() -> Unit, String>>()

        override fun <T : Any> provide(key: String, service: T) = Unit

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> inject(key: String): T =
            services[key] as? T
                ?: throw IllegalStateException("插件依赖服务未提供: " + key)

        override fun effect(disposer: () -> Unit, label: String) {
            effects += disposer to label
        }

        override fun on(event: String, handler: (Any) -> Unit) = Unit

        override fun onBail(event: String, handler: PluginEventListener) {
            // 与 PluginContextImpl 一致：订阅即 effect（卸载时退订）。
            effect(bus.subscribe(event, handler), "unsubscribe:" + event)
        }

        override fun bail(event: String, payload: Any): PluginEventResult = bus.bail(event, payload)

        /** 逆序执行（与 PluginContextImpl.disposeAll 一致）。 */
        fun disposeAll() {
            effects.asReversed().forEach { it.first() }
            effects.clear()
        }
    }

    // ── 替身 3：通道注册中心 ──

    private class FakeRegistry : MutableChannelRegistry {
        private val entries = LinkedHashMap<String, ChannelAdapter>()

        val registerCalls = mutableListOf<String>()
        val unregisterCalls = mutableListOf<String>()

        override fun register(pluginId: String, adapter: ChannelAdapter) {
            registerCalls += pluginId + "/" + adapter.channelKey
            entries[adapter.channelKey] = adapter
        }

        override fun unregister(pluginId: String, channelKey: String): Boolean {
            unregisterCalls += pluginId + "/" + channelKey
            return entries.remove(channelKey) != null
        }

        override fun adapters(): List<ChannelAdapter> = entries.values.toList()

        override fun adapter(channelKey: String): ChannelAdapter? = entries[channelKey]
    }

    /** 适配器替身（只用于断言 `setup` 的副作用形状，不参与发送链路）。 */
    private class FakeAdapter(
        override val channelKey: String,
        override val displayName: String = "fake:" + channelKey,
    ) : ChannelAdapter {
        override val capabilities: ChannelCapabilities = ChannelCapabilities(
            inboundText = true,
            inboundImage = true,
            outboundText = true,
            outboundImage = true,
            typingIndicator = false,
            proactiveSend = false,
            deliveryReceipt = false,
            stickers = true,
        )

        var startCount: Int = 0
            private set

        override suspend fun start(): ChannelSession {
            startCount++
            return object : ChannelSession {
                override val state: StateFlow<ChannelConnectionState> =
                    MutableStateFlow(ChannelConnectionState.DISCONNECTED)

                override suspend fun send(outbound: ChannelOutbound): ChannelSendResult =
                    ChannelSendResult.Sent("fake")

                override fun close() = Unit
            }
        }
    }

    /**
     * 宿主替身：只实现本测试需要的「按 id 分派 + 装载态 + fail-closed 依赖校验」语义。
     *
     * 真实 [PluginHost] 的语义（manifest 校验、effect 逆序回滚）已由 core:agent 的测试覆盖；
     * 这里只驱动真实插件的 `setup()`。
     */
    private class FakeHost(private val services: Map<String, Any>) : PluginHost {
        private val registered = LinkedHashMap<String, LianYuPlugin>()
        private val loadedIds = LinkedHashSet<String>()
        private val contexts = LinkedHashMap<String, FakeContext>()
        private val bus = FakeBailBus()

        override fun register(plugin: LianYuPlugin) {
            registered[plugin.id] = plugin
        }

        override fun unregister(id: String): Boolean {
            unload(id)
            return registered.remove(id) != null
        }

        override fun plugin(id: String): LianYuPlugin? = registered[id]

        override fun isRegistered(id: String): Boolean = registered.containsKey(id)

        override fun plugins(): List<LianYuPlugin> = registered.values.sortedBy { it.id }

        override fun pluginsOf(kind: PluginKind): List<LianYuPlugin> =
            plugins().filter { it.kind == kind }

        override fun load(id: String, configJson: String?): PluginLoadResult {
            val plugin = registered[id] ?: return PluginLoadResult.NotFound
            if (id in loadedIds) return PluginLoadResult.Loaded
            val missing = plugin.requires.filter { it !in services }
            if (missing.isNotEmpty()) {
                return PluginLoadResult.Failed(
                    "插件 " + id + " 缺少依赖服务: " + missing + "（宿主未预置）",
                )
            }
            val ctx = FakeContext(services, bus)
            return try {
                plugin.setup(ctx)
                contexts[id] = ctx
                loadedIds += id
                PluginLoadResult.Loaded
            } catch (t: Throwable) {
                ctx.disposeAll()
                PluginLoadResult.Failed("插件 " + id + " 装配失败: " + t.message)
            }
        }

        override fun unload(id: String): Boolean {
            val ctx = contexts.remove(id) ?: return false
            ctx.disposeAll()
            loadedIds -= id
            return true
        }

        override fun isLoaded(id: String): Boolean = id in loadedIds

        override fun loadedIds(): Set<String> = loadedIds.toSet()

        override fun loadBlueprint(
            blueprint: com.yunian.ai.domain.plugin.PluginBlueprint,
        ): com.yunian.ai.domain.plugin.BlueprintLoadResult =
            com.yunian.ai.domain.plugin.BlueprintLoadResult.Applied(emptyList(), emptyList())
    }

    // ── 替身 4：发送链路（登录态 / context_token / 传输）──

    private class FakeSessionStore(
        private var account: IlinkAccount? = ACCOUNT,
    ) : IlinkSessionStore {

        private val tokens = mutableMapOf<String, String>()
        private val savedAt = mutableMapOf<String, Long>()

        fun putToken(userId: String, token: String?, savedAtMs: Long?) {
            if (token == null) tokens.remove(ACCOUNT.accountId + ":" + userId)
            else tokens[ACCOUNT.accountId + ":" + userId] = token
            if (savedAtMs == null) savedAt.remove(ACCOUNT.accountId + ":" + userId)
            else savedAt[ACCOUNT.accountId + ":" + userId] = savedAtMs
        }

        fun logout() {
            account = null
        }

        override suspend fun getSessionAccount(): IlinkAccount? = account

        override suspend fun saveSessionAccount(account: IlinkAccount) {
            this.account = account
        }

        override suspend fun clearSessionAccount() {
            account = null
        }

        override suspend fun getCursor(): String = ""

        override suspend fun saveCursor(cursor: String) = Unit

        override suspend fun getContextToken(accountId: String, userId: String): String? =
            tokens[accountId + ":" + userId]

        override suspend fun getContextTokenSavedAt(accountId: String, userId: String): Long? =
            savedAt[accountId + ":" + userId]

        override suspend fun saveContextToken(accountId: String, userId: String, token: String) {
            tokens[accountId + ":" + userId] = token
        }

        override suspend fun getContextTokens(accountId: String): Map<String, String> {
            // 与真实现 WeChatTokenStore.getContextTokens **逐字一致**：剥掉 "accountId:"
            // 前缀，返回 Map<wechatUserId, token>。
            // （旧替身漏了 removePrefix，键会带前缀，污染「候选集」判定——本批修的是替身保真度。）
            val prefix = accountId + ":"
            return tokens.filterKeys { it.startsWith(prefix) }
                .mapKeys { (key, _) -> key.removePrefix(prefix) }
        }

        companion object {
            /**
             * 扫码登录 confirmed 的账号。
             *
             * `ilinkUserId` = **扫码绑定的那个微信用户**（iLink 唯一允许发送的对象），
             * 因此它必须与用例里使用的收件人 id（[BOUND_USER]）一致——
             * 这正是协议事实：入站 `from_user_id`、出站 `to_user_id`、
             * `ilink_user_id` 是同一个 id 空间。
             */
            val ACCOUNT = IlinkAccount(
                botToken = "bot-token",
                ilinkBotId = "bot-id",
                ilinkUserId = BOUND_USER,
                accountId = "default",
            )
        }
    }

    private class FakeTransport(
        private val result: Result<Unit> = Result.success(Unit),
    ) : WeChatTransportPort {

        val calls = mutableListOf<String>()

        override suspend fun sendText(
            toUserId: String,
            text: String,
            contextToken: String?,
        ): Result<Unit> {
            calls += listOf(toUserId, text, contextToken ?: "<null>").joinToString("|")
            return result
        }

        override suspend fun sendImage(
            toUserId: String,
            imageBytes: ByteArray,
            fileName: String,
            description: String?,
            contextToken: String?,
        ): Result<Unit> = Result.failure(UnsupportedOperationException("本测试不覆盖图片出站"))
    }

    /**
     * 记录调用的发送方（真插件的出站接缝替身，只用于**会话 / 装配**类用例）。
     *
     * [resolveRecipient] 在这里刻意是**最笨的直通实现**（显式 target 优先，否则绑定用户）：
     * 解析规则本身由 [WeChatChannelSender.fromTransport] 的生产实现承担，
     * 相关用例一律走 `realSender(...)` 驱动**真代码**，不靠这个替身证明。
     */
    private class RecordingSender(
        private val outcome: WeChatSendOutcome = WeChatSendOutcome.Sent(messageRef = null),
        override val loggedIn: Flow<Boolean> = MutableStateFlow(true),
        private val boundUserId: String = BOUND_USER,
    ) : WeChatChannelSender {
        val sent = mutableListOf<String>()

        override suspend fun resolveRecipient(target: String?): WeChatRecipientResolution =
            WeChatRecipientResolution.Resolved(
                target?.trim()?.takeIf { it.isNotEmpty() } ?: boundUserId,
            )

        override suspend fun sendText(toUserId: String, text: String): WeChatSendOutcome {
            sent += toUserId + "|" + text
            return outcome
        }
    }

    /**
     * 解析**一律失败**、且**一旦被调用发送就 AssertionError** 的替身。
     *
     * 用来证明插件在「解析不出收件人」之后**根本不往下走**（控制流级证明）。
     */
    private class ExplodingSender(
        private val resolution: WeChatRecipientResolution = WeChatRecipientResolution.Failed(
            "替身：无法确定收件人",
        ),
    ) : WeChatChannelSender {
        override val loggedIn: Flow<Boolean> = MutableStateFlow(true)

        override suspend fun resolveRecipient(target: String?): WeChatRecipientResolution = resolution

        override suspend fun sendText(toUserId: String, text: String): WeChatSendOutcome =
            throw AssertionError("解析未成功时不得调用任何发送（可能发给非绑定用户 / 广播）")
    }

    /** 直接抛异常的发送方：验证「异常不得穿出成无人认领」。 */
    private class ThrowingSender(private val error: Throwable) : WeChatChannelSender {
        override val loggedIn: Flow<Boolean> = MutableStateFlow(true)

        override suspend fun resolveRecipient(target: String?): WeChatRecipientResolution =
            WeChatRecipientResolution.Resolved(
                target?.trim()?.takeIf { it.isNotEmpty() } ?: BOUND_USER,
            )

        override suspend fun sendText(toUserId: String, text: String): WeChatSendOutcome =
            throw error
    }

    /**
     * **一旦被调用即 AssertionError** 的传输替身（同时记录被调用过的收件人）。
     *
     * 用它证明「收件人不合法 / 无法唯一确定时**根本没有**落到传输层」——
     * 比断言返回值更硬：真发出去会立刻炸成测试失败，而不是悄悄通过。
     */
    private class ExplodingTransport : WeChatTransportPort {
        val calls = mutableListOf<String>()

        override suspend fun sendText(
            toUserId: String,
            text: String,
            contextToken: String?,
        ): Result<Unit> {
            calls += toUserId
            throw AssertionError("收件人不合法：不得发起任何发送（可能发给非绑定用户 / 广播）")
        }

        override suspend fun sendImage(
            toUserId: String,
            imageBytes: ByteArray,
            fileName: String,
            description: String?,
            contextToken: String?,
        ): Result<Unit> = throw AssertionError("本测试不覆盖图片出站")
    }

    // ── 工具 ──

    private companion object {
        /**
         * 绑定的微信用户 id —— 扫码登录 confirmed 返回的 `ilink_user_id`，
         * iLink 协议**唯一**允许发送的对象。
         */
        const val BOUND_USER = "wx-user-1"

        /** 固定时钟（context_token 年龄判定用）。 */
        const val NOW = 1_700_000_000_000L
    }

    private fun pluginWith(
        sender: WeChatChannelSender?,
        adapter: ChannelAdapter = FakeAdapter(ChannelKeys.WECHAT),
    ): WeChatChannelPlugin = WeChatChannelPlugin(
        adapterFactory = { adapter },
        senderSupplier = { sender },
    )

    /**
     * **生产同源**的发送方：`WeChatChannelSender.fromTransport` 造出的真实现，
     * 只有 [IlinkSessionStore] / [WeChatTransportPort] 两个契约层替身。
     * 收件人解析规则的相关用例一律用它，保证被测的是**真代码**。
     */
    private fun realSender(
        transport: WeChatTransportPort = FakeTransport(),
        store: IlinkSessionStore = FakeSessionStore(),
        loggedIn: Flow<Boolean> = MutableStateFlow(true),
        nowMs: () -> Long = { System.currentTimeMillis() },
    ): WeChatChannelSender = WeChatChannelSender.fromTransport(
        transport = transport,
        sessionStore = store,
        loggedIn = loggedIn,
        nowMs = nowMs,
    )

    /** 已登录、且绑定用户持有 context_token 的会话存储（主路径的常见前置状态）。 */
    private fun loggedInStore(
        account: IlinkAccount = FakeSessionStore.ACCOUNT,
        token: String? = "tok",
    ): FakeSessionStore = FakeSessionStore(account).apply {
        if (token != null) putToken(BOUND_USER, token, NOW)
    }

    private fun request(
        channelKey: String = ChannelKeys.WECHAT,
        target: String? = BOUND_USER,
        text: String = "你好",
    ): ChannelOutboundRequest = ChannelOutboundRequest(
        channelKey = channelKey,
        target = target,
        text = text,
    )

    private fun failedReason(result: ChannelOutboundResult): String {
        assertTrue("必须是 Failed，实际是 " + result, result is ChannelOutboundResult.Failed)
        return (result as ChannelOutboundResult.Failed).reason
    }

    /** 轮询等待连接态（不依赖调度器实现细节，避免 flaky）。 */
    private fun awaitState(session: ChannelSession, expected: ChannelConnectionState) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (session.state.value == expected) return
            Thread.sleep(10)
        }
        assertEquals("连接态未在超时内到达", expected, session.state.value)
    }

    // ── 1. 插件身份与依赖声明 ──

    @Test
    fun `插件 id 是 channel_wechat 且 kind 是 ADAPTER`() {
        val plugin = pluginWith(RecordingSender())
        assertEquals("channel.wechat", plugin.id)
        assertEquals(PluginKind.ADAPTER, plugin.kind)
        assertEquals(ChannelKeys.WECHAT, WeChatChannelPlugin.CHANNEL_KEY)
        assertEquals("wechat", WeChatChannelPlugin.CHANNEL_KEY)
    }

    @Test
    fun `插件依赖声明为 CHANNELS 且清单与自描述一致`() {
        val plugin = pluginWith(RecordingSender())
        assertEquals(setOf(PluginServices.CHANNELS), plugin.requires)
        assertEquals(plugin.id, plugin.manifest.id)
        assertEquals(plugin.name, plugin.manifest.name)
        assertEquals(PluginKind.ADAPTER, plugin.manifest.kind)
        assertEquals(plugin.requires.sorted(), plugin.manifest.requires)
        assertEquals(plugin.configSchema, plugin.manifest.configSchema)
    }

    // ── 2. 真实微信适配器的诚实能力声明 ──

    @Test
    fun `微信适配器 capabilities 逐字段等于诚实声明值`() {
        val caps = WeChatChannelAdapter.CAPABILITIES
        assertTrue("文本入站已接", caps.inboundText)
        assertTrue("入站图片已消费（CDN 下载 + AI 视觉回复）", caps.inboundImage)
        assertTrue("文本出站已接", caps.outboundText)
        assertTrue("图片出站已接（outbox IMAGE kind / 表情物化）", caps.outboundImage)
        assertFalse("无 typing 接缝", caps.typingIndicator)
        assertFalse(
            "ChannelSession.send 只能被动回复；主动发送走插件事件，不走这一位",
            caps.proactiveSend,
        )
        assertFalse("无 ack：发了不等于对方收到", caps.deliveryReceipt)
        assertTrue("表情走图片通道", caps.stickers)
    }

    @Test
    fun `微信适配器 capabilities 整体等于逐位构造的诚实值（防顺手改成理想值）`() {
        val expected = ChannelCapabilities(
            inboundText = true,
            inboundImage = true,
            outboundText = true,
            outboundImage = true,
            typingIndicator = false,
            proactiveSend = false,
            deliveryReceipt = false,
            stickers = true,
        )
        assertEquals(expected, WeChatChannelAdapter.CAPABILITIES)
    }

    @Test
    fun `微信适配器的通道标识与展示名来自唯一来源`() {
        assertEquals(ChannelKeys.WECHAT, WeChatChannelAdapter.KEY)
        val adapter = WeChatChannelAdapter(senderSupplier = { RecordingSender() })
        assertEquals(ChannelKeys.WECHAT, adapter.channelKey)
        assertEquals(WeChatChannelAdapter.CAPABILITIES, adapter.capabilities)
        assertTrue("展示名不得为空", adapter.displayName.isNotBlank())
    }

    @Test
    fun `适配器构造不触碰出站通路，start 才求值供应函数`() {
        var supplierCalls = 0
        val adapter = WeChatChannelAdapter(
            senderSupplier = {
                supplierCalls++
                RecordingSender()
            },
        )
        assertEquals("构造适配器不得触碰任何通路", 0, supplierCalls)
        val session = kotlinx.coroutines.runBlocking { adapter.start() }
        assertEquals("start 才求值供应函数", 1, supplierCalls)
        session.close()
    }

    // ── 3. setup 的副作用形状 ──

    @Test
    fun `setup 把适配器注册进 CHANNELS 并登记撤销副作用`() {
        val adapter = FakeAdapter(ChannelKeys.WECHAT)
        val registry = FakeRegistry()
        val bus = FakeBailBus()
        val ctx = FakeContext(mapOf(PluginServices.CHANNELS to registry), bus)

        pluginWith(RecordingSender(), adapter).setup(ctx)

        assertEquals(listOf("channel.wechat/wechat"), registry.registerCalls)
        assertSame(adapter, registry.adapter(ChannelKeys.WECHAT))
        // T3 起 setup 有**三条**可逆副作用：通道注册 + 出站请求订阅 + 设置区注册。
        // 逐条按位置与标签断言（而不是只数数量），三条都必须真的登记。
        assertEquals("必须登记恰好三条撤销副作用", 3, ctx.effects.size)
        assertTrue(
            "撤销标签必须点名通道: " + ctx.effects[0].second,
            ctx.effects[0].second.contains(ChannelKeys.WECHAT),
        )
        assertEquals("订阅必须登记退订副作用", "unsubscribe:" + ChannelOutboundEvents.REQUEST, ctx.effects[1].second)
        assertEquals("设置区必须登记撤销副作用", "settings-section", ctx.effects[2].second)
        assertEquals("装配期不得启动会话", 0, adapter.startCount)
        assertEquals("setup 后必须已订阅出站请求", 1, bus.listenerCount(ChannelOutboundEvents.REQUEST))
    }

    @Test
    fun `卸载执行撤销副作用后注册中心取不到适配器`() {
        val registry = FakeRegistry()
        val bus = FakeBailBus()
        val ctx = FakeContext(mapOf(PluginServices.CHANNELS to registry), bus)

        pluginWith(RecordingSender()).setup(ctx)
        assertNotNull(registry.adapter(ChannelKeys.WECHAT))

        ctx.disposeAll()

        assertNull("撤销后必须从注册中心消失", registry.adapter(ChannelKeys.WECHAT))
        assertTrue(registry.adapters().isEmpty())
        assertEquals(listOf("channel.wechat/wechat"), registry.unregisterCalls)
    }

    @Test
    fun `未注入 CHANNELS 时 setup 抛异常且不留注册条目`() {
        val registry = FakeRegistry()
        val bus = FakeBailBus()
        val ctx = FakeContext(emptyMap(), bus) // 刻意不预置 CHANNELS

        val thrown = runCatching { pluginWith(RecordingSender()).setup(ctx) }.exceptionOrNull()

        assertNotNull("缺依赖必须抛异常（由宿主转成 fail-closed 装载失败）", thrown)
        assertTrue(
            "异常必须点名缺失的服务键: " + thrown!!.message,
            thrown.message!!.contains(PluginServices.CHANNELS),
        )
        assertTrue("不得留下注册条目", registry.adapters().isEmpty())
        assertTrue("不得登记任何副作用", ctx.effects.isEmpty())
        assertEquals("不得留下订阅", 0, bus.listenerCount(ChannelOutboundEvents.REQUEST))
    }

    @Test
    fun `预置 CHANNELS 时插件经宿主装载成功且注册中心可查到适配器`() {
        val registry = FakeRegistry()
        val host = FakeHost(mapOf(PluginServices.CHANNELS to registry))
        val adapter = FakeAdapter(ChannelKeys.WECHAT)
        host.register(pluginWith(RecordingSender(), adapter))

        assertEquals(PluginLoadResult.Loaded, host.load(WeChatChannelPlugin.ID, null))
        assertSame(adapter, registry.adapter(ChannelKeys.WECHAT))
        assertEquals(
            "ADAPTER 分派视图必须能查到本插件",
            listOf(WeChatChannelPlugin.ID),
            host.pluginsOf(PluginKind.ADAPTER).map { it.id },
        )

        assertTrue(host.unload(WeChatChannelPlugin.ID))
        assertNull(registry.adapter(ChannelKeys.WECHAT))
    }

    @Test
    fun `宿主未预置 CHANNELS 时装载 fail-closed 且不留半装配状态`() {
        val host = FakeHost(emptyMap())
        val registry = FakeRegistry()
        host.register(pluginWith(RecordingSender(), FakeAdapter(ChannelKeys.WECHAT)))

        val result = host.load(WeChatChannelPlugin.ID, null)
        assertTrue("缺依赖必须 fail-closed", result is PluginLoadResult.Failed)
        val reason = (result as PluginLoadResult.Failed).reason
        assertTrue("原因必须点名 CHANNELS: " + reason, reason.contains(PluginServices.CHANNELS))
        assertFalse(host.isLoaded(WeChatChannelPlugin.ID))
        assertTrue("不得留下注册条目", registry.adapters().isEmpty())
    }

    // ── 4. 订阅语义：放行 / 认领（验收点 1）──

    @Test
    fun `不匹配 channelKey 时返回 null 放行（不得吞掉 QQ 的请求）`() {
        val plugin = pluginWith(RecordingSender())

        // 真实订阅回调注册进共享总线后，派发一条 **QQ 的**请求：
        // 微信回调必须返回 null（= 不 bail = 放行），请求才能落到后面的 QQ 订阅者。
        val registry = FakeRegistry()
        val bus = FakeBailBus()
        val ctx = FakeContext(mapOf(PluginServices.CHANNELS to registry), bus)
        plugin.setup(ctx)

        var qqCalls = 0
        // 模拟「另一个通道插件（QQ）也订阅了同一事件」，注册在微信**之后**。
        ctx.onBail(ChannelOutboundEvents.REQUEST) { payload ->
            if (payload is ChannelOutboundRequest && payload.channelKey == ChannelKeys.QQBOT) {
                qqCalls++
                ChannelOutboundResult.Sent(messageRef = "qq-msg-1")
            } else {
                null
            }
        }

        val dispatched = ctx.bail(
            ChannelOutboundEvents.REQUEST,
            request(channelKey = ChannelKeys.QQBOT, target = "openid-1", text = "来自 QQ 的请求"),
        )

        assertTrue("QQ 的请求必须被 QQ 认领（微信不得吞掉）", dispatched.isBailed)
        assertEquals(ChannelOutboundResult.Sent(messageRef = "qq-msg-1"), dispatched.bailValue)
        assertEquals("QQ 订阅者必须被调用恰好一次", 1, qqCalls)
    }

    @Test
    fun `不匹配 channelKey 时微信插件不产生任何出站副作用`() {
        val sender = RecordingSender()
        val registry = FakeRegistry()
        val bus = FakeBailBus()
        val ctx = FakeContext(mapOf(PluginServices.CHANNELS to registry), bus)
        pluginWith(sender).setup(ctx)

        val dispatched = ctx.bail(
            ChannelOutboundEvents.REQUEST,
            request(channelKey = ChannelKeys.APP_CHAT, target = null, text = "不是给微信的"),
        )

        assertFalse("没人认领才是正确结果（微信必须放行）", dispatched.isBailed)
        assertTrue("不得发起任何发送", sender.sent.isEmpty())
    }

    @Test
    fun `非 ChannelOutboundRequest 的 payload 一律放行`() {
        val sender = RecordingSender()
        val registry = FakeRegistry()
        val bus = FakeBailBus()
        val ctx = FakeContext(mapOf(PluginServices.CHANNELS to registry), bus)
        pluginWith(sender).setup(ctx)

        val dispatched = ctx.bail(ChannelOutboundEvents.REQUEST, "不是请求对象")

        assertFalse("payload 形状不对必须放行，不得吞掉", dispatched.isBailed)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `channelKey 匹配时认领并 bail 出应答`() {
        val sender = RecordingSender(outcome = WeChatSendOutcome.Sent(messageRef = null))
        val registry = FakeRegistry()
        val bus = FakeBailBus()
        val ctx = FakeContext(mapOf(PluginServices.CHANNELS to registry), bus)
        pluginWith(sender).setup(ctx)

        val dispatched = ctx.bail(
            ChannelOutboundEvents.REQUEST,
            request(target = "wx-user-1", text = "晚安"),
        )

        assertTrue("微信必须认领自己的请求", dispatched.isBailed)
        assertEquals(ChannelOutboundResult.Sent(messageRef = null), dispatched.bailValue)
        assertEquals(listOf("wx-user-1|晚安"), sender.sent)
    }

    // ── 5. 收件人语义（验收点 2–5）：target 为空 = 绑定的微信用户 ──

    @Test
    fun `target 为空时发给绑定的微信用户（主路径：你给我微信发条消息）`() {
        val store = loggedInStore()
        val transport = FakeTransport()
        val sender = realSender(transport = transport, store = store, nowMs = { NOW })

        val result = pluginWith(sender).handleOutboundRequest(request(target = null), sender)

        assertEquals(ChannelOutboundResult.Sent(messageRef = null), result)
        assertEquals(
            "收件人必须**恰好**是绑定的微信用户，且只发一条",
            listOf("$BOUND_USER|你好|tok"),
            transport.calls,
        )
    }

    @Test
    fun `target 为空白字符串同样发给绑定的微信用户`() {
        val store = loggedInStore()
        val transport = FakeTransport()
        val sender = realSender(transport = transport, store = store, nowMs = { NOW })

        val result = pluginWith(sender).handleOutboundRequest(request(target = "   "), sender)

        assertEquals(ChannelOutboundResult.Sent(messageRef = null), result)
        assertEquals(listOf("$BOUND_USER|你好|tok"), transport.calls)
    }

    @Test
    fun `target 恰好等于绑定用户时正常发送`() {
        val store = loggedInStore()
        val transport = FakeTransport()
        val sender = realSender(transport = transport, store = store, nowMs = { NOW })

        val result = pluginWith(sender).handleOutboundRequest(request(target = BOUND_USER), sender)

        assertEquals(ChannelOutboundResult.Sent(messageRef = null), result)
        assertEquals(listOf("$BOUND_USER|你好|tok"), transport.calls)
    }

    @Test
    fun `target 是非绑定用户 id 时如实失败且绝不落到传输层`() {
        val store = loggedInStore()
        val transport = ExplodingTransport()
        val sender = realSender(transport = transport, store = store, nowMs = { NOW })

        val reason = failedReason(
            pluginWith(sender).handleOutboundRequest(request(target = "wx-someone-else"), sender),
        )

        assertTrue("必须点名 ilink 协议: " + reason, reason.contains("ilink"))
        assertTrue("必须说明只允许发给绑定账号: " + reason, reason.contains("只允许"))
        assertTrue("必须点名绑定账号: " + reason, reason.contains("绑定"))
        assertTrue("必须说明本次未发送任何消息: " + reason, reason.contains("未发送"))
        assertTrue(
            "一旦被调用即 AssertionError：收件人不合法时绝不落到传输层",
            transport.calls.isEmpty(),
        )
    }

    @Test
    fun `target 是非绑定用户时绝不静默改写为绑定用户后发送`() {
        val store = loggedInStore()
        val transport = FakeTransport()
        val sender = realSender(transport = transport, store = store, nowMs = { NOW })

        val result = pluginWith(sender).handleOutboundRequest(request(target = "wx-someone-else"), sender)

        assertTrue("必须如实 Failed，绝不能改写后照发", result is ChannelOutboundResult.Failed)
        assertTrue("发送方必须零调用", transport.calls.isEmpty())
    }

    @Test
    fun `绑定用户无法唯一确定（0 个候选）时如实失败且发送方零调用`() {
        // 该状态可达：IlinkClientManager.pollLoginStatus 用 status.ilinkUserId.orEmpty() 兜底，
        // 服务器没返回 ilink_user_id 时绑定用户就是空串；此处候选集也为空。
        val store = FakeSessionStore(FakeSessionStore.ACCOUNT.copy(ilinkUserId = ""))
        val transport = ExplodingTransport()
        val sender = realSender(transport = transport, store = store)

        val reason = failedReason(
            pluginWith(sender).handleOutboundRequest(request(target = null), sender),
        )

        assertEquals(WeChatChannelSender.BOUND_USER_UNRESOLVED_REASON, reason)
        assertTrue("一旦被调用即 AssertionError：绝不发送", transport.calls.isEmpty())
    }

    @Test
    fun `绑定用户无法唯一确定（多个候选）时如实失败且绝不静默挑一个`() {
        val store = FakeSessionStore(FakeSessionStore.ACCOUNT.copy(ilinkUserId = "")).apply {
            putToken("wx-user-a", "tok-a", NOW)
            putToken("wx-user-b", "tok-b", NOW)
        }
        val transport = ExplodingTransport()
        val sender = realSender(transport = transport, store = store)

        val reason = failedReason(
            pluginWith(sender).handleOutboundRequest(request(target = null), sender),
        )

        assertEquals(WeChatChannelSender.BOUND_USER_UNRESOLVED_REASON, reason)
        assertTrue("绝不静默挑一个", transport.calls.isEmpty())
    }

    @Test
    fun `ilink_user_id 缺失但候选恰好一个时认它（唯一的合法收件人）`() {
        val store = FakeSessionStore(FakeSessionStore.ACCOUNT.copy(ilinkUserId = "")).apply {
            putToken("wx-only-user", "tok-only", NOW)
        }
        val transport = FakeTransport()
        val sender = realSender(transport = transport, store = store, nowMs = { NOW })

        val result = pluginWith(sender).handleOutboundRequest(request(target = null), sender)

        assertEquals(ChannelOutboundResult.Sent(messageRef = null), result)
        assertEquals(listOf("wx-only-user|你好|tok-only"), transport.calls)
    }

    @Test
    fun `解析不出收件人时插件绝不调用发送方（控制流级证明）`() {
        val exploding = ExplodingSender(
            WeChatRecipientResolution.Failed("替身：收件人不合法"),
        )

        val reason = failedReason(
            pluginWith(exploding).handleOutboundRequest(request(target = null), exploding),
        )

        assertEquals("替身：收件人不合法", reason)
    }

    @Test
    fun `通道未接线时 target 为空也如实失败（解析需要真实的会话存储）`() {
        val result = pluginWith(sender = null).handleOutboundRequest(request(target = null), null)

        val reason = failedReason(result)
        assertTrue("原因必须点名装配问题: " + reason, reason.contains("未接线"))
        assertEquals(WeChatChannelPlugin.NOT_WIRED_REASON, reason)
    }

    @Test
    fun `target 为空但未登录时如实失败（不拿登录态兜底）`() {
        val store = FakeSessionStore().apply { logout() }
        val transport = ExplodingTransport()
        val sender = realSender(transport = transport, store = store)

        val reason = failedReason(
            pluginWith(sender).handleOutboundRequest(request(target = null), sender),
        )

        assertEquals(WeChatChannelSender.NOT_LOGGED_IN_REASON, reason)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `模型省略 target 时经真实事件派发得到 Sent（端到端）`() {
        // ChannelSendTool.execute 在模型省略 / 留空 target 时构造的请求**正是**
        // ChannelOutboundRequest(channelKey = "wechat", target = null, text = …)：
        // core/agent/.../ChannelSendTool.kt:174 把空白 target 归一成 null，:193 派发。
        val store = loggedInStore()
        val transport = FakeTransport()
        val sender = realSender(transport = transport, store = store, nowMs = { NOW })
        val registry = FakeRegistry()
        val bus = FakeBailBus()
        val ctx = FakeContext(mapOf(PluginServices.CHANNELS to registry), bus)
        pluginWith(sender).setup(ctx)

        val dispatched = ctx.bail(
            ChannelOutboundEvents.REQUEST,
            ChannelOutboundRequest(channelKey = ChannelKeys.WECHAT, target = null, text = "晚安"),
        )

        assertTrue("必须认领（否则会被误报成通道未启用）", dispatched.isBailed)
        assertEquals(ChannelOutboundResult.Sent(messageRef = null), dispatched.bailValue)
        assertEquals(listOf("$BOUND_USER|晚安|tok"), transport.calls)
    }

    @Test
    fun `模型显式指定非绑定用户时经真实事件派发得到 Failed`() {
        val store = loggedInStore()
        val transport = ExplodingTransport()
        val sender = realSender(transport = transport, store = store, nowMs = { NOW })
        val registry = FakeRegistry()
        val bus = FakeBailBus()
        val ctx = FakeContext(mapOf(PluginServices.CHANNELS to registry), bus)
        pluginWith(sender).setup(ctx)

        val dispatched = ctx.bail(
            ChannelOutboundEvents.REQUEST,
            request(target = "wx-someone-else", text = "晚安"),
        )

        assertTrue("必须认领并如实应答失败", dispatched.isBailed)
        val failed = dispatched.bailValue as? ChannelOutboundResult.Failed
        assertNotNull("必须是 Failed，实际是 " + dispatched.bailValue, failed)
        assertTrue("原因必须点名 ilink 只允许发给绑定账号", failed!!.reason.contains("只允许"))
        assertTrue("绝不落到传输层", transport.calls.isEmpty())
    }

    // ── 6. 未接线 / 未登录 / token 门（验收点 3、4）──

    @Test
    fun `通道未接线时返回 Failed 而不是假装成功`() {
        val result = pluginWith(sender = null).handleOutboundRequest(request(), null)

        val reason = failedReason(result)
        assertTrue("原因必须点名装配问题: " + reason, reason.contains("未接线"))
    }

    @Test
    fun `未登录时返回 Failed 且原因具体可操作`() {
        val store = FakeSessionStore().apply { logout() }
        val transport = FakeTransport()
        val sender = WeChatChannelSender.fromTransport(
            transport = transport,
            sessionStore = store,
            loggedIn = MutableStateFlow(false),
        )

        val reason = failedReason(pluginWith(sender).handleOutboundRequest(request(), sender))

        assertTrue("必须点名未登录: " + reason, reason.contains("未登录"))
        assertTrue("必须给出可操作的下一步: " + reason, reason.contains("扫码"))
        assertTrue("不得落到传输层", transport.calls.isEmpty())
        assertEquals(
            "未登录原因必须与常量逐字一致（唯一来源）",
            WeChatChannelSender.NOT_LOGGED_IN_REASON,
            reason,
        )
    }

    @Test
    fun `context_token 缺失时返回 Failed 且原因具体（不得静默跳过）`() {
        val store = FakeSessionStore()
        val transport = FakeTransport()
        val sender = WeChatChannelSender.fromTransport(transport, store, MutableStateFlow(true))

        val reason = failedReason(pluginWith(sender).handleOutboundRequest(request(), sender))

        assertTrue("必须点名 context_token: " + reason, reason.contains("context_token"))
        assertTrue("必须给出可操作的下一步: " + reason, reason.contains("先发一条消息"))
        assertTrue("不得静默跳过、也不得落到传输层", transport.calls.isEmpty())
    }

    @Test
    fun `context_token 过期（超过 24 小时）时返回 Failed 且原因具体`() {
        val now = 1_700_000_000_000L
        val store = FakeSessionStore().apply {
            putToken(
                userId = "wx-user-1",
                token = "stale-token",
                savedAtMs = now - WeChatOutboxCoordinator.CONTEXT_TOKEN_MAX_AGE_MS - 60_000L,
            )
        }
        val transport = FakeTransport()
        val sender = WeChatChannelSender.fromTransport(
            transport = transport,
            sessionStore = store,
            loggedIn = MutableStateFlow(true),
            nowMs = { now },
        )

        val reason = failedReason(pluginWith(sender).handleOutboundRequest(request(), sender))

        assertTrue("必须点名过期: " + reason, reason.contains("过期"))
        assertTrue("必须点名 24 小时窗口: " + reason, reason.contains("24 小时"))
        assertTrue("窗口外不得重试、不得落到传输层", transport.calls.isEmpty())
    }

    @Test
    fun `context_token 恰好落在 24 小时边界内时仍然发送`() {
        val now = 1_700_000_000_000L
        val store = FakeSessionStore().apply {
            putToken(
                userId = "wx-user-1",
                token = "fresh-token",
                savedAtMs = now - WeChatOutboxCoordinator.CONTEXT_TOKEN_MAX_AGE_MS + 1_000L,
            )
        }
        val transport = FakeTransport()
        val sender = WeChatChannelSender.fromTransport(
            transport = transport,
            sessionStore = store,
            loggedIn = MutableStateFlow(true),
            nowMs = { now },
        )

        val result = pluginWith(sender).handleOutboundRequest(request(), sender)

        assertEquals(ChannelOutboundResult.Sent(messageRef = null), result)
        assertEquals(listOf("wx-user-1|你好|fresh-token"), transport.calls)
    }

    @Test
    fun `旧格式记录（无落库时间）不做年龄判定`() {
        val now = 1_700_000_000_000L
        val store = FakeSessionStore().apply {
            putToken(userId = "wx-user-1", token = "legacy-token", savedAtMs = null)
        }
        val transport = FakeTransport()
        val sender = WeChatChannelSender.fromTransport(
            transport = transport,
            sessionStore = store,
            loggedIn = MutableStateFlow(true),
            nowMs = { now },
        )

        val result = pluginWith(sender).handleOutboundRequest(request(), sender)

        assertEquals("无法证明过期就不判死（与 outbox 的边界一致）", ChannelOutboundResult.Sent(null), result)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `传输返回失败时如实转成 Failed 并带上原因`() {
        val store = FakeSessionStore().apply {
            putToken(userId = "wx-user-1", token = "tok", savedAtMs = System.currentTimeMillis())
        }
        val transport = FakeTransport(
            result = Result.failure(IllegalStateException("errcode=-14 会话已过期")),
        )
        val sender = WeChatChannelSender.fromTransport(transport, store, MutableStateFlow(true))

        val reason = failedReason(pluginWith(sender).handleOutboundRequest(request(), sender))

        assertTrue("必须带上通道侧原因: " + reason, reason.contains("errcode=-14"))
        assertEquals(1, transport.calls.size)
    }

    // ── 7. 异常不得穿出（验收点 5）──

    @Test
    fun `发送抛异常时返回 Failed 而不是让异常穿出`() {
        val throwing = ThrowingSender(IllegalStateException("boom-发送链路炸了"))

        val result = pluginWith(throwing).handleOutboundRequest(request(), throwing)

        val reason = failedReason(result)
        assertTrue("必须如实转成 Failed: " + reason, reason.contains("异常"))
        assertTrue("必须带上底层原因: " + reason, reason.contains("boom-发送链路炸了"))
    }

    @Test
    fun `发送抛异常时不得表现为无人认领`() {
        val throwing = ThrowingSender(RuntimeException("boom"))
        val registry = FakeRegistry()
        val bus = FakeBailBus()
        val ctx = FakeContext(mapOf(PluginServices.CHANNELS to registry), bus)
        pluginWith(throwing).setup(ctx)

        val dispatched = ctx.bail(ChannelOutboundEvents.REQUEST, request())

        assertTrue(
            "异常必须变成 Failed 应答（否则会被误报成「该通道未启用」）",
            dispatched.isBailed,
        )
        assertTrue(dispatched.bailValue is ChannelOutboundResult.Failed)
    }

    // ── 8. 卸载即失效（验收点 6）──

    @Test
    fun `插件卸载后订阅消失（再派发没人认领）`() {
        val sender = RecordingSender()
        val registry = FakeRegistry()
        val bus = FakeBailBus()
        val ctx = FakeContext(mapOf(PluginServices.CHANNELS to registry), bus)
        pluginWith(sender).setup(ctx)

        assertEquals(1, bus.listenerCount(ChannelOutboundEvents.REQUEST))
        assertTrue(ctx.bail(ChannelOutboundEvents.REQUEST, request()).isBailed)

        ctx.disposeAll()

        assertEquals("卸载必须退订", 0, bus.listenerCount(ChannelOutboundEvents.REQUEST))
        val dispatched = ctx.bail(ChannelOutboundEvents.REQUEST, request())
        assertFalse("卸载后再派发必须没人认领（= 该通道未启用）", dispatched.isBailed)
        assertNull(registry.adapter(ChannelKeys.WECHAT))
    }

    // ── 9. 会话（传输通路，被动回复）──

    @Test
    fun `会话按登录态投影连接态`() {
        val loggedIn = MutableStateFlow(true)
        val session = WeChatChannelSession(RecordingSender(loggedIn = loggedIn))
        awaitState(session, ChannelConnectionState.CONNECTED)

        loggedIn.value = false
        awaitState(session, ChannelConnectionState.DISCONNECTED)

        session.close()
    }

    @Test
    fun `未登录时会话状态是 DISCONNECTED`() {
        val session = WeChatChannelSession(RecordingSender(loggedIn = MutableStateFlow(false)))
        awaitState(session, ChannelConnectionState.DISCONNECTED)
        session.close()
    }

    @Test
    fun `会话把 replyTo 当作收件人并转发文本`() {
        val sender = RecordingSender()
        val session = WeChatChannelSession(sender)

        val result = kotlinx.coroutines.runBlocking {
            session.send(ChannelOutbound.Text(replyTo = "wx-user-9", text = "在的"))
        }

        assertEquals(ChannelSendResult.Sent(messageRef = null), result)
        assertEquals(listOf("wx-user-9|在的"), sender.sent)
        session.close()
    }

    @Test
    fun `会话缺少锚点时明确失败而不是静默丢弃`() {
        val sender = RecordingSender()
        val session = WeChatChannelSession(sender)

        val result = kotlinx.coroutines.runBlocking {
            session.send(ChannelOutbound.Text(replyTo = "   ", text = "在的"))
        }

        assertTrue(result is ChannelSendResult.Failed)
        assertTrue(
            "失败原因必须点名锚点: " + (result as ChannelSendResult.Failed).reason,
            result.reason.contains("replyTo"),
        )
        assertTrue(sender.sent.isEmpty())
        session.close()
    }

    @Test
    fun `会话内发送异常也不穿出（ChannelSession 契约）`() {
        val session = WeChatChannelSession(ThrowingSender(IllegalStateException("boom-会话")))

        val result = kotlinx.coroutines.runBlocking {
            session.send(ChannelOutbound.Text(replyTo = "wx-user-1", text = "在的"))
        }

        assertTrue("实现不得抛异常表示失败", result is ChannelSendResult.Failed)
        assertTrue((result as ChannelSendResult.Failed).reason.contains("boom-会话"))
        session.close()
    }

    @Test
    fun `关闭后的会话拒绝发送`() {
        val session = WeChatChannelSession(RecordingSender())
        session.close()

        val result = kotlinx.coroutines.runBlocking {
            session.send(ChannelOutbound.Text(replyTo = "wx-user-1", text = "在的"))
        }

        assertTrue(result is ChannelSendResult.Failed)
        assertEquals(ChannelConnectionState.DISCONNECTED, session.state.value)
    }

    // ── 3b. 自贡献设置区（T3：插件自贡献 UI 的生命周期契约）──

    @Test
    fun `装载后注册 channel_wechat 设置区，卸载后设置区消失`() {
        // 注册表是宿主级单例：用例前后各自清一次，避免与同模块其它用例互相污染。
        PluginSettingsSections.clear()
        val registry = FakeRegistry()
        val host = FakeHost(mapOf(PluginServices.CHANNELS to registry))
        host.register(pluginWith(RecordingSender()))
        try {
            assertEquals(PluginLoadResult.Loaded, host.load(WeChatChannelPlugin.ID, null))

            val section = PluginSettingsSections.forPlugin(WeChatChannelPlugin.ID)
            assertNotNull("装载后必须能按插件 id 查到设置区（写错 id 会静默不显示）", section)
            assertEquals("channel.wechat", section!!.pluginId)
            assertSame(
                "必须注册的是本模块贡献的那一个设置区",
                WeChatSettingsSection as Any,
                section,
            )
            assertEquals(PluginSettingsCategory.CHANNEL, section.category)

            // 核心契约：撤销由 ctx.effect 承担——宿主卸载 → 逆序执行 effects → 设置区消失。
            assertTrue(host.unload(WeChatChannelPlugin.ID))
            assertNull(
                "卸载后设置区必须消失（否则停用的插件仍会在设置页留下可展开的区块）",
                PluginSettingsSections.forPlugin(WeChatChannelPlugin.ID),
            )
            assertTrue(
                "注册表里不得残留本插件的条目: " +
                    PluginSettingsSections.all().map { it.pluginId },
                PluginSettingsSections.all().none { it.pluginId == WeChatChannelPlugin.ID },
            )
        } finally {
            PluginSettingsSections.clear()
        }
    }

    @Test
    fun `设置区 id 逐字等于插件 id 而不是通道键`() {
        // 两套 id 空间不同：插件 id 是 `channel.wechat`，通道键是 `wechat`。
        // 写错会让 PluginSettingsSections.forPlugin() 永远查不到、设置区静默不显示。
        assertEquals(WeChatChannelPlugin.ID, WeChatSettingsSection.pluginId)
        assertEquals("channel.wechat", WeChatSettingsSection.pluginId)
        assertFalse(
            "插件 id 不得退化成通道键",
            WeChatSettingsSection.pluginId == WeChatChannelPlugin.CHANNEL_KEY,
        )
    }

    @Test
    fun `设置区声明为整页呈现 FULL_PAGE`() {
        // ⚠️ 这条断言锁的是**崩溃风险**，不是观感：
        // WeChatSettingsSection 渲染的 WeChatSettingsScreen 是整页组件，内部用
        // GlassPageScaffold（背景层 + Scaffold + Column(verticalScroll)）。
        // 一旦这里被写成 INLINE，设置页就会把它内联进外层 LazyColumn 的 item 里，
        // 内层滚动容器拿到**无界最大高度约束** → 运行期抛异常。
        assertEquals(
            "整页设置区必须声明 FULL_PAGE；写成 INLINE 就是一次运行期崩溃",
            PluginSettingsPresentation.FULL_PAGE,
            WeChatSettingsSection.presentation,
        )
        assertFalse(
            "FULL_PAGE 不得退化成 INLINE",
            WeChatSettingsSection.presentation == PluginSettingsPresentation.INLINE,
        )
        // 注册表必须原样带回这条声明——设置页读的就是它。
        PluginSettingsSections.clear()
        try {
            PluginSettingsSections.register(WeChatSettingsSection)
            assertEquals(
                PluginSettingsPresentation.FULL_PAGE,
                PluginSettingsSections.forPlugin(WeChatChannelPlugin.ID)?.presentation,
            )
        } finally {
            PluginSettingsSections.clear()
        }
    }
}

