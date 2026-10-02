package com.yunian.ai.domain.plugin

/**
 * 强类型 Cordis 事件键。
 *
 * 底层仍使用 [name] 接入既有字符串事件总线；类型参数只在编译期约束载荷，
 * 不创建第二张监听表，也不改变 Cordis 的事件命名。
 */
data class EventKey<T : Any>(val name: String) {
    init {
        require(name.isNotBlank()) { "event name must not be blank" }
    }
}

/** 只暴露发布能力的 Cordis 宿主端口；插件订阅仍必须经 [PluginContext] 以获得卸载清理。 */
interface PluginEventPublisher {
    fun <T : Any> emit(key: EventKey<T>, payload: T)
}

/** 用强类型键订阅；实际订阅仍登记为 [PluginContext.effect]，卸载时自动退订。 */
fun <T : Any> PluginContext.on(key: EventKey<T>, handler: (T) -> Unit) {
    on(key.name) { payload ->
        @Suppress("UNCHECKED_CAST")
        handler(payload as T)
    }
}

/** 用强类型键同步广播。 */
fun <T : Any> PluginContext.emit(key: EventKey<T>, payload: T) {
    emit(key.name, payload)
}
