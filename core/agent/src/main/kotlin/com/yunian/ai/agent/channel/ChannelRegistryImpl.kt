package com.yunian.ai.agent.channel

import com.yunian.ai.agent.plugin.AndroidPluginLog
import com.yunian.ai.agent.plugin.PluginLog
import com.yunian.ai.domain.channel.ChannelAdapter
import com.yunian.ai.domain.channel.MutableChannelRegistry
import com.yunian.ai.domain.plugin.PluginHost
import com.yunian.ai.domain.plugin.PluginKind
import java.util.concurrent.ConcurrentHashMap

/**
 * [MutableChannelRegistry] 实现：把「通道即插件」的装载契约真正接通。
 *
 * ## 内容来源（唯一来源）
 *
 * 注册中心**不持有任何旁路全局表**：适配器由通道插件在自己的 [com.yunian.ai.domain.plugin.LianYuPlugin.setup]
 * 里经 [register] 挂上来的，而 [register] **每一步都回查宿主**：
 *
 * 1. [PluginHost.pluginsOf] `(PluginKind.ADAPTER)` —— 插件必须在宿主的 ADAPTER 分派视图里；
 * 2. [PluginHost.plugin] `(pluginId)` —— 该 id 确实是一个 kind = ADAPTER 的插件。
 *
 * 任何一步不成立即拒绝注册（fail-closed）并抛 [IllegalStateException]。
 * 「绕过插件宿主直接把适配器塞进注册中心」这条路走不通——那正是本任务要防的情形。
 *
 * ## 可见性（为什么 register 里不查 isLoaded）
 *
 * [PluginHost.isLoaded] 在 [PluginHost.load] 里是**setup 成功之后**才置位的
 * （`PluginHostImpl.load`：`plugin.setup(ctx)` → `loadedIds.add(id)`）。因此装配期的
 * 注册请求里 isLoaded 必然为 false——若在此处强校验装载态，通道插件永远无法装载。
 *
 * 正确的不变量由**查询侧**保证（见 [loadedAdapterPluginIds]）：注册中心对外只暴露
 * 「此刻在 [PluginHost.pluginsOf] `(ADAPTER)` 视图里**且**处于装载态」的适配器。
 * 于是未装载 / 装载失败的插件即使调用过 [register]，也绝不会出现在 [adapters] / [adapter] 里。
 *
 * ## 可逆性
 *
 * [unregister] 与 [register] 成对，由插件的 [com.yunian.ai.domain.plugin.PluginContext.effect]
 * 在卸载时逆序调用（Cordis「卸载不留鸡毛」）。因此
 * [PluginHost.unload] `("channel.qqbot")` 之后 [adapter] `("qqbot")` 必然返回 null。
 *
 * ## 查询期的活性兜底
 *
 * 即便某个插件忘了注册撤销副作用，[adapters] / [adapter] 仍会在查询时回查
 * [PluginHost.pluginsOf] `(PluginKind.ADAPTER)` 并剔除「已不在装载态」的条目——
 * 注册中心的输出永远是「此刻真正已装载的 ADAPTER 插件」的函数。
 *
 * 线程安全：内部 [ConcurrentHashMap]，register/unregister/查询均可在任意线程调用。
 *
 * ## 构造顺序（宿主 ↔ 注册中心互相需要）
 *
 * 宿主需要把注册中心作为框架服务预置（插件才能 `ctx.inject(CHANNELS)`），
 * 而注册中心需要宿主来判定「谁已装载」。因此本类**先构造、后绑定**：
 * 先 `ChannelRegistryImpl()`，再用同一个实例建宿主，最后 [bindHost]。
 * 未绑定即使用会抛 [IllegalStateException]（fail-closed，不静默返回空）。
 *
 * @param log 日志出口（生产为 [AndroidPluginLog]，单测注入 [NoOpPluginLog]）。
 */
class ChannelRegistryImpl(
    private val log: PluginLog = AndroidPluginLog(),
) : MutableChannelRegistry {

    /** channelKey → (适配器, 归属插件 id)。 */
    private val entries = ConcurrentHashMap<String, Entry>()

    private data class Entry(val adapter: ChannelAdapter, val pluginId: String)

    @Volatile
    private var host: PluginHost? = null

    /** 绑定插件宿主（**唯一**的适配器来源判定者）；构造后必须调用一次。 */
    fun bindHost(host: PluginHost) {
        this.host = host
    }

    private fun requireHost(): PluginHost = host
        ?: throw IllegalStateException(
            "ChannelRegistryImpl 尚未绑定 PluginHost：请先构造注册中心、再建宿主、最后 bindHost"
        )

    override fun register(pluginId: String, adapter: ChannelAdapter) {
        require(pluginId.isNotBlank()) { "注册通道适配器必须给出归属插件 id" }
        val key = adapter.channelKey
        require(key.isNotBlank()) { "通道适配器 channelKey 不得为空（插件 $pluginId）" }

        // ① 必须在宿主的 ADAPTER 分派视图里（pluginsOf(ADAPTER) 的真实消费点之一）
        val adapters = requireHost().pluginsOf(PluginKind.ADAPTER)
        check(adapters.any { it.id == pluginId }) {
            "拒绝注册通道适配器：插件 $pluginId 不在宿主 pluginsOf(ADAPTER) 分派视图里"
        }
        // ② 该 id 确实是一个 ADAPTER 插件（防 id 撞到别的类别）
        val plugin = requireHost().plugin(pluginId)
        check(plugin != null && plugin.kind == PluginKind.ADAPTER) {
            "拒绝注册通道适配器：$pluginId 不是 kind=ADAPTER 的插件"
        }

        val existing = entries[key]
        check(existing == null || existing.pluginId == pluginId) {
            "通道标识冲突：$key 已被插件 ${existing?.pluginId} 占用，插件 $pluginId 不得重复注册"
        }
        entries[key] = Entry(adapter, pluginId)
        log.i(TAG, "channel adapter registered: $key by $pluginId")
    }

    override fun unregister(pluginId: String, channelKey: String): Boolean {
        val existing = entries[channelKey] ?: return false
        if (existing.pluginId != pluginId) {
            log.w(TAG, "拒绝摘除通道适配器：$channelKey 属于 ${existing.pluginId}，而非 $pluginId")
            return false
        }
        val removed = entries.remove(channelKey, existing)
        if (removed) log.i(TAG, "channel adapter unregistered: $channelKey by $pluginId")
        return removed
    }

    override fun adapters(): List<ChannelAdapter> {
        val loadedAdapterIds = loadedAdapterPluginIds()
        return entries.values
            .filter { it.pluginId in loadedAdapterIds }
            .map { it.adapter }
            .sortedBy { it.channelKey }
    }

    override fun adapter(channelKey: String): ChannelAdapter? {
        val entry = entries[channelKey] ?: return null
        return if (entry.pluginId in loadedAdapterPluginIds()) entry.adapter else null
    }

    /**
     * 当前处于**装载态**的 ADAPTER 插件 id 集合。
     *
     * 这是 [PluginHost.pluginsOf] `(PluginKind.ADAPTER)` 在 core:agent 的**生产**消费点：
     * 注册中心的每一次查询都要经过它，因此「通道插件被卸载」这件事无需额外通知就会
     * 立刻反映到 [adapters] / [adapter] 上——这正是「卸载不留鸡毛」的兜底（即便某个
     * 插件忘了登记撤销副作用）。
     */
    private fun loadedAdapterPluginIds(): Set<String> =
        requireHost().pluginsOf(PluginKind.ADAPTER)
            .filter { requireHost().isLoaded(it.id) }
            .map { it.id }
            .toSet()

    private companion object {
        const val TAG = "ChannelRegistryImpl"
    }
}
