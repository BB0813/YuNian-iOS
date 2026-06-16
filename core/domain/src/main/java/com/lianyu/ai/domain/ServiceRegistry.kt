package com.lianyu.ai.domain

/**
 * Lightweight manual DI registry for cross-feature communication.
 * Eliminates feature→feature project() dependencies.
 * 
 * Usage in LianYuApplication.onCreate():
 *   ServiceRegistry.register(LocalModelProvider::class.java) { LocalAiService.getInstance() }
 * 
 * Usage in feature modules:
 *   val provider = ServiceRegistry.get(LocalModelProvider::class.java)
 */
object ServiceRegistry {
    private val factories = mutableMapOf<Class<*>, () -> Any?>()

    fun <T : Any> register(type: Class<T>, factory: () -> T?) {
        factories[type] = factory as () -> Any?
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(type: Class<T>): T? {
        return factories[type]?.invoke() as? T
    }

    fun clear() {
        factories.clear()
    }
}
