package com.yunian.ai.agent.tools

import com.yunian.ai.agent.plugin.MessageSendPlugin
import com.yunian.ai.agent.plugin.NoOpPluginLog
import com.yunian.ai.agent.plugin.PluginEventBus
import com.yunian.ai.agent.plugin.PluginHostImpl
import com.yunian.ai.common.ContentFilter
import com.yunian.ai.domain.ChannelKeys
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.domain.channel.ChannelOutboundEvents
import com.yunian.ai.domain.channel.ChannelOutboundRequest
import com.yunian.ai.domain.channel.ChannelOutboundResult
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginLoadResult
import com.yunian.ai.domain.plugin.PluginServices
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息发送**发起方**工具与插件的接线测试（P4-1）。
 *
 * ## 被测对象（全部是真代码）
 *
 * - [ChannelSendTool]：真实的发起方工具（参数校验 / 安全门 / 事件派发 / 结果映射）；
 * - [MessageSendPlugin]：真实的 TOOL 插件；
 * - [PluginHostImpl] + [PluginContextImpl] + [PluginEventBus]：**真实的**宿主与宿主级总线
 *   （不是替身——本包就在 :core:agent 内，可以直接用生产实现）；
 * - [ToolRegistry]：真实的运行期可变注册表。
 *
 * 只有「通道插件」是替身：core:agent 不能依赖任何 feature 模块（feature 才持有真实通道），
 * 因此这里用与契约同构的最小替身通道插件订阅 [ChannelOutboundEvents.REQUEST]。
 *
 * ## 在旧实现下为什么失败
 *
 * 旧实现里 `ChannelOutboundEvents` / `ChannelOutboundRequest` / `ChannelOutboundResult` /
 * [MessageSendPlugin] / [ChannelSendTool] **都不存在**，本文件根本编译不过——
 * 如实说明：这是新能力的契约测试，不是既有行为的回归测试。
 */
class ChannelSendToolTest {

    private val registeredByThisTest = mutableListOf<String>()

    @After
    fun tearDown() {
        // ToolRegistry 是进程级单例：用例结束必须摘干净，否则污染同 JVM 的其它用例。
        registeredByThisTest.forEach { ToolRegistry.unregister(it) }
        registeredByThisTest.clear()
        ToolRegistry.unregister(ChannelSendTool.NAME)
    }

    // ── 测试替身 ──

    /** 通道插件替身：订阅出站请求事件，按 `channelKey` 路由，可选是否放行 / 是否应答。 */
    private class FakeChannelPlugin(
        private val key: String,
        private val answer: ChannelOutboundResult? = ChannelOutboundResult.Sent(messageRef = "fake-1"),
        /** 认领后是否仍然返回 null（模拟「坏监听器」）——用于验证发起方不把它当成功。 */
        private val swallowAndAnswerNull: Boolean = false,
    ) : LianYuPlugin {

        val seen = mutableListOf<ChannelOutboundRequest>()

        override val id: String = "test.channel." + key
        override val name: String = "test channel " + key
        override val kind: PluginKind = PluginKind.ADAPTER
        override val requires: Set<String> = emptySet()
        override val configSchema: String? = null

        override fun setup(ctx: PluginContext) {
            ctx.onBail(ChannelOutboundEvents.REQUEST) { payload ->
                if (payload !is ChannelOutboundRequest || payload.channelKey != key) {
                    null // 不是给我的：放行
                } else {
                    seen += payload
                    if (swallowAndAnswerNull) null else answer
                }
            }
        }
    }

    /**
     * 宿主替身：与 PluginHostEventBusTest 同一手法（真实总线 + 真实上下文）。
     *
     * 预置真实的 [ToolRegistry] 作为 TOOLS 服务——这正是 :app 里宿主装配的做法，
     * 因此本测试驱动的装载路径与生产一致。
     */
    private fun newHost(): PluginHostImpl = PluginHostImpl(
        mapOf(PluginServices.TOOLS to ToolRegistry),
        NoOpPluginLog,
        PluginEventBus(NoOpPluginLog),
    )

    /**
     * 装载**真实的** [MessageSendPlugin]，并把宿主交给它的那个真实上下文带出来。
     *
     * 之所以包一层：真实 `PluginHostImpl` 不对外暴露「插件 → 上下文」的查询面
     * （那本来就是实现细节），而本测试需要在装载**之后**继续派发事件。
     * 包装器**只转调**真实插件的 setup，不添加任何行为——被测的注册 / effect 语义
     * 仍然是真实插件自己的代码。
     */
    private class ContextCapturingPlugin(
        private val delegate: LianYuPlugin,
    ) : LianYuPlugin by delegate {
        var captured: PluginContext? = null
            private set

        override fun setup(ctx: PluginContext) {
            captured = ctx
            delegate.setup(ctx)
        }
    }

    private fun installSendPlugin(host: PluginHostImpl): PluginContext {
        val wrapper = ContextCapturingPlugin(MessageSendPlugin())
        host.register(wrapper)
        assertEquals(PluginLoadResult.Loaded, host.load(wrapper.id, null))
        val ctx = wrapper.captured
        assertNotNull("宿主必须为已装载插件保留上下文", ctx)
        return ctx!!
    }

    /** 装一个真实的发起方工具（用给定的安全门），并登记清理。 */
    private fun installTool(
        ctx: PluginContext,
        safety: (String) -> ContentFilter.OutputSafetyResult = { ContentFilter.OutputSafetyResult(true, ContentFilter.ViolationLevel.NONE, "") },
    ): ChannelSendTool {
        val tool = ChannelSendTool(ctx, safety)
        ToolRegistry.register(tool)
        registeredByThisTest += tool.name
        return tool
    }

    private fun safe() = ContentFilter.OutputSafetyResult(
        isSafe = true,
        level = ContentFilter.ViolationLevel.NONE,
        reason = "",
    )

    private fun unsafe(reason: String) = ContentFilter.OutputSafetyResult(
        isSafe = false,
        level = ContentFilter.ViolationLevel.HIGH,
        reason = reason,
    )

    private fun args(channelKey: String, text: String, target: String? = null): String =
        if (target == null) {
            "{\"channelKey\":\"" + channelKey + "\",\"text\":\"" + text + "\"}"
        } else {
            "{\"channelKey\":\"" + channelKey + "\",\"target\":\"" + target +
                "\",\"text\":\"" + text + "\"}"
        }

    private fun assertFailure(json: String, expectedCode: String) {
        assertTrue("必须是失败应答: " + json, json.contains("\"ok\":false"))
        assertTrue(
            "错误码必须是 " + expectedCode + "，实际: " + json,
            json.contains("\"error\":\"" + expectedCode + "\""),
        )
    }

    // ── 1. 工具契约（验收点 1）──

    @Test
    fun `工具是本机敏感且默认走确认门`() {
        val tool = ChannelSendTool(newHost().let { installSendPlugin(it) })
        assertTrue("必须 appLocalOnly（渠道隔离的第一道闸）", tool.appLocalOnly)
        assertTrue("必须 requiresConfirmation（用户裁定 Q3：默认需确认）", tool.requiresConfirmation)
    }

    @Test
    fun `工具名一次定死`() {
        assertEquals("send_channel_message", ChannelSendTool.NAME)
        val tool = ChannelSendTool(newHost().let { installSendPlugin(it) })
        assertEquals(ChannelSendTool.NAME, tool.name)
        assertEquals(setOf("channel"), tool.toolsets)
    }

    @Test
    fun `isAvailable 恒为真（连接状态不得走 30s TTL 缓存）`() {
        val tool = ChannelSendTool(newHost().let { installSendPlugin(it) })
        assertTrue(
            "可用性必须恒真：连接/登录只能在 execute 内实时再判（TTL 30s + flake 60s）",
            tool.isAvailable(),
        )
    }

    @Test
    fun `参数 schema 只声明通道目标与文本`() {
        val tool = ChannelSendTool(newHost().let { installSendPlugin(it) })
        val schema = tool.parametersJsonSchema
        assertTrue(schema.contains("\"channelKey\""))
        assertTrue(schema.contains("\"text\""))
        assertTrue(schema.contains("\"target\""))
        assertTrue("channelKey 与 text 必填", schema.contains("\"required\":[\"channelKey\",\"text\"]"))
    }

    // ── 2. 插件装载 / 卸载与注册表可见性（验收点 2）──

    @Test
    fun `插件装载后工具在注册表可见且本机会话能枚举到`() {
        val host = newHost()
        installSendPlugin(host)

        val registered = ToolRegistry.get(ChannelSendTool.NAME)
        assertNotNull("装载后必须注册进 ToolRegistry", registered)
        assertTrue(
            "本机会话（includeAppLocal=true）必须能看到它",
            ToolRegistry.availableTools(includeAppLocal = true).any { it.name == ChannelSendTool.NAME },
        )
    }

    @Test
    fun `外部桥接会话看不到本机发送工具`() {
        val host = newHost()
        installSendPlugin(host)

        assertFalse(
            "非本机会话（默认 includeAppLocal=false）必须枚举不到",
            ToolRegistry.availableTools().any { it.name == ChannelSendTool.NAME },
        )
        assertFalse(
            "工具集枚举同样不得泄漏",
            ToolRegistry.toolsInToolset("channel").any { it.name == ChannelSendTool.NAME },
        )
    }

    @Test
    fun `插件卸载后工具从注册表消失（effect 生效）`() {
        val host = newHost()
        installSendPlugin(host)
        assertNotNull(ToolRegistry.get(ChannelSendTool.NAME))

        assertTrue(host.unload(MessageSendPlugin.ID))

        assertNull("卸载必须注销工具（卸载不留鸡毛）", ToolRegistry.get(ChannelSendTool.NAME))
        assertFalse(
            "卸载后本机会话也枚举不到",
            ToolRegistry.availableTools(includeAppLocal = true).any { it.name == ChannelSendTool.NAME },
        )
    }

    @Test
    fun `插件依赖声明 TOOLS 且清单与自描述一致`() {
        val plugin = MessageSendPlugin()
        assertEquals(MessageSendPlugin.ID, plugin.id)
        assertEquals(PluginKind.TOOL, plugin.kind)
        assertEquals(setOf(PluginServices.TOOLS), plugin.requires)
        assertEquals(plugin.id, plugin.manifest.id)
        assertEquals(plugin.kind, plugin.manifest.kind)
        assertEquals(plugin.requires.sorted(), plugin.manifest.requires)
        assertNull(plugin.configSchema)
    }

    @Test
    fun `宿主未预置 TOOLS 时装载 fail-closed`() {
        val host = PluginHostImpl(
            emptyMap(),
            NoOpPluginLog,
            PluginEventBus(NoOpPluginLog),
        )
        val plugin = MessageSendPlugin()
        host.register(plugin)

        val result = host.load(plugin.id, null)
        assertTrue("缺依赖必须拒绝装载: " + result, result is PluginLoadResult.Failed)
        assertFalse(host.isLoaded(plugin.id))
        assertNull("不得留下半装配状态", ToolRegistry.get(ChannelSendTool.NAME))
    }

    // ── 3. 无通道订阅时必须是失败（验收点 3）──

    @Test
    fun `没有任何通道插件订阅时工具返回失败而不是成功`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val tool = installTool(ctx)

        val out = tool.execute(args(ChannelKeys.QQBOT, "你好"))

        assertFailure(out, "no_channel")
        assertTrue("必须点名通道未启用: " + out, out.contains("该通道未启用"))
        assertFalse("绝不假装成功", out.contains("\"ok\":true"))
    }

    @Test
    fun `订阅者只处理匹配的通道其余请求仍然无人认领`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        host.register(FakeChannelPlugin(ChannelKeys.QQBOT))
        assertEquals(PluginLoadResult.Loaded, host.load("test.channel." + ChannelKeys.QQBOT, null))
        val tool = installTool(ctx)

        // QQ 有人认领 → 成功
        val ok = tool.execute(args(ChannelKeys.QQBOT, "你好"))
        assertTrue("QQ 请求必须成功: " + ok, ok.contains("\"ok\":true"))

        // 微信没人认领 → 明确失败（不得因为「有别的通道插件」就当作处理过）
        assertFailure(tool.execute(args(ChannelKeys.WECHAT, "你好")), "no_channel")
    }

    @Test
    fun `订阅者认领却不应答时不得当成成功`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val bad = FakeChannelPlugin(ChannelKeys.QQBOT, swallowAndAnswerNull = true)
        host.register(bad)
        assertEquals(PluginLoadResult.Loaded, host.load(bad.id, null))
        val tool = installTool(ctx)

        val out = tool.execute(args(ChannelKeys.QQBOT, "你好"))

        assertFailure(out, "no_channel")
        assertEquals("请求确实到达了订阅者", 1, bad.seen.size)
    }

    // ── 4. 事件派发的内容与结果语义 ──

    @Test
    fun `请求经插件事件派发到订阅者且文本与目标原样传递`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val channel = FakeChannelPlugin(ChannelKeys.QQBOT)
        host.register(channel)
        assertEquals(PluginLoadResult.Loaded, host.load(channel.id, null))
        val tool = installTool(ctx)

        val out = tool.execute(args(ChannelKeys.QQBOT, "晚安", target = "group:g-1"))

        assertEquals(1, channel.seen.size)
        assertEquals(ChannelOutboundRequest(ChannelKeys.QQBOT, "group:g-1", "晚安"), channel.seen[0])
        assertTrue(out.contains("\"ok\":true"))
        assertTrue("必须回填通道侧消息标识: " + out, out.contains("\"messageRef\":\"fake-1\""))
    }

    @Test
    fun `target 省略时按 null 派发（语义=本通道绑定的宿主）`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val channel = FakeChannelPlugin(ChannelKeys.QQBOT)
        host.register(channel)
        assertEquals(PluginLoadResult.Loaded, host.load(channel.id, null))
        val tool = installTool(ctx)

        tool.execute(args(ChannelKeys.QQBOT, "你好"))

        assertNull("target 必须原样是 null，由通道解释成「用户本人」", channel.seen[0].target)
    }

    @Test
    fun `成功结果必须无歧义地区分发送成功与送达状态未知`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val channel = FakeChannelPlugin(ChannelKeys.QQBOT)
        host.register(channel)
        assertEquals(PluginLoadResult.Loaded, host.load(channel.id, null))
        val tool = installTool(ctx)

        val out = tool.execute(args(ChannelKeys.QQBOT, "你好"))

        assertTrue("必须重复强化发送成功: " + out, out.contains("\"send_succeeded\":true"))
        assertTrue("成功状态必须是 sent: " + out, out.contains("\"status\":\"sent\""))
        assertTrue("无送达回执用枚举表达，不能伪装成失败: " + out, out.contains("\"delivery_status\":\"unknown\""))
        assertTrue("必须指导模型按成功向用户汇报: " + out, out.contains("请向用户确认已发出"))
        assertFalse("成功结果不得包含易被模型误读的 false: " + out, out.contains(":false"))
        assertFalse("成功结果不得出现 failed: " + out, out.contains("failed"))
        assertFalse("不得出现已送达声明: " + out, out.contains("\"delivered\":true"))
    }

    @Test
    fun `通道侧失败原因原样上报`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val channel = FakeChannelPlugin(
            ChannelKeys.QQBOT,
            answer = ChannelOutboundResult.Failed("QQ 通道当前未连接（状态 RECONNECTING）"),
        )
        host.register(channel)
        assertEquals(PluginLoadResult.Loaded, host.load(channel.id, null))
        val tool = installTool(ctx)

        val out = tool.execute(args(ChannelKeys.QQBOT, "你好"))

        assertFailure(out, "send_failed")
        assertTrue("必须带上通道侧原因: " + out, out.contains("未连接"))
    }

    @Test
    fun `通道应答类型不对时明确失败`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        // 一个「乱应答」的订阅者：bail 出的不是契约结果类型。
        val rogue = object : LianYuPlugin {
            override val id: String = "test.channel.rogue"
            override val name: String = "rogue"
            override val kind: PluginKind = PluginKind.ADAPTER
            override val requires: Set<String> = emptySet()
            override val configSchema: String? = null
            override fun setup(ctx: PluginContext) {
                ctx.onBail(ChannelOutboundEvents.REQUEST) { payload ->
                    if (payload is ChannelOutboundRequest && payload.channelKey == ChannelKeys.QQBOT) {
                        "not-a-result"
                    } else {
                        null
                    }
                }
            }
        }
        host.register(rogue)
        assertEquals(PluginLoadResult.Loaded, host.load(rogue.id, null))
        val tool = installTool(ctx)

        assertFailure(tool.execute(args(ChannelKeys.QQBOT, "你好")), "bad_channel_response")
    }

    // ── 5. 参数校验 ──

    @Test
    fun `未知通道明确失败且不派发`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val channel = FakeChannelPlugin("something.else")
        host.register(channel)
        assertEquals(PluginLoadResult.Loaded, host.load(channel.id, null))
        val tool = installTool(ctx)

        assertFailure(tool.execute(args("something.else", "你好")), "unknown_channel")
        assertEquals("未知通道不得进入派发", 0, channel.seen.size)
    }

    @Test
    fun `未声明通道明确失败且不派发`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val channel = FakeChannelPlugin(ChannelKeys.QQBOT)
        host.register(channel)
        assertEquals(PluginLoadResult.Loaded, host.load(channel.id, null))
        val tool = installTool(ctx)

        assertFailure(tool.execute("{\"text\":\"你好\"}"), "missing_channel")
        assertEquals(0, channel.seen.size)
    }

    @Test
    fun `空白文本明确失败且不派发`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val channel = FakeChannelPlugin(ChannelKeys.QQBOT)
        host.register(channel)
        assertEquals(PluginLoadResult.Loaded, host.load(channel.id, null))
        val tool = installTool(ctx)

        assertFailure(tool.execute(args(ChannelKeys.QQBOT, "   ")), "missing_text")
        assertEquals(0, channel.seen.size)
    }

    @Test
    fun `超长文本明确失败而不是截断`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val channel = FakeChannelPlugin(ChannelKeys.QQBOT)
        host.register(channel)
        assertEquals(PluginLoadResult.Loaded, host.load(channel.id, null))
        val tool = installTool(ctx)

        val long = "啊".repeat(ChannelSendTool.MAX_TEXT_LENGTH + 1)
        assertFailure(tool.execute(args(ChannelKeys.QQBOT, long)), "text_too_long")
        assertEquals("超长不得截断后照发", 0, channel.seen.size)
    }

    @Test
    fun `参数不是合法 JSON 时明确失败`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val tool = installTool(ctx)

        assertFailure(tool.execute("{not json"), "malformed_arguments")
    }

    // ── 6. 出站安全门（验收点 8）──

    @Test
    fun `文本未过安全过滤时不发送`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val channel = FakeChannelPlugin(ChannelKeys.QQBOT)
        host.register(channel)
        assertEquals(PluginLoadResult.Loaded, host.load(channel.id, null))
        val tool = installTool(ctx) { unsafe("命中高危词") }

        val out = tool.execute(args(ChannelKeys.QQBOT, "危险内容"))

        assertFailure(out, "unsafe_content")
        assertEquals("不安全的内容绝不能进入派发", 0, channel.seen.size)
        assertTrue("必须给出拒绝原因: " + out, out.contains("命中高危词"))
    }

    @Test
    fun `安全门自身故障时 fail-closed 拒绝发送`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val channel = FakeChannelPlugin(ChannelKeys.QQBOT)
        host.register(channel)
        assertEquals(PluginLoadResult.Loaded, host.load(channel.id, null))
        val tool = installTool(ctx) { throw IllegalStateException("filter down") }

        assertFailure(tool.execute(args(ChannelKeys.QQBOT, "你好")), "safety_check_failed")
        assertEquals("安全门故障时必须拒绝发送", 0, channel.seen.size)
    }

    @Test
    fun `安全过滤先于通道派发（顺序契约）`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val channel = FakeChannelPlugin(ChannelKeys.QQBOT)
        host.register(channel)
        assertEquals(PluginLoadResult.Loaded, host.load(channel.id, null))
        val checked = mutableListOf<String>()
        val tool = installTool(ctx) { text ->
            checked += text
            safe()
        }

        tool.execute(args(ChannelKeys.QQBOT, "你好"))

        assertEquals(listOf("你好"), checked)
        assertEquals(1, channel.seen.size)
    }

    // ── 7. 通道插件卸载后工具对该通道变为失败（验收点 9）──

    @Test
    fun `通道插件卸载后工具对该通道的请求变为失败`() = runBlocking {
        val host = newHost()
        val ctx = installSendPlugin(host)
        val channel = FakeChannelPlugin(ChannelKeys.QQBOT)
        host.register(channel)
        assertEquals(PluginLoadResult.Loaded, host.load(channel.id, null))
        val tool = installTool(ctx)

        assertTrue("装载态下必须成功", tool.execute(args(ChannelKeys.QQBOT, "你好")).contains("\"ok\":true"))

        assertTrue(host.unload(channel.id))

        assertFailure(
            tool.execute(args(ChannelKeys.QQBOT, "你好")),
            "no_channel",
        )
    }

    @Test
    fun `发起方插件卸载后工具消失（不残留可调用的工具）`() = runBlocking {
        val host = newHost()
        installSendPlugin(host)
        assertNotNull(ToolRegistry.get(ChannelSendTool.NAME))

        host.unload(MessageSendPlugin.ID)

        assertNull(ToolRegistry.get(ChannelSendTool.NAME))
    }
}
