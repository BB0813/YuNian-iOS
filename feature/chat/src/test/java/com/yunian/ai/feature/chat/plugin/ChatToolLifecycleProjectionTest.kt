package com.yunian.ai.feature.chat.plugin

import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.ToolFinishStatus
import com.yunian.ai.domain.plugin.ToolFinished
import com.yunian.ai.domain.plugin.ToolLifecycleEvents
import com.yunian.ai.domain.plugin.ToolStarted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天侧工具生命周期订阅的行为验收。
 *
 * 这一层只做投影：把 Cordis 标准事件（tool.started / tool.finished）折叠成卡片项，
 * 不接触 ToolHost、不持有静态全局总线。因此这里用最小 [PluginContext] 替身即可覆盖
 * 「订阅的是标准事件名」与「投影只认本回合 streamId」两条真实契约，
 * 无需（也不应）依赖 core:agent 的宿主内部实现。
 */
class ChatToolLifecycleProjectionTest {

    /** 只记录订阅关系的最小上下文替身；其余 Cordis 能力用契约默认实现。 */
    private class RecordingContext : PluginContext {
        val subscriptions: MutableMap<String, (Any) -> Unit> = LinkedHashMap()

        override fun <T : Any> provide(key: String, service: T) = Unit

        override fun <T : Any> inject(key: String): T =
            throw IllegalStateException("测试替身未提供服务: $key")

        override fun effect(disposer: () -> Unit, label: String) = Unit

        override fun on(event: String, handler: (Any) -> Unit) {
            subscriptions[event] = handler
        }
    }

    /** 只记录投影输出，便于断言回调次数与内容。 */
    private class Recorder {
        val emissions: MutableList<List<ChatToolLifecycleItem>> = mutableListOf()
        fun onChanged(items: List<ChatToolLifecycleItem>) { emissions += items }
    }


    /** 只取实例属性：Compose 编译器会注入静态 `$stable`，它不是业务字段。 */
    private fun instanceFieldNames(type: Class<*>): Set<String> = type.declaredFields
        .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.isSynthetic }
        .map { it.name }
        .toSet()

    private fun started(streamId: String, callId: String, toolName: String, at: Long = 1_000L) =
        ToolStarted(streamId = streamId, callId = callId, toolName = toolName, startedAtMs = at)

    private fun finished(
        streamId: String,
        callId: String,
        toolName: String,
        status: ToolFinishStatus,
        at: Long = 1_500L,
    ) = ToolFinished(
        streamId = streamId,
        callId = callId,
        toolName = toolName,
        status = status,
        startedAtMs = 1_000L,
        finishedAtMs = at,
        elapsedMs = at - 1_000L,
    )

    // ── 1. 订阅的是标准事件名（与 core 侧发布端同源） ──

    @Test
    fun `插件订阅标准 tool_started 与 tool_finished 事件名`() {
        val ctx = RecordingContext()
        val plugin: LianYuPlugin = ChatToolLifecyclePlugin("s1", object : ChatToolLifecycleSubscriber {
            override fun onStarted(event: ToolStarted) = Unit
            override fun onFinished(event: ToolFinished) = Unit
        })

        plugin.setup(ctx)

        assertEquals(
            setOf(ToolLifecycleEvents.STARTED.name, ToolLifecycleEvents.FINISHED.name),
            ctx.subscriptions.keys.toSet(),
        )
        assertEquals("tool.started", ToolLifecycleEvents.STARTED.name)
        assertEquals("tool.finished", ToolLifecycleEvents.FINISHED.name)
    }

    @Test
    fun `插件 id 带固定前缀且类别为 PIPELINE（不侵入 TOOL 分派）`() {
        val plugin = ChatToolLifecyclePlugin("turn-42", object : ChatToolLifecycleSubscriber {
            override fun onStarted(event: ToolStarted) = Unit
            override fun onFinished(event: ToolFinished) = Unit
        })

        assertEquals(PluginKind.PIPELINE, plugin.kind)
        assertTrue(
            "插件 id 必须以固定前缀开头，卸载时才能按实例精确回收",
            plugin.id.startsWith(ChatToolLifecyclePlugin.ID_PREFIX),
        )
        assertTrue("每个回合必须是独立实例 id", plugin.id.contains("turn-42"))
    }

    @Test
    fun `事件经插件订阅通道抵达投影`() {
        val ctx = RecordingContext()
        val recorder = Recorder()
        val projection = ChatToolLifecycleProjection("turn-x", recorder::onChanged)
        ChatToolLifecyclePlugin("turn-x", projection).setup(ctx)

        ctx.subscriptions.getValue("tool.started").invoke(started("turn-x", "1", "channel.send_message"))
        ctx.subscriptions.getValue("tool.finished")
            .invoke(finished("turn-x", "1", "channel.send_message", ToolFinishStatus.SUCCEEDED))

        assertEquals(2, recorder.emissions.size)
        assertEquals(ChatToolLifecycleStatus.RUNNING, recorder.emissions[0].single().status)
        assertEquals(ChatToolLifecycleStatus.DONE, recorder.emissions[1].single().status)
    }

    // ── 2. 回合隔离：其他 streamId 的事件必须被忽略 ──

    @Test
    fun `只处理本回合 streamId，其他回合事件不进入卡片`() {
        val recorder = Recorder()
        val projection = ChatToolLifecycleProjection("mine", recorder::onChanged)

        projection.onStarted(started("other", "9", "leaked.tool"))
        projection.onFinished(finished("other", "9", "leaked.tool", ToolFinishStatus.FAILED))

        assertTrue("串台的回合事件绝不能进入本回合卡片", recorder.emissions.isEmpty())
        assertTrue(projection.snapshot().isEmpty())
    }

    // ── 3. RUNNING → 终态同 id 覆写，工具名以 started 为准 ──

    @Test
    fun `同 callId 的终态覆写 RUNNING 且保留 started 的工具名`() {
        val recorder = Recorder()
        val projection = ChatToolLifecycleProjection("s", recorder::onChanged)

        projection.onStarted(started("s", "7", "group.send", at = 2_000L))
        projection.onFinished(finished("s", "7", "group.send", ToolFinishStatus.SUCCEEDED, at = 3_000L))

        val item = projection.snapshot().single()
        assertEquals("7", item.id)
        assertEquals("group.send", item.toolName)
        assertEquals(ChatToolLifecycleStatus.DONE, item.status)
        assertEquals("起始时间必须沿用 started，避免卡片时间跳变", 2_000L, item.startedAtMs)
    }

    @Test
    fun `失败与非成功终态都映射为 FAILED`() {
        val projection = ChatToolLifecycleProjection("s") { }
        listOf(
            ToolFinishStatus.FAILED,
            ToolFinishStatus.CANCELLED,
            ToolFinishStatus.TIMED_OUT,
        ).forEachIndexed { index, status ->
            val callId = "c$index"
            projection.onStarted(started("s", callId, "risky.tool"))
            projection.onFinished(finished("s", callId, "risky.tool", status))
        }

        assertEquals(3, projection.snapshot().size)
        assertTrue(
            "非 SUCCEEDED 一律不得显示为完成",
            projection.snapshot().all { it.status == ChatToolLifecycleStatus.FAILED },
        )
    }

    @Test
    fun `先 finished 后 started 的乱序到达不会丢卡片`() {
        val projection = ChatToolLifecycleProjection("s") { }

        projection.onFinished(finished("s", "5", "late.tool", ToolFinishStatus.FAILED))
        val afterFinish = projection.snapshot().single()
        assertEquals(ChatToolLifecycleStatus.FAILED, afterFinish.status)

        projection.onStarted(started("s", "5", "late.tool"))
        val afterStart = projection.snapshot().single()
        assertEquals("乱序 started 必须把状态推进为 RUNNING 并保留工具名", "late.tool", afterStart.toolName)
    }

    // ── 4. 多次调用保序（卡片按发生顺序展示） ──

    @Test
    fun `多个工具按发生顺序保序，且每次变更都通知订阅方`() {
        val recorder = Recorder()
        val projection = ChatToolLifecycleProjection("s", recorder::onChanged)

        projection.onStarted(started("s", "1", "first.tool", at = 100L))
        projection.onStarted(started("s", "2", "second.tool", at = 200L))
        projection.onStarted(started("s", "3", "third.tool", at = 300L))

        assertEquals(listOf("first.tool", "second.tool", "third.tool"), projection.snapshot().map { it.toolName })
        assertEquals("每次状态变更都应通知（RUNNING 也要实时可见）", 3, recorder.emissions.size)
        assertEquals(3, recorder.emissions.last().size)
        assertTrue(recorder.emissions.last().all { it.status == ChatToolLifecycleStatus.RUNNING })
    }

    // ── 5. 隐私契约：卡片模型不可能携带参数或结果 ──

    @Test
    fun `卡片模型不含参数与结果字段（事件本来就不带）`() {
        assertEquals(
            "卡片新增字段属于隐私契约变更，需同步更新本断言与 KDoc",
            setOf("id", "toolName", "status", "startedAtMs"),
            instanceFieldNames(ChatToolLifecycleItem::class.java),
        )
        assertNotNull(ChatToolLifecycleStatus.valueOf("RUNNING"))
    }
}
