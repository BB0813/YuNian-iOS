package com.yunian.ai.feature.skills.testing

import com.yunian.ai.domain.plugin.PluginContext

/**
 * [PluginContext] 的 JVM 假实现：只实现 provide / inject / effect / on 四个抽象成员，
 * 其余 Cordis 事件派发成员沿用接口默认实现（调用即抛 UnsupportedOperationException）。
 *
 * [effect] 按注册顺序记录 (label, disposer)——单测据此断言
 * 「9 个工具各有一条 unregister effect」以及「逆序执行后注册表干净」。
 */
class FakePluginContext(services: Map<String, Any> = emptyMap()) : PluginContext {

    private val services: MutableMap<String, Any> = LinkedHashMap(services)

    /** 按注册顺序记录的副作用 (label, disposer)。 */
    val effects: MutableList<Pair<String, () -> Unit>> = mutableListOf()

    /** 已订阅事件名（按订阅顺序）。 */
    val subscribedEvents: MutableList<String> = mutableListOf()

    override fun <T : Any> provide(key: String, service: T) {
        services[key] = service
    }

    override fun <T : Any> inject(key: String): T {
        val service = services[key]
            ?: throw IllegalStateException("服务未 provide：$key")
        @Suppress("UNCHECKED_CAST")
        return service as T
    }

    override fun effect(disposer: () -> Unit, label: String) {
        effects += label to disposer
    }

    override fun on(event: String, handler: (Any) -> Unit) {
        subscribedEvents += event
    }

    /** 按注册顺序执行全部副作用（宿主装配失败时的回滚路径）。 */
    fun disposeInOrder() {
        effects.forEach { (_, disposer) -> disposer() }
    }

    /** 按注册**逆序**执行全部副作用（对齐宿主 unload 语义）。 */
    fun disposeReverse() {
        effects.asReversed().forEach { (_, disposer) -> disposer() }
    }
}
