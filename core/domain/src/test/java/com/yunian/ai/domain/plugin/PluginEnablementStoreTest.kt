package com.yunian.ai.domain.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginEnablementStore] 契约的编译期 + fail-safe 语义测试。
 *
 * 该接口在 `core:domain` **没有实现**（实现方在 `:app`），所以这里只做两件事：
 * 1. 用测试替身**钉住签名**（一旦签名漂移，本文件编译失败）；
 * 2. 钉住 fail-safe 语义：读取失败 = 空集合 = 全部启用。
 *
 * 纯 JVM 单测：不触碰 `android.util.Log` / `org.json`。
 */
class PluginEnablementStoreTest {

    /** 内存替身：记录写入，并可按需模拟「读取失败」。 */
    private class FakeStore(private val failRead: Boolean = false) : PluginEnablementStore {
        val written = mutableListOf<Pair<String, Boolean>>()
        private val disabled = linkedSetOf<String>()

        override suspend fun disabledIds(): Set<String> {
            if (failRead) return emptySet() // fail-safe：读不到就是「全部启用」
            return disabled.toSet()
        }

        override suspend fun setEnabled(pluginId: String, enabled: Boolean) {
            written += pluginId to enabled
            if (enabled) disabled.remove(pluginId) else disabled.add(pluginId)
        }
    }

    @Test
    fun `fresh store reports nothing disabled`() = runBlocking {
        val store: PluginEnablementStore = FakeStore()
        assertEquals(emptySet<String>(), store.disabledIds())
    }

    @Test
    fun `setEnabled false then true round-trips`() = runBlocking {
        val store: PluginEnablementStore = FakeStore()

        store.setEnabled("qqbot", false)
        assertEquals(setOf("qqbot"), store.disabledIds())

        store.setEnabled("qqbot", true)
        assertEquals(emptySet<String>(), store.disabledIds())
    }

    @Test
    fun `reading failure yields empty set so every plugin stays enabled`() = runBlocking {
        val store: PluginEnablementStore = FakeStore(failRead = true)
        val ids = store.disabledIds()

        assertTrue("fail-safe 必须是空集合，绝不能是「全停用」", ids.isEmpty())
    }

    @Test
    fun `setEnabled is idempotent`() = runBlocking {
        val store: PluginEnablementStore = FakeStore()
        repeat(3) { store.setEnabled("wechat", false) }

        assertEquals(setOf("wechat"), store.disabledIds())
    }

    @Test
    fun `disabled ids are a snapshot, not a live view`() = runBlocking {
        val store: PluginEnablementStore = FakeStore()
        store.setEnabled("qqbot", false)
        val snapshot = store.disabledIds()

        store.setEnabled("wechat", false)

        assertEquals(setOf("qqbot"), snapshot)
        assertEquals(setOf("qqbot", "wechat"), store.disabledIds())
    }
}
