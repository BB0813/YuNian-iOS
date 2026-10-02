package com.yunian.ai.feature.qqbot.channel

import com.yunian.ai.domain.ChannelKeys
import com.yunian.ai.domain.channel.ChannelAdapter
import com.yunian.ai.domain.channel.ChannelCapabilities
import com.yunian.ai.domain.channel.ChannelConnectionState
import com.yunian.ai.domain.channel.ChannelOutbound
import com.yunian.ai.domain.channel.ChannelSendResult
import com.yunian.ai.domain.channel.ChannelSession
import com.yunian.ai.domain.channel.MutableChannelRegistry
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.channel.ChannelOutboundEvents
import com.yunian.ai.domain.channel.ChannelOutboundRequest
import com.yunian.ai.domain.channel.ChannelOutboundResult
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginEventListener
import com.yunian.ai.domain.plugin.PluginEventResult
import com.yunian.ai.domain.plugin.PluginHost
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginLoadResult
import com.yunian.ai.domain.plugin.PluginServices
import com.yunian.ai.feature.qqbot.data.model.QQProactiveSendResult
import com.yunian.ai.feature.qqbot.ui.QQBotSettingsSection
import com.yunian.ai.uicommon.plugin.PluginSettingsCategory
import com.yunian.ai.uicommon.plugin.PluginSettingsPresentation
import com.yunian.ai.uicommon.plugin.PluginSettingsSections
import com.yunian.ai.feature.qqbot.data.model.QQProactiveTarget
import com.yunian.ai.feature.qqbot.data.network.ConnectionState
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
 * `channel.qqbot` 通道插件测试：QQ 适配器的**诚实能力声明** + 插件装载契约。
 *
 * ## 与 [com.yunian.ai.agent.channel.ChannelRegistryHostWiringTest] 的分工
 *
 * - 宿主 / 注册中心的**真实**接线测试在 core:agent（那里有真实 PluginHostImpl）；
 * - 本文件负责**真实 QQ 插件**：id / kind / requires / capabilities / setup 的副作用形状。
 *
 * :feature:qqbot 不能依赖 :core:agent（core 不得依赖 feature、feature 之间不得互相依赖，
 * 而 core:agent 也不在 feature:qqbot 的依赖里），所以这里用与 [PluginHost] /
 * [MutableChannelRegistry] **同构的最小替身**驱动真实插件代码——被测的 `setup()` 是真代码。
 *
 * ## 在旧实现下为什么失败
 *
 * 旧实现里 [com.yunian.ai.domain.channel.ChannelAdapter] / [MutableChannelRegistry] /
 * [PluginServices.CHANNELS] / 本插件**都不存在**，本文件**根本编译不过**——编译期不可达，
 * 不是行为回归测试，如实说明。
 */
class QQBotChannelPluginTest {

    // ── 替身 ──

    private class FakeAdapter(
        override val channelKey: String,
        override val displayName: String = "fake:" + channelKey,
    ) : ChannelAdapter {
        override val capabilities: ChannelCapabilities = ChannelCapabilities(
            inboundText = true,
            inboundImage = false,
            outboundText = true,
            outboundImage = false,
            typingIndicator = false,
            proactiveSend = false,
            deliveryReceipt = false,
            stickers = false,
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

    /** 注册中心替身：记录 register/unregister 调用，便于断言 setup 的副作用形状。 */
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

    /** 插件上下文替身：服务表 + effect 记录 + **可派发的事件订阅表**（与 PluginContextImpl 语义同构）。 */
    private class FakeContext(private val services: Map<String, Any>) : PluginContext {
        val effects = mutableListOf<Pair<() -> Unit, String>>()

        /**
         * 订阅表（事件名 → 按注册顺序的监听器）。
         *
         * :feature:qqbot 不能依赖 :core:agent（真实的 `PluginEventBus` 在那里），
         * 因此这里用**与总线同构的最小替身**驱动真实插件的订阅回调：
         * 返回 null = 不 bail = **放行**，返回非 null = bail（含 [PluginEventResult]）。
         */
        private val bailListeners = LinkedHashMap<String, MutableList<PluginEventListener>>()

        /** 追加一个监听器（模拟「另一个通道插件也订阅了同一事件」）。 */
        fun addBailListener(event: String, handler: PluginEventListener) {
            bailListeners.getOrPut(event) { mutableListOf() } += handler
        }

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
            bailListeners.getOrPut(event) { mutableListOf() } += handler
            // 与 PluginContextImpl 一致：订阅即 effect（卸载时退订）。
            effect({ bailListeners[event]?.remove(handler) }, "unsubscribe:" + event)
        }

        /** 本事件当前的监听器数量（退订后应归零）。 */
        fun bailListenerCount(event: String): Int = bailListeners[event]?.size ?: 0

        /**
         * 按 bail 语义派发：顺序调用，**首个 bail 即短路**。
         *
         * 与 `PluginEventBus.bail` 一致：监听器抛异常按「无结果」处理（不掐断链）。
         */
        fun dispatchBail(event: String, payload: Any): PluginEventResult {
            val snapshot = bailListeners[event]?.toList().orEmpty()
            for (listener in snapshot) {
                val raw = try {
                    listener.onEvent(payload)
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

        /** 逆序执行（与 PluginContextImpl.disposeAll 一致）。 */
        fun disposeAll() {
            effects.asReversed().forEach { it.first() }
            effects.clear()
        }
    }

    /**
     * 宿主替身：只实现本测试需要的「按 id 分派 + 装载态」语义。
     *
     * 真实 [PluginHost] 的语义（manifest 校验、fail-closed 依赖检查、effect 逆序回滚）
     * 已由 core:agent 的测试覆盖；这里只驱动真实插件的 `setup()`。
     */
    private class FakeHost(private val services: Map<String, Any>) : PluginHost {
        private val registered = LinkedHashMap<String, LianYuPlugin>()
        private val loadedIds = LinkedHashSet<String>()

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
                    "插件 " + id + " 缺少依赖服务: " + missing + "（宿主未预置）"
                )
            }
            val ctx = FakeContext(services)
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

        private val contexts = LinkedHashMap<String, FakeContext>()

        /** 取某插件装配期用过的上下文替身（测试要驱动它的订阅表 / 副作用）。 */
        fun contextOf(id: String): FakeContext? = contexts[id]
    }

    // ── 1. 插件身份与依赖声明 ──

    @Test
    fun `插件 id 是 channel_qqbot 且 kind 是 ADAPTER`() {
        val plugin = QQBotChannelPlugin(adapterFactory = { FakeAdapter("qqbot") })
        assertEquals("channel.qqbot", plugin.id)
        assertEquals(PluginKind.ADAPTER, plugin.kind)
        assertEquals("qqbot", QQBotChannelPlugin.CHANNEL_KEY)
        assertEquals(ChannelKeys.QQBOT, QQBotChannelPlugin.CHANNEL_KEY)
    }

    @Test
    fun `插件依赖声明为 CHANNELS 且清单与自描述一致`() {
        val plugin = QQBotChannelPlugin(adapterFactory = { FakeAdapter("qqbot") })
        assertEquals(setOf(PluginServices.CHANNELS), plugin.requires)
        assertEquals(plugin.id, plugin.manifest.id)
        assertEquals(plugin.name, plugin.manifest.name)
        assertEquals(PluginKind.ADAPTER, plugin.manifest.kind)
        assertEquals(plugin.requires.sorted(), plugin.manifest.requires)
        assertEquals(plugin.configSchema, plugin.manifest.configSchema)
    }

    // ── 2. 真实 QQ 适配器的诚实能力声明 ──

    @Test
    fun `QQ 适配器 capabilities 逐字段等于诚实声明值`() {
        val caps = QQBotChannelAdapter.CAPABILITIES
        assertTrue("文本入站已接", caps.inboundText)
        assertFalse("入站图片被丢弃（extractImageUrl 零调用）", caps.inboundImage)
        assertTrue("文本出站已接", caps.outboundText)
        assertTrue("图片出站已接（msg_type=7）", caps.outboundImage)
        assertFalse("INPUT_NOTIFY 枚举存在但从未发送", caps.typingIndicator)
        assertFalse("ChannelSession.send 100% 被动，必须带 msg_id", caps.proactiveSend)
        assertFalse("无 ack：发了不等于对方收到", caps.deliveryReceipt)
        assertFalse("无表情通路", caps.stickers)
    }

    @Test
    fun `QQ 适配器 capabilities 整体等于逐位构造的诚实值（防顺手改成理想值）`() {
        val expected = ChannelCapabilities(
            inboundText = true,
            inboundImage = false,
            outboundText = true,
            outboundImage = true,
            typingIndicator = false,
            proactiveSend = false,
            deliveryReceipt = false,
            stickers = false,
        )
        assertEquals(expected, QQBotChannelAdapter.CAPABILITIES)
    }

    @Test
    fun `QQ 适配器的通道标识与展示名来自唯一来源`() {
        assertEquals(ChannelKeys.QQBOT, QQBotChannelAdapter.KEY)
        val adapter = QQBotChannelAdapter(senderSupplier = { FakeSender() })
        assertEquals(ChannelKeys.QQBOT, adapter.channelKey)
        assertEquals(QQBotChannelAdapter.CAPABILITIES, adapter.capabilities)
        assertTrue("展示名不得为空", adapter.displayName.isNotBlank())
    }

    // ── 3. setup 的副作用形状 ──

    @Test
    fun `setup 把适配器注册进 CHANNELS 并登记撤销副作用`() {
        val adapter = FakeAdapter("qqbot")
        val registry = FakeRegistry()
        val plugin = QQBotChannelPlugin(adapterFactory = { adapter })
        val ctx = FakeContext(mapOf(PluginServices.CHANNELS to registry))

        plugin.setup(ctx)

        assertEquals(listOf("channel.qqbot/qqbot"), registry.registerCalls)
        assertSame(adapter, registry.adapter("qqbot"))
        // T3 起 setup 有**三条**可逆副作用：通道注册 + 出站请求订阅 + 设置区注册。
        // 这里逐条按标签断言（而不是只数数量），三条都必须真的登记。
        assertEquals("必须登记恰好三条撤销副作用", 3, ctx.effects.size)
        val labels = ctx.effects.map { it.second }
        assertTrue(
            "必须登记通道撤销副作用（标签点名通道）: " + labels,
            labels.any { it.contains("qqbot") && it.startsWith("unregister-channel:") },
        )
        assertTrue(
            "必须登记出站请求订阅的退订副作用: " + labels,
            labels.contains("unsubscribe:" + ChannelOutboundEvents.REQUEST),
        )
        assertEquals("装配期不得启动会话", 0, adapter.startCount)
    }

    @Test
    fun `卸载执行撤销副作用后注册中心取不到适配器`() {
        val registry = FakeRegistry()
        val plugin = QQBotChannelPlugin(adapterFactory = { FakeAdapter("qqbot") })
        val ctx = FakeContext(mapOf(PluginServices.CHANNELS to registry))

        plugin.setup(ctx)
        assertNotNull(registry.adapter("qqbot"))

        ctx.disposeAll()

        assertNull("撤销后必须从注册中心消失", registry.adapter("qqbot"))
        assertTrue(registry.adapters().isEmpty())
        assertEquals(listOf("channel.qqbot/qqbot"), registry.unregisterCalls)
    }

    // ── 3b. 自贡献设置区（T3：插件自贡献 UI 的生命周期契约）──

    @Test
    fun `装载后注册 channel_qqbot 设置区，卸载后设置区消失`() {
        // 注册表是宿主级单例：用例前后各自清一次，避免与同模块其它用例互相污染。
        PluginSettingsSections.clear()
        try {
            val (host, _, _) = loadedPlugin(FakeProactiveSender())

            val section = PluginSettingsSections.forPlugin(QQBotChannelPlugin.ID)
            assertNotNull("装载后必须能按插件 id 查到设置区（写错 id 会静默不显示）", section)
            assertEquals("channel.qqbot", section!!.pluginId)
            assertSame(
                "必须注册的是本模块贡献的那一个设置区",
                QQBotSettingsSection as Any,
                section,
            )
            assertEquals(PluginSettingsCategory.CHANNEL, section.category)

            // 核心契约：撤销由 ctx.effect 承担——宿主卸载 → 逆序执行 effects → 设置区消失。
            assertTrue(host.unload(QQBotChannelPlugin.ID))
            assertNull(
                "卸载后设置区必须消失（否则停用的插件仍会在设置页留下可展开的区块）",
                PluginSettingsSections.forPlugin(QQBotChannelPlugin.ID),
            )
            assertTrue(
                "注册表里不得残留本插件的条目: " +
                    PluginSettingsSections.all().map { it.pluginId },
                PluginSettingsSections.all().none { it.pluginId == QQBotChannelPlugin.ID },
            )
        } finally {
            PluginSettingsSections.clear()
        }
    }

    @Test
    fun `设置区 id 逐字等于插件 id 而不是通道键`() {
        // 两套 id 空间不同：插件 id 是 `channel.qqbot`，通道键是 `qqbot`。
        // 写错会让 PluginSettingsSections.forPlugin() 永远查不到、设置区静默不显示。
        assertEquals(QQBotChannelPlugin.ID, QQBotSettingsSection.pluginId)
        assertEquals("channel.qqbot", QQBotSettingsSection.pluginId)
        assertFalse(
            "插件 id 不得退化成通道键",
            QQBotSettingsSection.pluginId == QQBotChannelPlugin.CHANNEL_KEY,
        )
    }

    @Test
    fun `设置区声明为整页呈现 FULL_PAGE`() {
        // ⚠️ 这条断言锁的是**崩溃风险**，不是观感：
        // QQBotSettingsSection 渲染的 QQBotSettingsScreen 是整页组件，内部用
        // GlassPageScaffold（背景层 + Scaffold + Column(verticalScroll)）。
        // 一旦这里被写成 INLINE，设置页就会把它内联进外层 LazyColumn 的 item 里，
        // 内层滚动容器拿到**无界最大高度约束** → 运行期抛异常。
        assertEquals(
            "整页设置区必须声明 FULL_PAGE；写成 INLINE 就是一次运行期崩溃",
            PluginSettingsPresentation.FULL_PAGE,
            QQBotSettingsSection.presentation,
        )
        assertFalse(
            "FULL_PAGE 不得退化成 INLINE",
            QQBotSettingsSection.presentation == PluginSettingsPresentation.INLINE,
        )
        // 注册表必须原样带回这条声明——设置页读的就是它。
        PluginSettingsSections.clear()
        try {
            PluginSettingsSections.register(QQBotSettingsSection)
            assertEquals(
                PluginSettingsPresentation.FULL_PAGE,
                PluginSettingsSections.forPlugin(QQBotChannelPlugin.ID)?.presentation,
            )
        } finally {
            PluginSettingsSections.clear()
        }
    }

    @Test
    fun `未注入 CHANNELS 时 setup 抛异常且不留注册条目`() {
        val registry = FakeRegistry()
        val plugin = QQBotChannelPlugin(adapterFactory = { FakeAdapter("qqbot") })
        val ctx = FakeContext(emptyMap()) // 刻意不预置 CHANNELS

        val thrown = runCatching { plugin.setup(ctx) }.exceptionOrNull()
        assertNotNull("缺依赖必须抛异常（由宿主转成 fail-closed 装载失败）", thrown)
        assertTrue(
            "异常必须点名缺失的服务键: " + thrown!!.message,
            thrown.message!!.contains(PluginServices.CHANNELS),
        )
        assertTrue("不得留下注册条目", registry.adapters().isEmpty())
        assertTrue("不得登记撤销副作用", ctx.effects.isEmpty())
    }

    // ── 4. 宿主装载链路（含 fail-closed）──

    @Test
    fun `预置 CHANNELS 时插件经宿主装载成功且注册中心可查到适配器`() {
        val registry = FakeRegistry()
        val host = FakeHost(mapOf(PluginServices.CHANNELS to registry))
        val adapter = FakeAdapter("qqbot")
        host.register(QQBotChannelPlugin(adapterFactory = { adapter }))

        assertEquals(PluginLoadResult.Loaded, host.load(QQBotChannelPlugin.ID, null))
        assertSame(adapter, registry.adapter("qqbot"))
        assertEquals(
            "ADAPTER 分派视图必须能查到本插件",
            listOf(QQBotChannelPlugin.ID),
            host.pluginsOf(PluginKind.ADAPTER).map { it.id },
        )

        assertTrue(host.unload(QQBotChannelPlugin.ID))
        assertNull(registry.adapter("qqbot"))
    }

    @Test
    fun `宿主未预置 CHANNELS 时装载 fail-closed 且不留半装配状态`() {
        val host = FakeHost(emptyMap())
        val registry = FakeRegistry()
        host.register(QQBotChannelPlugin(adapterFactory = { FakeAdapter("qqbot") }))

        val result = host.load(QQBotChannelPlugin.ID, null)
        assertTrue("缺依赖必须 fail-closed", result is PluginLoadResult.Failed)
        val reason = (result as PluginLoadResult.Failed).reason
        assertTrue("原因必须点名 CHANNELS: " + reason, reason.contains(PluginServices.CHANNELS))
        assertFalse(host.isLoaded(QQBotChannelPlugin.ID))
        assertTrue("不得留下注册条目", registry.adapters().isEmpty())
    }

    // ── 5. 出站会话（窄接口替身，无需 Android Context）──

    private class FakeSender(
        private val response: Result<com.yunian.ai.feature.qqbot.data.model.SendMessageResponse?> =
            Result.success(com.yunian.ai.feature.qqbot.data.model.SendMessageResponse(id = "msg-1")),
    ) : QQBotChannelSender {
        val sent = mutableListOf<String>()

        override val connectionState: StateFlow<com.yunian.ai.feature.qqbot.data.network.ConnectionState> =
            MutableStateFlow(com.yunian.ai.feature.qqbot.data.network.ConnectionState.DISCONNECTED)

        override val incomingEvents: kotlinx.coroutines.flow.Flow<
            com.yunian.ai.feature.qqbot.data.model.QQInboundEvent
            > = MutableStateFlow(
            com.yunian.ai.feature.qqbot.data.model.QQInboundEvent.C2CMessage(
                userOpenid = "user-1",
                raw = com.yunian.ai.feature.qqbot.data.model.QQMessageEvent(
                    id = "anchor-1",
                    content = "hi",
                ),
            )
        )

        override suspend fun sendText(
            event: com.yunian.ai.feature.qqbot.data.model.QQInboundEvent,
            text: String,
        ): Result<com.yunian.ai.feature.qqbot.data.model.SendMessageResponse?> {
            sent += event.raw.id + "|" + text
            return response
        }
    }

    @Test
    fun `适配器 start 用既有仓库包装构造会话（装配期不触碰仓库）`() {
        val sender = FakeSender()
        var supplierCalls = 0
        val adapter = QQBotChannelAdapter(
            senderSupplier = {
                supplierCalls++
                sender
            },
        )

        assertEquals("构造适配器不得触碰仓库", 0, supplierCalls)
        val session = kotlinx.coroutines.runBlocking { adapter.start() }
        assertEquals("start 才求值供应函数", 1, supplierCalls)
        assertEquals(ChannelConnectionState.DISCONNECTED, session.state.value)
        session.close()
    }

    @Test
    fun `会话按被动锚点发送文本并回填 messageRef`() {
        val sender = FakeSender()
        val session = QQBotChannelSession(sender)
        val event = com.yunian.ai.feature.qqbot.data.model.QQInboundEvent.C2CMessage(
            userOpenid = "user-1",
            raw = com.yunian.ai.feature.qqbot.data.model.QQMessageEvent(
                id = "anchor-1",
                content = "hi",
            ),
        )
        session.rememberInbound(event)

        val result = kotlinx.coroutines.runBlocking {
            session.send(ChannelOutbound.Text(replyTo = "anchor-1", text = "你好"))
        }
        assertEquals(ChannelSendResult.Sent(messageRef = "msg-1"), result)
        assertEquals(listOf("anchor-1|你好"), sender.sent)
        session.close()
    }

    @Test
    fun `找不到锚点时明确失败而不是静默丢弃`() {
        val session = QQBotChannelSession(FakeSender())
        val result = kotlinx.coroutines.runBlocking {
            session.send(ChannelOutbound.Text(replyTo = "missing", text = "你好"))
        }
        assertTrue("必须明确失败", result is ChannelSendResult.Failed)
        assertTrue(
            "失败原因必须点名锚点: " + (result as ChannelSendResult.Failed).reason,
            result.reason.contains("missing"),
        )
        session.close()
    }

    @Test
    fun `关闭后的会话拒绝发送`() {
        val session = QQBotChannelSession(FakeSender())
        session.close()
        val result = kotlinx.coroutines.runBlocking {
            session.send(ChannelOutbound.Text(replyTo = "anchor-1", text = "你好"))
        }
        assertTrue(result is ChannelSendResult.Failed)
        assertEquals(ChannelConnectionState.DISCONNECTED, session.state.value)
    }

    // ── 6. 出站请求的**订阅方**语义（P4-1：主动发送走事件，不走 ChannelSession.send）──

    /**
     * 主动发送接缝替身（无需 Android Context）。
     *
     * 刻意**不**实现 `ChannelSession` / `ChannelAdapter`：这条通路与传输契约无关，
     * 它只回答「认领到的请求该发给谁、发没发成」。
     */
    private class FakeProactiveSender(
        private var loggedIn: Boolean = true,
        private var hostId: String? = "host-openid",
        private var recentGroupId: String? = "recent-group-openid",
        private val state: ConnectionState =
            ConnectionState.CONNECTED,
        private val result: Result<QQProactiveSendResult> =
            Result.success(QQProactiveSendResult(messageRef = "msg-9")),
    ) : QQBotProactiveSender {

        val sentTargets = mutableListOf<String>()
        val sentTexts = mutableListOf<String>()

        fun setLoggedIn(value: Boolean) {
            loggedIn = value
        }

        fun setHostId(value: String?) {
            hostId = value
        }

        fun setRecentGroupId(value: String?) {
            recentGroupId = value
        }


        override fun isLoggedIn(): Boolean = loggedIn

        override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(state)

        override suspend fun boundHostId(): String? = hostId

        override suspend fun recentGroupId(): String? = recentGroupId

        override suspend fun send(
            target: QQProactiveTarget,
            text: String,
        ): Result<QQProactiveSendResult> {
            sentTargets += target.kind.name + ":" + target.id
            sentTexts += text
            return result
        }
    }

    private fun loadedPlugin(
        sender: QQBotProactiveSender?,
    ): Triple<FakeHost, FakeContext, QQBotChannelPlugin> {
        val registry = FakeRegistry()
        val services = mapOf<String, Any>(PluginServices.CHANNELS to registry)
        val plugin = QQBotChannelPlugin(
            adapterFactory = { QQBotChannelAdapter(senderSupplier = { FakeSender() }) },
            proactiveSenderSupplier = { sender },
        )
        val host = FakeHost(services)
        host.register(plugin)
        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        val ctx = host.contextOf(plugin.id) ?: error("上下文缺失")
        return Triple(host, ctx, plugin)
    }

    private fun request(
        channelKey: String = QQBotChannelPlugin.CHANNEL_KEY,
        target: String? = null,
        text: String = "你好",
    ) = ChannelOutboundRequest(channelKey = channelKey, target = target, text = text)

    @Test
    fun `setup 订阅出站请求事件（订阅即 effect）`() {
        val (host, ctx, _) = loadedPlugin(FakeProactiveSender())
        assertEquals(1, ctx.bailListenerCount(ChannelOutboundEvents.REQUEST))
        assertTrue(
            "订阅必须登记为 effect（卸载即退订）",
            ctx.effects.any { it.second == "unsubscribe:" + ChannelOutboundEvents.REQUEST },
        )
        host.unload(QQBotChannelPlugin.ID)
    }

    @Test
    fun `channelKey 不匹配时必须放行给下一个订阅者（不得吞掉别人的请求）`() {
        val (host, ctx, _) = loadedPlugin(FakeProactiveSender())
        // 在 QQ 插件**之后**再挂一个订阅者：若 QQ 插件吞掉了不匹配的请求，它永远不会被调用。
        var downstreamCalls = 0
        var downstreamPayload: Any? = null
        ctx.addBailListener(ChannelOutboundEvents.REQUEST) { payload ->
            downstreamCalls++
            downstreamPayload = payload
            null
        }

        val result = ctx.dispatchBail(
            ChannelOutboundEvents.REQUEST,
            request(channelKey = ChannelKeys.WECHAT),
        )
        assertFalse("不匹配的请求必须不被认领（isBailed=false 才能放行）", result.isBailed)
        assertEquals("必须真的放行到下一个订阅者", 1, downstreamCalls)
        assertEquals(
            "下游收到的必须还是原请求",
            request(channelKey = ChannelKeys.WECHAT),
            downstreamPayload,
        )
        host.unload(QQBotChannelPlugin.ID)
    }

    @Test
    fun `channelKey 匹配时认领并 bail 出结果`() {
        val sender = FakeProactiveSender()
        val (host, ctx, _) = loadedPlugin(sender)
        val result = ctx.dispatchBail(ChannelOutboundEvents.REQUEST, request(text = "晚安"))
        assertTrue("匹配的请求必须被认领", result.isBailed)
        assertEquals(ChannelOutboundResult.Sent(messageRef = "msg-9"), result.bailValue)
        assertEquals(listOf("USER:host-openid"), sender.sentTargets)
        assertEquals(listOf("晚安"), sender.sentTexts)
        host.unload(QQBotChannelPlugin.ID)
    }

    @Test
    fun `target 为 null 时发给绑定宿主（用户本人）的 openid`() {
        val sender = FakeProactiveSender(hostId = "host-42")
        val (host, ctx, _) = loadedPlugin(sender)
        ctx.dispatchBail(ChannelOutboundEvents.REQUEST, request(target = null))
        assertEquals(listOf("USER:host-42"), sender.sentTargets)
        host.unload(QQBotChannelPlugin.ID)
    }

    @Test
    fun `target 为 group_ 前缀时走群 openid`() {
        val sender = FakeProactiveSender()
        val (host, ctx, _) = loadedPlugin(sender)
        ctx.dispatchBail(ChannelOutboundEvents.REQUEST, request(target = "group:g-7"))
        assertEquals(listOf("GROUP:g-7"), sender.sentTargets)
        host.unload(QQBotChannelPlugin.ID)
    }

    @Test
    fun `target 为 group 时走最近一次可信入站群路由`() {
        val sender = FakeProactiveSender(recentGroupId = "gateway-group-8")
        val (host, ctx, _) = loadedPlugin(sender)
        ctx.dispatchBail(ChannelOutboundEvents.REQUEST, request(target = "group"))
        assertEquals(listOf("GROUP:gateway-group-8"), sender.sentTargets)
        host.unload(QQBotChannelPlugin.ID)
    }

    @Test
    fun `target 为 recent_group 时兼容最近群别名`() {
        val sender = FakeProactiveSender(recentGroupId = "gateway-group-9")
        val (host, ctx, _) = loadedPlugin(sender)
        ctx.dispatchBail(ChannelOutboundEvents.REQUEST, request(target = "recent_group"))
        assertEquals(listOf("GROUP:gateway-group-9"), sender.sentTargets)
        host.unload(QQBotChannelPlugin.ID)
    }


    @Test
    fun `没有捕获过群路由时 target group 明确失败且绝不猜测`() {
        val sender = FakeProactiveSender(recentGroupId = null)
        val (host, ctx, _) = loadedPlugin(sender)
        val result = ctx.dispatchBail(ChannelOutboundEvents.REQUEST, request(target = "group"))
        val value = result.bailValue
        assertTrue(value is ChannelOutboundResult.Failed)
        assertTrue((value as ChannelOutboundResult.Failed).reason.contains("先在群里 @ 机器人一次"))
        assertEquals(emptyList<String>(), sender.sentTargets)
        host.unload(QQBotChannelPlugin.ID)
    }

    @Test
    fun `没有可用目标时明确失败而不是猜一个目标`() {
        val sender = FakeProactiveSender(hostId = null)
        val (host, ctx, _) = loadedPlugin(sender)
        val result = ctx.dispatchBail(ChannelOutboundEvents.REQUEST, request(target = null))
        assertTrue("必须认领（这是本通道的请求）", result.isBailed)
        val value = result.bailValue
        assertTrue("必须明确失败", value is ChannelOutboundResult.Failed)
        assertTrue(
            "失败原因必须点名「没有可用目标」: " + (value as ChannelOutboundResult.Failed).reason,
            value.reason.contains("没有可用的发送目标"),
        )
        assertEquals("不得发起任何发送", emptyList<String>(), sender.sentTargets)
        host.unload(QQBotChannelPlugin.ID)
    }

    @Test
    fun `未配置账号时明确失败`() {
        val sender = FakeProactiveSender(loggedIn = false)
        val (host, ctx, _) = loadedPlugin(sender)
        val result = ctx.dispatchBail(ChannelOutboundEvents.REQUEST, request())
        val value = result.bailValue
        assertTrue("必须明确失败", value is ChannelOutboundResult.Failed)
        assertTrue(
            "失败原因必须点名未配置账号: " + (value as ChannelOutboundResult.Failed).reason,
            value.reason.contains("未配置账号"),
        )
        assertEquals("不得发起任何发送", emptyList<String>(), sender.sentTargets)
        host.unload(QQBotChannelPlugin.ID)
    }

    @Test
    fun `通道未连接时明确失败且不发送`() {
        val sender = FakeProactiveSender(state = ConnectionState.RECONNECTING)
        val (host, ctx, _) = loadedPlugin(sender)
        val result = ctx.dispatchBail(ChannelOutboundEvents.REQUEST, request())
        val value = result.bailValue
        assertTrue("必须明确失败", value is ChannelOutboundResult.Failed)
        assertTrue(
            "失败原因必须点名未连接: " + (value as ChannelOutboundResult.Failed).reason,
            value.reason.contains("未连接"),
        )
        assertEquals("不得发起任何发送", emptyList<String>(), sender.sentTargets)
        host.unload(QQBotChannelPlugin.ID)
    }

    @Test
    fun `发送失败时如实上报原因而不是假装成功`() {
        val sender = FakeProactiveSender(
            result = Result.failure(IllegalStateException("主动发送失败: 500")),
        )
        val (host, ctx, _) = loadedPlugin(sender)
        val result = ctx.dispatchBail(ChannelOutboundEvents.REQUEST, request())
        val value = result.bailValue
        assertTrue("必须明确失败", value is ChannelOutboundResult.Failed)
        assertTrue(
            "必须带上通道侧原因: " + (value as ChannelOutboundResult.Failed).reason,
            value.reason.contains("500"),
        )
        host.unload(QQBotChannelPlugin.ID)
    }

    @Test
    fun `主动发送未接线时明确失败（不假装成功）`() {
        val (host, ctx, _) = loadedPlugin(null)
        val result = ctx.dispatchBail(ChannelOutboundEvents.REQUEST, request())
        val value = result.bailValue
        assertTrue("必须明确失败", value is ChannelOutboundResult.Failed)
        assertTrue(
            "失败原因必须点名未接线: " + (value as ChannelOutboundResult.Failed).reason,
            value.reason.contains("未接线"),
        )
        host.unload(QQBotChannelPlugin.ID)
    }

    @Test
    fun `插件卸载后请求不再被认领（可自由启用停用的验收点）`() {
        val sender = FakeProactiveSender()
        val (host, ctx, _) = loadedPlugin(sender)

        assertTrue(
            "装载态下必须被认领",
            ctx.dispatchBail(ChannelOutboundEvents.REQUEST, request()).isBailed,
        )

        host.unload(QQBotChannelPlugin.ID)
        assertEquals(
            "卸载必须退订（effect 生效）",
            0,
            ctx.bailListenerCount(ChannelOutboundEvents.REQUEST),
        )
        assertFalse(
            "卸载后同一请求必须变成「无人认领」",
            ctx.dispatchBail(ChannelOutboundEvents.REQUEST, request()).isBailed,
        )
        assertEquals("卸载后不得再发送", 1, sender.sentTargets.size)
    }

    @Test
    fun `非本通道请求不会被卸载影响（订阅互不干扰）`() {
        val (host, ctx, _) = loadedPlugin(FakeProactiveSender())
        var downstreamCalls = 0
        ctx.addBailListener(ChannelOutboundEvents.REQUEST) { downstreamCalls++; null }
        host.unload(QQBotChannelPlugin.ID)
        val result = ctx.dispatchBail(
            ChannelOutboundEvents.REQUEST,
            request(channelKey = ChannelKeys.WECHAT),
        )
        assertFalse(result.isBailed)
        assertEquals("下游订阅者仍应被调用", 1, downstreamCalls)
    }

    @Test
    fun `既有连接状态五态逐项映射到契约状态`() {
        assertEquals(
            ChannelConnectionState.DISCONNECTED,
            com.yunian.ai.feature.qqbot.data.network.ConnectionState.DISCONNECTED
                .toChannelConnectionState(),
        )
        assertEquals(
            ChannelConnectionState.CONNECTING,
            com.yunian.ai.feature.qqbot.data.network.ConnectionState.CONNECTING
                .toChannelConnectionState(),
        )
        assertEquals(
            ChannelConnectionState.CONNECTED,
            com.yunian.ai.feature.qqbot.data.network.ConnectionState.CONNECTED
                .toChannelConnectionState(),
        )
        assertEquals(
            ChannelConnectionState.RECONNECTING,
            com.yunian.ai.feature.qqbot.data.network.ConnectionState.RECONNECTING
                .toChannelConnectionState(),
        )
        assertEquals(
            ChannelConnectionState.FAILED,
            com.yunian.ai.feature.qqbot.data.network.ConnectionState.AUTH_FAILED
                .toChannelConnectionState(),
        )
    }
}