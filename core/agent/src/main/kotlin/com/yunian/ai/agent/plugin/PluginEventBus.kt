package com.yunian.ai.agent.plugin

import com.yunian.ai.domain.plugin.PluginDispatchException
import com.yunian.ai.domain.plugin.PluginEventNext
import com.yunian.ai.domain.plugin.PluginEventListener
import com.yunian.ai.domain.plugin.PluginEventResult
import com.yunian.ai.domain.plugin.PluginWaterfallListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * 宿主级事件总线：**跨插件共享**的监听表 + Cordis 五种派发模式。
 *
 * ## 为什么必须是宿主级（P3-3a 的核心改动）
 *
 * P3-3a 之前，监听表挂在每个 [PluginContextImpl] 实例上（`listeners` 私有字段），
 * 而 [PluginHostImpl] 为**每个插件**新建一个上下文。于是「插件 A emit → 插件 B 的 on」
 * 物理上不可能发生：A 的表里只有 A 自己的监听器。把表提升到宿主级（一个
 * [PluginHostImpl] 一张表）之后，A 的 emit 与 B 的 on 落在同一张表上，跨插件投递成立。
 *
 * ## 并发模型（明确声明）
 *
 * - **监听表**：`ConcurrentHashMap<String, CopyOnWriteArrayList<...>>`。
 *   注册/退订是写操作（复制整个列表，代价与监听器数量成正比但与本表规模无关）；
 *   **派发是无锁快照遍历**——`CopyOnWriteArrayList` 的迭代器天然一致，不需要在派发期间
 *   持有任何锁，因此「监听器在回调里 emit 同一事件」这类重入不会死锁，监听器在派发过程中
 *   退订（或卸载插件）也不会抛 `ConcurrentModificationException`，只会影响**后续**派发。
 * - **插件上下文集合**：`ConcurrentHashMap.newKeySet()`，仅用于 [disposeContexts] 的兜底清理。
 * - **派发线程**：[emit] / [bail] / [waterfall] 在**调用方线程**上同步执行；
 *   [serial] 在调用方上下文里顺序 await；[parallel] 显式切到 [Dispatchers.Default]
 *   线程池并发执行（见 [parallel] 的 KDoc：继承调用方上下文会在单线程调度器上退化成
 *   顺序执行）。本类不保证监听器的执行顺序在 [parallel] 下可预测（那正是并发的含义）。
 * - **可见性**：监听器集合的读写都经并发容器，无额外 `synchronized`；本类自身无可变状态。
 *
 * ## 派发语义（对齐 cordis-rs 0.6.2 `src/events.rs`）
 *
 * | 方法 | 对齐 | 异常策略 |
 * |------|------|----------|
 * | [emit] | `emit_event`（`:482`） | 记日志后继续（一个监听器失败不影响其他） |
 * | [parallel] | `parallel`（`:493`） | 全部跑完，失败聚合为 [PluginDispatchException] |
 * | [serial] | `serial`（`:531`） | 直接向上抛（Rust 侧 `?` 传播） |
 * | [bail] | `bail_event`（`:572`） | 记日志后继续 |
 * | [waterfall] | `waterfall_async_event`（`:620`） | 记日志后继续（跳过该监听器，直接走 next） |
 *
 * ## 可见性
 *
 * 本类是 [PluginHostImpl] 构造参数的类型，因此必须 public（Kotlin 禁止 public 构造函数
 * 暴露 internal 类型）。但它是**实现细节**：插件不应直接持有总线——订阅必须经
 * [com.yunian.ai.domain.plugin.PluginContext.on] / `onBail` / `onWaterfall`，
 * 才能被登记成 effect 并在卸载时自动退订（Cordis「卸载不留鸡毛」）。
 *
 * @param log 日志出口（生产为 [AndroidPluginLog]，单测注入 [NoOpPluginLog]）。
 */
class PluginEventBus(
    private val log: PluginLog = AndroidPluginLog(),
) {

    /**
     * 监听器条目：调用体屏蔽三种监听器签名的差异。
     *
     * 条目**自身**即退订句柄（`CopyOnWriteArrayList.remove(entry)` 按引用相等移除），
     * 因此不需要额外的 id / 弱引用表。
     */
    private class Listener(
        val invoke: (Any) -> PluginEventResult,
        val waterfall: ((Any, PluginEventNext) -> PluginEventResult)?,
    )

    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<Listener>>()
    private val contexts = ConcurrentHashMap.newKeySet<PluginContextImpl>()

    // ── 注册 / 退订 ──

    /**
     * 订阅（仅观察，返回值被忽略）；卸载时由调用方登记的 effect 自动退订。
     *
     * @return 退订句柄，交给 `ctx.effect` 使用。
     */
    internal fun subscribe(event: String, handler: (Any) -> Unit): () -> Unit =
        add(event, invoke = { payload -> handler(payload); PluginEventResult.none })

    /**
     * 订阅可返回 bail 值的监听器。
     *
     * 返回值映射见 [PluginEventListener]：null = 不 bail；[PluginEventResult] 按其标志；
     * 其他非 null 值 = `bail(value)`。
     */
    internal fun subscribeBail(event: String, handler: PluginEventListener): () -> Unit =
        add(
            event,
            invoke = { payload ->
                when (val raw = handler.onEvent(payload)) {
                    null -> PluginEventResult.none
                    is PluginEventResult -> raw
                    else -> PluginEventResult.bail(raw)
                }
            },
        )

    /**
     * 订阅 waterfall 监听器。
     *
     * [Listener.waterfall] 才是 waterfall 派发用的调用体（带真实 next）；
     * [Listener.invoke] 是「该监听器被 emit/bail 调用」时的降级形态——此时没有下游，
     * `next` 恒为「不 bail」，因此它的返回值不会误短路其他监听器。
     */
    internal fun subscribeWaterfall(event: String, handler: PluginWaterfallListener): () -> Unit =
        add(
            event = event,
            invoke = { payload ->
                handler.onEvent(payload, PluginEventNext { PluginEventResult.none })
            },
            waterfall = { payload, next -> handler.onEvent(payload, next) },
        )

    private fun add(
        event: String,
        invoke: (Any) -> PluginEventResult,
        waterfall: ((Any, PluginEventNext) -> PluginEventResult)? = null,
    ): () -> Unit {
        val entry = Listener(invoke, waterfall)
        listeners.computeIfAbsent(event) { CopyOnWriteArrayList() }.add(entry)
        return { listeners[event]?.remove(entry) }
    }

    // ── 生命周期 ──

    /** 登记插件上下文（仅用于 [disposeContexts] 兜底清理）。 */
    internal fun trackContext(ctx: PluginContextImpl) {
        contexts.add(ctx)
    }

    /** 注销插件上下文。 */
    internal fun releaseContext(ctx: PluginContextImpl) {
        contexts.remove(ctx)
    }

    /**
     * 清空**全部**监听表（宿主整体回收时的兜底）。
     *
     * 正常路径不依赖它：每个插件的订阅都在自己的 effect 里退订（卸载即退订）。
     * 本方法只兜「插件忘了登记 effect」的情形，与
     * `ChannelRegistryImpl` 查询期回查装载态的兜底思路一致。
     */
    internal fun disposeContexts() {
        contexts.toList().forEach { it.disposeAll() }
        contexts.clear()
        listeners.clear()
    }

    // ── 派发 ──

    /** 当前事件的监听器数量（诊断 / 测试用）。 */
    internal fun listenerCount(event: String): Int = listeners[event]?.size ?: 0

    /** 同步广播：依次调用全部监听器，忽略返回值，全部调用完才返回。 */
    internal fun emit(event: String, payload: Any) {
        snapshot(event).forEach { entry -> invokeGuarded(event, entry) { it.invoke(payload) } }
    }

    /**
     * 并发派发：全部监听器**真并发**执行，失败聚合为 [PluginDispatchException]。
     *
     * 显式用 [Dispatchers.Default]（而不是继承调用方上下文）是**语义要求**，不是优化：
     * 若只写 `async` 而继承调用方上下文，在单线程调度器（Android 主线程、
     * 测试里的 `runBlocking`）上两个监听器会被排到同一个线程、顺序执行——
     * 此时 `parallel` 与 `emit` 无区别，契约里的「并发」就是空话。
     * 监听器是彼此独立的观察者，放到 Default 线程池是它们本来就该待的地方。
     */
    internal suspend fun parallel(event: String, payload: Any): PluginEventResult {
        val entries = snapshot(event)
        if (entries.isEmpty()) return PluginEventResult.none
        val failures = withContext(Dispatchers.Default) {
            supervisorScope {
                entries
                    .map { entry ->
                        async {
                            try {
                                entry.invoke(payload)
                                null
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (t: Throwable) {
                                "${t.javaClass.simpleName}: ${t.message}"
                            }
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
            }
        }
        if (failures.isNotEmpty()) throw PluginDispatchException(event, failures)
        return PluginEventResult.none
    }

    /** 顺序派发：顺序 await 每个监听器，遇 bail 值停止并返回它。 */
    internal suspend fun serial(event: String, payload: Any): PluginEventResult {
        for (entry in snapshot(event)) {
            val result = entry.invoke(payload)
            if (result.isBailed) return result
        }
        return PluginEventResult.none
    }

    /** 同步顺序派发：顺序调用，遇 bail 值停止并返回它。 */
    internal fun bail(event: String, payload: Any): PluginEventResult {
        for (entry in snapshot(event)) {
            val result = invokeGuarded(event, entry) { it.invoke(payload) } ?: continue
            if (result.isBailed) return result
        }
        return PluginEventResult.none
    }

    /**
     * waterfall：监听器按注册顺序包在 [inner] 外面（首个注册的在最外层）。
     *
     * 与 cordis-rs 一致：监听器返回 bail 值即**终止整条链**（结果直接向上传播，
     * 外层监听器若原样返回该结果，链就到此为止）；监听器不调用 `next` 即短路 inner。
     */
    internal fun waterfall(event: String, payload: Any, inner: () -> PluginEventResult): PluginEventResult {
        val entries = snapshot(event).filter { it.waterfall != null }
        var next: PluginEventNext = PluginEventNext { inner() }
        for (entry in entries.asReversed()) {
            val downstream = next
            next = PluginEventNext {
                invokeGuarded(event, entry) { it.waterfall!!.invoke(payload, downstream) }
                    ?: downstream.next()
            }
        }
        return next.next()
    }

    /** 无锁快照：CopyOnWriteArrayList 的迭代器天然一致，派发期间不持锁。 */
    private fun snapshot(event: String): List<Listener> = listeners[event]?.toList().orEmpty()

    /**
     * 调用监听器并吞掉异常（记日志）。
     *
     * 异常返回 null（调用方按「无结果」处理）；waterfall 的调用方会退化为直接走 next，
     * 因此一个坏监听器不会掐断整条链（与 cordis-rs 的 `bail_event` 一致——它只是
     * 不返回 bail 值，链继续）。
     */
    private fun invokeGuarded(
        event: String,
        entry: Listener,
        block: (Listener) -> PluginEventResult,
    ): PluginEventResult? = try {
        block(entry)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        log.w(TAG, "事件监听器失败 [$event]: ${t.javaClass.simpleName}: ${t.message}", t)
        null
    }

    companion object {
        /** 日志 tag。 */
        private const val TAG = "PluginEventBus"
    }
}
