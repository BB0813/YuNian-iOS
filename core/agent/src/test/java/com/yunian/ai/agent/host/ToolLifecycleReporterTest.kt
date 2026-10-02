package com.yunian.ai.agent.host

import com.yunian.ai.agent.plugin.NoOpPluginLog
import com.yunian.ai.agent.plugin.PluginEventBus
import com.yunian.ai.agent.plugin.PluginHostImpl
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginLoadResult
import com.yunian.ai.domain.plugin.ToolFinishStatus
import com.yunian.ai.domain.plugin.ToolFinished
import com.yunian.ai.domain.plugin.ToolLifecycleEvents
import com.yunian.ai.domain.plugin.ToolStarted
import com.yunian.ai.domain.plugin.on
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 工具生命周期事件（tool.started / tool.finished）的**生产路径**验收。
 *
 * 被测对象是真实装配：ToolLifecycleReporter → PluginHostImpl（真实宿主，
 * 同时是 PluginEventPublisher）→ PluginEventBus（宿主级共享总线）→ 订阅插件。
 * 只有订阅插件与日志出口是替身，因为 core:agent 不能依赖任何 feature 模块。
 *
 * 与 [com.yunian.ai.agent.plugin.PluginHostEventBusTest] 的分工：那边验证宿主事件
 * 派发语义；这里验证「工具执行必然产出配对的 started/finished，且观察侧故障
 * 绝不影响工具结果」。
 */
class ToolLifecycleReporterTest {

    /** 订阅替身：把收到的事件按到达顺序记录，可选在回调里抛异常。
     *
     * 抛出回调异常用于验证观察侧故障隔离：契约规定监听器异常不中断 emit 派发，
     * 且绝不改变工具返回值。
     */
    private class RecordingPlugin(
        override val id: String,
        private val received: MutableList<Any>,
        private val failOnEvent: Boolean = false,
    ) : LianYuPlugin {
        override val name: String = "recording:" + id
        override val kind: PluginKind = PluginKind.PIPELINE
        override val requires: Set<String> = emptySet()
        override val configSchema: String? = null

        override fun setup(ctx: PluginContext) {
            ctx.on(ToolLifecycleEvents.STARTED) { event ->
                if (failOnEvent) error("subscriber boom")
                received += event
            }
            ctx.on(ToolLifecycleEvents.FINISHED) { event ->
                if (failOnEvent) error("subscriber boom")
                received += event
            }
        }
    }

    /** 只取实例属性：静态/合成字段（如编译器注入物）不属于事件载荷契约。 */
    private fun instanceFieldNames(type: Class<*>): Set<String> = type.declaredFields
        .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.isSynthetic }
        .map { it.name }
        .toSet()

    private fun newHost(): PluginHostImpl =
        PluginHostImpl(emptyMap<String, Any>(), NoOpPluginLog, PluginEventBus(NoOpPluginLog))

    private fun loadRecordingPlugin(
        host: PluginHostImpl,
        received: MutableList<Any>,
        id: String = "test.recorder",
        failOnEvent: Boolean = false,
    ) {
        val plugin = RecordingPlugin(id, received, failOnEvent)
        host.register(plugin)
        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
    }

    // ── 1. 正常路径：started → finished 成对且同 id ──

    @Test
    fun `成功执行产出配对的 started 与 finished（真实宿主 + 真实总线）`() {
        val host = newHost()
        val received = mutableListOf<Any>()
        loadRecordingPlugin(host, received)
        val reporter = ToolLifecycleReporter(host, "stream-ok")

        val result = withToolLifecycle(reporter, "channel.send_message") { "ok" }

        assertEquals("ok", result)
        assertEquals(2, received.size)
        val started = received[0] as ToolStarted
        val finished = received[1] as ToolFinished
        assertEquals("stream-ok", started.streamId)
        assertEquals("channel.send_message", started.toolName)
        assertEquals(started.callId, finished.callId)
        assertEquals(ToolFinishStatus.SUCCEEDED, finished.status)
        assertTrue("elapsed 不得为负", finished.elapsedMs >= 0L)
    }

    // ── 2. 失败：标记 FAILED 且原异常继续抛出 ──

    @Test
    fun `执行失败标记 FAILED 且原始异常继续向上抛出`() {
        val host = newHost()
        val received = mutableListOf<Any>()
        loadRecordingPlugin(host, received)
        val reporter = ToolLifecycleReporter(host, "stream-fail")
        val boom = IllegalStateException("tool exploded")

        try {
            withToolLifecycle(reporter, "failing.tool") { throw boom }
            fail("异常必须继续抛出，不得被生命周期观察吞掉")
        } catch (thrown: IllegalStateException) {
            assertTrue("必须是同一个异常实例", thrown === boom)
        }

        assertEquals(2, received.size)
        assertEquals(ToolFinishStatus.FAILED, (received[1] as ToolFinished).status)
    }

    // ── 3. 超时 / 4. 取消 各自映射到独立终态 ──

    @Test
    fun `超时映射为 TIMED_OUT`() {
        val host = newHost()
        val received = mutableListOf<Any>()
        loadRecordingPlugin(host, received)
        val reporter = ToolLifecycleReporter(host, "stream-timeout")

        // TimeoutCancellationException 无公开构造函数（库内 internal），
        // 因此用真实 withTimeout 产生，与生产 ToolHost 的超时来源一致。
        val thrown = runCatching {
            withToolLifecycle(reporter, "slow.tool") {
                runBlocking { withTimeout(1L) { delay(50L) } }
            }
        }.exceptionOrNull()

        assertTrue("超时必须继续抛出，不得被观察吞掉", thrown is TimeoutCancellationException)
        assertEquals(2, received.size)
        assertEquals(ToolFinishStatus.TIMED_OUT, (received[1] as ToolFinished).status)
    }

    @Test
    fun `取消映射为 CANCELLED`() {
        val host = newHost()
        val received = mutableListOf<Any>()
        loadRecordingPlugin(host, received)
        val reporter = ToolLifecycleReporter(host, "stream-cancel")

        try {
            withToolLifecycle(reporter, "cancelled.tool") { throw CancellationException("cancelled") }
            fail("取消必须继续抛出")
        } catch (expected: CancellationException) {
            // 预期路径
        }

        assertEquals(2, received.size)
        assertEquals(ToolFinishStatus.CANCELLED, (received[1] as ToolFinished).status)
    }

    // ── 5. 每个 started 恰好一个 finished（finish 幂等） ──

    @Test
    fun `每个 started 恰好对应一个 finished，重复标记不重复投递`() {
        val host = newHost()
        val received = mutableListOf<Any>()
        loadRecordingPlugin(host, received)
        val reporter = ToolLifecycleReporter(host, "stream-idem")

        val result = withToolLifecycle(reporter, "idem.tool") { mark ->
            mark(ToolFinishStatus.FAILED)
            mark(ToolFinishStatus.SUCCEEDED)
            "done"
        }

        assertEquals("done", result)
        // 幂等点在「投递」上：块内标记多少次，都只有一对 started/finished。
        assertEquals("重复标记不得追加事件", 2, received.size)
        assertEquals(1, received.filterIsInstance<ToolStarted>().size)
        val finishedEvents = received.filterIsInstance<ToolFinished>()
        assertEquals(1, finishedEvents.size)
        // marker 是状态「写入」而非追加：后一次标记覆盖前一次（生产路径每路只标记一次）。
        assertEquals(
            "后一次标记覆盖前一次",
            ToolFinishStatus.SUCCEEDED,
            finishedEvents.single().status,
        )
    }

    // ── 6. 观察侧故障隔离 ──

    @Test
    fun `订阅者抛异常不影响工具返回值与终态投递`() {
        val host = newHost()
        val received = mutableListOf<Any>()
        loadRecordingPlugin(host, received, id = "test.boom", failOnEvent = true)
        val reporter = ToolLifecycleReporter(host, "stream-boom")

        val result = withToolLifecycle(reporter, "any.tool") { "survived" }

        assertEquals("观察侧异常绝不能改变工具结果", "survived", result)
        assertTrue("订阅者异常时不应记录到事件", received.isEmpty())
    }

    @Test
    fun `无发布端时工具执行照常（装配期尚未接线）`() {
        val reporter = ToolLifecycleReporter(null, "stream-none")

        val result = withToolLifecycle(reporter, "any.tool") { "ok-without-publisher" }

        assertEquals("ok-without-publisher", result)
    }

    // ── 7. Cordis 卸载语义：卸载后不再投递 ──

    @Test
    fun `卸载订阅插件后本回合事件不再投递（卸载不留鸡毛）`() {
        val host = newHost()
        val received = mutableListOf<Any>()
        loadRecordingPlugin(host, received)
        val reporter = ToolLifecycleReporter(host, "stream-unload")

        withToolLifecycle(reporter, "before.unload") { "first" }
        assertEquals(2, received.size)

        assertTrue(host.unload("test.recorder"))
        withToolLifecycle(reporter, "after.unload") { "second" }

        assertEquals("卸载后不得再收到事件", 2, received.size)
    }

    // ── 8. 隐私契约：载荷结构被钉死，不可能夹带参数或结果 ──

    @Test
    fun `事件载荷结构被钉死，不含参数与结果字段`() {
        assertEquals(
            "生命周期事件新增字段属于隐私契约变更，需同步更新本断言与 KDoc",
            setOf("streamId", "callId", "toolName", "startedAtMs"),
            instanceFieldNames(ToolStarted::class.java),
        )
        assertEquals(
            "生命周期事件新增字段属于隐私契约变更，需同步更新本断言与 KDoc",
            setOf(
                "streamId", "callId", "toolName", "status",
                "startedAtMs", "finishedAtMs", "elapsedMs",
            ),
            instanceFieldNames(ToolFinished::class.java),
        )

        // 端到端再证一次：把「敏感」文本当作工具自身的输入/输出流经执行路径，
        // 事件文本里不得出现它们。
        val host = newHost()
        val received = mutableListOf<Any>()
        loadRecordingPlugin(host, received)
        val reporter = ToolLifecycleReporter(host, "stream-privacy")
        val secretArgs = """{"token":"SECRET_ARGS_MARKER"}"""
        val secretResult = "SECRET_RESULT_MARKER"

        val result = withToolLifecycle(reporter, "privacy.tool") {
            assertEquals(secretArgs, """{"token":"SECRET_ARGS_MARKER"}""")
            secretResult
        }

        assertEquals(secretResult, result)
        val payloads = received.joinToString(" | ") { it.toString() }
        assertTrue("事件不得泄漏工具参数", !payloads.contains("SECRET_ARGS_MARKER"))
        assertTrue("事件不得泄漏工具结果", !payloads.contains("SECRET_RESULT_MARKER"))
    }

    // ── 9. 回合隔离：streamId 就是关联键 ──

    @Test
    fun `不同回合（streamId）的事件可被订阅方区分`() {
        val host = newHost()
        val received = mutableListOf<Any>()
        loadRecordingPlugin(host, received)

        withToolLifecycle(ToolLifecycleReporter(host, "turn-a"), "tool.a") { "a" }
        withToolLifecycle(ToolLifecycleReporter(host, "turn-b"), "tool.b") { "b" }

        val started = received.filterIsInstance<ToolStarted>()
        assertEquals(2, started.size)
        assertEquals(listOf("turn-a", "turn-b"), started.map { it.streamId })
        assertNotNull(started.firstOrNull { it.toolName == "tool.a" })
    }

    // ── 10. 时钟可注入：elapsed 由单一来源计算，便于确定性验证 ──

    @Test
    fun `elapsedMs 取自注入时钟`() {
        val host = newHost()
        val received = mutableListOf<Any>()
        loadRecordingPlugin(host, received)
        var now = 1_000L
        val reporter = ToolLifecycleReporter(host, "stream-clock") { now }

        withToolLifecycle(reporter, "clock.tool") {
            now = 1_250L
            "ok"
        }

        val finished = received[1] as ToolFinished
        assertEquals(250L, finished.elapsedMs)
        assertEquals(1_000L, finished.startedAtMs)
        assertEquals(1_250L, finished.finishedAtMs)
    }
}
