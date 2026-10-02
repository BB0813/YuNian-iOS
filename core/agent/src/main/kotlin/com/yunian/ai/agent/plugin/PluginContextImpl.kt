package com.yunian.ai.agent.plugin

import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginEventListener
import com.yunian.ai.domain.plugin.PluginEventNext
import com.yunian.ai.domain.plugin.PluginEventResult
import com.yunian.ai.domain.plugin.PluginWaterfallListener

/**
 * [PluginContext] 实现：服务注入 + 可逆副作用（LIFO 逆序 dispose）+ 事件订阅与派发。
 *
 * 生命周期约束：
 * - [effect] 注册的副作用在 [disposeAll] 时**逆序**执行（Cordis「卸载不留鸡毛」语义），
 *   保证先注册的先清理依赖（如先注册工具再注册可用性钩子 → 先摘钩子再注销工具）；
 * - [on] / [onBail] / [onWaterfall] 自动把退订注册为 effect，卸载即退订；
 * - 装配抛异常时由宿主调用 [disposeAll] 回滚已注册的全部副作用。
 *
 * ## 事件总线是宿主级共享的（P3-3a）
 *
 * 订阅表**不在本类里**，而在构造时注入的 [PluginEventBus] 上。宿主（[PluginHostImpl]）
 * 为全部插件共用同一个总线实例，因此插件 A 的 [emit] 能被插件 B 的 [on] 收到。
 * 退订仍然走 effect：卸载 → [disposeAll] → 逆序执行 `unsubscribe:*` → 从共享总线上摘除。
 *
 * ## 并发
 *
 * 本类的 [services] 是**每个插件实例私有**的 [MutableMap]（装载时由宿主复制框架服务快照，
 * 装载/卸载在宿主的 `synchronized(this)` 内串行），因此不额外加锁；
 * 事件侧的线程安全由 [PluginEventBus] 的并发容器负责（见其 KDoc）。
 *
 * @param services 宿主框架服务快照 + 本插件运行期 provide 的服务；
 * @param bus 宿主级共享事件总线（同一宿主的全部插件共用）；
 * @param log 日志出口（生产为 [AndroidPluginLog]，单测注入 [NoOpPluginLog]）；
 *   除日志出口外，装配/回滚语义与迁移前逐字一致。
 */
internal class PluginContextImpl(
    private val services: MutableMap<String, Any>,
    private val bus: PluginEventBus,
    private val log: PluginLog = AndroidPluginLog(),
) : PluginContext {

    private data class EffectEntry(val disposer: () -> Unit, val label: String)

    private val effects = mutableListOf<EffectEntry>()

    override fun <T : Any> provide(key: String, service: T) {
        services[key] = service
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> inject(key: String): T {
        return services[key] as? T
            ?: throw IllegalStateException("插件依赖服务未提供: $key（请检查插件 requires 声明与宿主预置服务）")
    }

    override fun effect(disposer: () -> Unit, label: String) {
        effects.add(EffectEntry(disposer, label))
    }

    // ── 订阅（订阅即 effect：卸载/回滚时自动退订） ──

    override fun on(event: String, handler: (Any) -> Unit) {
        val unsubscribe = bus.subscribe(event, handler)
        effect(unsubscribe, "unsubscribe:$event")
    }

    override fun onBail(event: String, handler: PluginEventListener) {
        val unsubscribe = bus.subscribeBail(event, handler)
        effect(unsubscribe, "unsubscribe:$event")
    }

    override fun onWaterfall(event: String, handler: PluginWaterfallListener) {
        val unsubscribe = bus.subscribeWaterfall(event, handler)
        effect(unsubscribe, "unsubscribe:$event")
    }

    // ── 派发（转发到宿主级共享总线） ──

    override fun emit(event: String, payload: Any) = bus.emit(event, payload)

    override suspend fun parallel(event: String, payload: Any): PluginEventResult =
        bus.parallel(event, payload)

    override suspend fun serial(event: String, payload: Any): PluginEventResult =
        bus.serial(event, payload)

    override fun bail(event: String, payload: Any): PluginEventResult = bus.bail(event, payload)

    override fun waterfall(
        event: String,
        payload: Any,
        inner: () -> PluginEventResult,
    ): PluginEventResult = bus.waterfall(event, payload, inner)

    /** 逆序执行全部副作用并清空（卸载 / 装配失败回滚）。 */
    fun disposeAll() {
        effects.asReversed().forEach { entry ->
            runCatching { entry.disposer() }.onFailure {
                log.w("PluginContextImpl", "dispose 失败 [${entry.label}]: ${it.message}")
            }
        }
        effects.clear()
        // 上下文已释放：从总线的兜底清理集合里摘掉（避免总线长期持有已卸载上下文）。
        bus.releaseContext(this)
    }
}
