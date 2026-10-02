package com.yunian.ai.agent.plugin

/**
 * 插件框架日志出口（对应 Cordis 宿主的宿主日志面）。
 *
 * 存在理由：本包的生产代码需要打日志，而 `android.util.Log` 在纯 JVM 单测里是抛
 * `RuntimeException("Stub!")` 的空壳（本仓库无 Robolectric，`:core:agent` 也未开启
 * `unitTests.isReturnDefaultValues`）。因此把日志收敛到一个可替换的接口，
 * 宿主与装配上下文通过构造参数接收它：
 * - 生产：默认值 [AndroidPluginLog]（逐字保留原 `android.util.Log` 的 tag 与文案）；
 * - 单测：注入 [NoOpPluginLog]，纯 JVM 即可驱动真实的 PluginHostImpl / PluginContextImpl。
 *
 * 可见性说明：本接口是 :core:agent 的实现细节，但**必须 public**——[PluginHostImpl]
 * 的构造函数（由 :app 模块跨模块构造）带本类型的默认参数，Kotlin 禁止 public 函数
 * 暴露 internal 参数类型。它不是插件契约的一部分（契约在 core:domain），插件实现方
 * 不需要、也不应该实现它。
 *
 * 行为约定：实现**不得**抛出异常（日志失败不得影响插件装载/卸载语义）。
 */
interface PluginLog {
    /** 信息级日志（对齐 `android.util.Log.i`）。 */
    fun i(tag: String, message: String)

    /** 告警级日志（对齐 `android.util.Log.w`，可带 throwable）。 */
    fun w(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * 生产实现：直接转发到 `android.util.Log`（tag/文案与迁移前逐字一致）。
 *
 * 可见性说明：必须 public——[PluginHostImpl] 与
 * [com.yunian.ai.agent.channel.ChannelRegistryImpl] 的构造函数把它当默认值，
 * Kotlin 禁止 public 函数暴露 internal 类型（默认值表达式同样受限）。
 */
class AndroidPluginLog : PluginLog {
    override fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) android.util.Log.w(tag, message)
        else android.util.Log.w(tag, message, throwable)
    }
}

/** 单测替身：静默丢弃全部日志（纯 JVM 无 Android 运行时）。 */
internal object NoOpPluginLog : PluginLog {
    override fun i(tag: String, message: String) = Unit

    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
}
