package com.yunian.ai.uicommon.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * 插件设置区的**全局注册表**（宿主级单例）。
 *
 * 设置页只认这一张表：插件把自己的 [PluginSettingsSection] 挂进来，设置页读出来渲染，
 * 双方不需要互相依赖（插件不必依赖设置模块，设置模块也不必知道有哪些插件）。
 *
 * ## 生命周期语义
 *
 * 注册由插件的 `setup()` 发起，注销由该插件在 `PluginContext.effect` 里登记的
 * 副作用撤销：
 *
 * ```kotlin
 * override fun setup(ctx: PluginContext) {
 *     PluginSettingsSections.register(MySettingsSection)
 *     ctx.effect({ PluginSettingsSections.unregister(id) }, "settings-section")
 * }
 * ```
 *
 * 因此**插件停用 → 设置区自动消失**，设置页无需维护「哪些插件还活着」的镜像状态。
 * 注册表不判断插件是否真的处于装载态（那是宿主 `PluginHost` 的职责）：表里有条目，
 * 就说明该插件已完成装配。
 *
 * ## 线程安全
 *
 * 内部用 [ConcurrentHashMap]（与 `com.yunian.ai.domain.ToolRegistry` 的写法一致），
 * 注册 / 注销 / 查询可在任意线程并发调用：插件装配可能发生在工作线程，
 * 而设置页的读取发生在主线程（组合期）。
 */
object PluginSettingsSections {

    /** pluginId → 设置区；键唯一即保证「同一插件至多一个设置区」。 */
    private val sections = ConcurrentHashMap<String, PluginSettingsSection>()

    /**
     * 注册设置区（按 [PluginSettingsSection.pluginId] **幂等覆盖**）。
     *
     * 同一 id 重复注册（插件重载、配置变更后重新装配）不会产生重复条目，
     * 后注册的实例**整体替换**先前的实例；键不变，因此 [all] 的顺序也不变。
     */
    fun register(section: PluginSettingsSection) {
        sections[section.pluginId] = section
    }

    /**
     * 注销设置区（按 pluginId）。
     *
     * 幂等：该 id 未注册时静默返回。插件的 `ctx.effect` 会在卸载/装配回滚时调用它，
     * 重复调用不应抛异常。
     */
    fun unregister(pluginId: String) {
        sections.remove(pluginId)
    }

    /**
     * 全部设置区的**稳定快照**：按 [PluginSettingsSection.pluginId] 升序排列。
     *
     * 顺序必须稳定——直接返回 [ConcurrentHashMap] 的遍历顺序时，条目增删会让顺序抖动，
     * Compose 列表项随之重排/重组（表现为设置项「跳位」）。排序键是 pluginId 字符串，
     * 与注册先后无关，因此任何一次调用拿到的都是同一个顺序。
     *
     * 返回的是新构造的 [List]，调用方持有它不会影响注册表后续变更。
     */
    fun all(): List<PluginSettingsSection> =
        sections.values.sortedBy { it.pluginId }

    /** 按 pluginId 查单个设置区；未注册返回 null。 */
    fun forPlugin(pluginId: String): PluginSettingsSection? = sections[pluginId]

    /**
     * 清空注册表。
     *
     * 仅供**测试隔离**与宿主级整体重置使用；插件的正常撤销路径是 [unregister]。
     */
    fun clear() {
        sections.clear()
    }
}
