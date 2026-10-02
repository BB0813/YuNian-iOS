package com.yunian.ai.domain.plugin

/**
 * 插件启停状态的**持久化契约**：让「用户在设置页停用某个插件」跨重启保留。
 *
 * ## 为什么需要它
 *
 * 现状：插件蓝图（`app/src/main/assets/blueprints/default.json` → [PluginBlueprint]）是
 * **只读资产**——全仓没有任何写回蓝图的代码。因此停用状态没有落点：进程一重启，
 * 宿主重新按蓝图装载，被停用的插件又活了。
 *
 * 本接口是该状态的存储落点。**只定义契约，不提供实现**（`core:domain` 只有接口与数据类）：
 * 实现方在 `:app`，与蓝图的读路径合并后得到最终装载集合。
 *
 * ## fail-safe：读不到 = 全部启用
 *
 * [disabledIds] **读取失败时必须返回空集合**——空集合的含义是「没有任何插件被停用」，
 * 即**全部启用**，与引入本契约之前的现状完全一致。
 *
 * 这是硬要求：存储损坏 / 首次启动 / IO 异常 / 反序列化失败时，实现方**不得**抛异常，
 * 也**不得**返回「全部 id」（那会让用户一觉醒来所有插件全灭，且无法在设置页里自救——
 * 设置区本身可能就来自插件）。宁可多启用一个插件，不可让整个插件生态静默失效。
 *
 * 同理，[setEnabled] 的写失败应由实现方**自行记录并吞掉**（最多让本次设置不生效），
 * 不得把异常抛给设置页的调用方。
 *
 * ## 契约细节
 *
 * - 语义是**减法**：只持久化「被显式停用」的 id 集合；不在集合里的 id 一律视为启用。
 *   这与蓝图的 `enabled` 字段（默认 true）方向一致，也让新增插件天然默认启用。
 * - 实现方负责线程安全与持久化介质选择（DataStore / Room / 文件均可，契约层不限定）。
 */
interface PluginEnablementStore {

    /**
     * 当前被**显式停用**的插件 id 集合。
     *
     * @return 停用的 id 集合；**读取失败时返回空集合**（= 全部启用，与现状一致），
     *   绝不抛异常、绝不返回「全停用」。返回值应视为不可变快照。
     */
    suspend fun disabledIds(): Set<String>

    /**
     * 设置单个插件的启停状态并持久化。
     *
     * @param pluginId 插件 id，与 [LianYuPlugin.id] / [PluginManifest.id] 同一取值域。
     * @param enabled true = 启用（从停用集合中移除该 id）；false = 停用（加入停用集合）。
     *
     * 幂等：重复设置同一状态不产生额外副作用。写失败由实现方自行处理（见接口 KDoc
     * 的 fail-safe 说明），不向调用方抛异常。
     */
    suspend fun setEnabled(pluginId: String, enabled: Boolean)
}
