package com.yunian.ai.feature.settings.plugin

import com.yunian.ai.domain.plugin.PluginEnablementStore
import com.yunian.ai.domain.plugin.PluginLoadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PluginToggleControllerTest {
    private val id = "test.plugin"

    private class Store(var disabled: Set<String>) : PluginEnablementStore {
        var swallowWrite = false
        var writes = 0
        var reads = 0
        var onWrite: suspend () -> Unit = {}
        var onRead: suspend () -> Unit = {}
        override suspend fun setEnabled(pluginId: String, enabled: Boolean) {
            writes++
            onWrite()
            if (!swallowWrite) disabled = if (enabled) disabled - pluginId else disabled + pluginId
        }
        override suspend fun disabledIds(): Set<String> {
            reads++
            onRead()
            return disabled
        }
    }

    private class Runtime {
        var loaded = false
        var loads = 0
        var unloads = 0
        var result: PluginLoadResult = PluginLoadResult.Loaded
        var refuseUnload = false
        var error: Exception? = null
        fun controller(store: PluginEnablementStore?) = PluginToggleController(
            load = {
                loads++
                error?.let { throw it }
                if (result == PluginLoadResult.Loaded) loaded = true
                result
            },
            unload = {
                unloads++
                error?.let { throw it }
                val wasLoaded = loaded
                if (!refuseUnload) loaded = false
                wasLoaded && !refuseUnload
            },
            isLoaded = { loaded },
            store = store,
        )
    }

    @Test fun failedAndMissingLoadsNeverSave() = runBlocking {
        for (result in listOf(PluginLoadResult.Failed("missing service"), PluginLoadResult.NotFound)) {
            val runtime = Runtime().apply { this.result = result }
            val store = Store(setOf(id))
            val outcome = runtime.controller(store).toggle(id, true)
            assertTrue(outcome is PluginToggleResult.Failed)
            assertFalse(runtime.loaded)
            assertEquals(0, store.writes)
            assertEquals(0, store.reads)
            assertEquals(setOf(id), store.disabled)
        }
    }

    @Test fun savesOnlyAfterRuntimeChangeAndReadsBack() = runBlocking {
        val runtime = Runtime()
        val store = Store(setOf(id))
        store.onWrite = { assertTrue(runtime.loaded) }
        val controller = runtime.controller(store)
        assertEquals(PluginToggleResult.Saved, controller.toggle(id, true))
        assertEquals(emptySet<String>(), store.disabled)
        store.onWrite = { assertFalse(runtime.loaded) }
        assertEquals(PluginToggleResult.Saved, controller.toggle(id, false))
        assertEquals(setOf(id), store.disabled)
        assertEquals(2, store.reads)
    }

    @Test fun swallowedEnableWriteWarnsWithoutStoppingRunningPlugin() = runBlocking {
        val runtime = Runtime()
        val store = Store(setOf(id)).apply { swallowWrite = true }
        assertEquals(PluginToggleResult.NotSaved, runtime.controller(store).toggle(id, true))
        assertTrue(runtime.loaded)
        assertEquals(setOf(id), store.disabled)
    }

    @Test fun swallowedDisableWriteWarnsWithoutRestartingPlugin() = runBlocking {
        val runtime = Runtime().apply { loaded = true }
        val store = Store(emptySet()).apply { swallowWrite = true }
        assertEquals(PluginToggleResult.NotSaved, runtime.controller(store).toggle(id, false))
        assertFalse(runtime.loaded)
        assertEquals(emptySet<String>(), store.disabled)
    }

    @Test fun absentStoreWarnsForBothDirections() = runBlocking {
        val runtime = Runtime()
        val controller = runtime.controller(null)
        assertEquals(PluginToggleResult.NotSaved, controller.toggle(id, true))
        assertTrue(runtime.loaded)
        assertEquals(PluginToggleResult.NotSaved, controller.toggle(id, false))
        assertFalse(runtime.loaded)
    }

    @Test fun failedUnloadDoesNotPersistAndLeavesRuntimeOn() = runBlocking {
        val runtime = Runtime().apply { loaded = true; refuseUnload = true }
        val store = Store(emptySet())
        assertTrue(runtime.controller(store).toggle(id, false) is PluginToggleResult.Failed)
        assertTrue(runtime.loaded)
        assertEquals(0, store.writes)
    }

    @Test fun alreadyUnloadedCanPersistDisabledIntent() = runBlocking {
        val runtime = Runtime()
        val store = Store(emptySet())
        assertEquals(PluginToggleResult.Saved, runtime.controller(store).toggle(id, false))
        assertEquals(setOf(id), store.disabled)
    }

    @Test fun lyingLoadedResultDoesNotSave() = runBlocking {
        val store = Store(setOf(id))
        val controller = PluginToggleController({ PluginLoadResult.Loaded }, { false }, { false }, store)
        assertTrue(controller.toggle(id, true) is PluginToggleResult.Failed)
        assertEquals(0, store.writes)
    }

    @Test fun runtimeExceptionsAreVisibleAndReleaseGate() = runBlocking {
        for (enabled in listOf(true, false)) {
            val runtime = Runtime().apply { loaded = !enabled; error = IllegalStateException("host failure") }
            val store = Store(emptySet())
            val controller = runtime.controller(store)
            assertEquals(PluginToggleResult.Failed("host failure"), controller.toggle(id, enabled))
            assertEquals(0, store.writes)
            runtime.error = null
            assertEquals(PluginToggleResult.Saved, controller.toggle(id, enabled))
        }
    }

    @Test fun writeAndReadExceptionsWarnInsteadOfReportingSuccess() = runBlocking {
        for (duringRead in listOf(false, true)) {
            val runtime = Runtime()
            val store = Store(setOf(id))
            val fail: suspend () -> Unit = { throw IllegalStateException("storage failure") }
            if (duringRead) store.onRead = fail else store.onWrite = fail
            assertEquals(PluginToggleResult.NotSaved, runtime.controller(store).toggle(id, true))
            assertTrue(runtime.loaded)
        }
    }

    @Test fun concurrentTapIsRejectedRatherThanQueued() = runBlocking {
        val runtime = Runtime()
        val store = Store(setOf(id))
        val release = CompletableDeferred<Unit>()
        store.onWrite = { release.await() }
        val controller = runtime.controller(store)
        val first = async(start = CoroutineStart.UNDISPATCHED) { controller.toggle(id, true) }
        assertEquals(PluginToggleResult.Busy, controller.toggle(id, false))
        assertEquals(PluginToggleResult.Busy, controller.toggle("other.plugin", true))
        assertEquals(1, runtime.loads)
        assertEquals(0, runtime.unloads)
        release.complete(Unit)
        assertEquals(PluginToggleResult.Saved, first.await())
    }

    @Test fun cancellationDuringSavePropagatesAndReleasesGate() = runBlocking {
        val runtime = Runtime()
        val store = Store(setOf(id))
        store.onWrite = { CompletableDeferred<Unit>().await() }
        val controller = runtime.controller(store)
        val first = async(start = CoroutineStart.UNDISPATCHED) { controller.toggle(id, true) }
        first.cancelAndJoin()
        assertTrue(first.isCancelled)
        assertEquals(0, store.reads)
        store.onWrite = {}
        assertEquals(PluginToggleResult.Saved, controller.toggle(id, false))
    }

    @Test fun cancellationFromHostWriteAndReadIsNeverConvertedToFailure() = runBlocking {
        for (stage in listOf("load", "unload", "write", "read")) {
            val cancellation = CancellationException(stage)
            val runtime = Runtime()
            val store = Store(setOf(id))
            when (stage) {
                "load", "unload" -> runtime.error = cancellation
                "write" -> store.onWrite = { throw cancellation }
                "read" -> store.onRead = { throw cancellation }
            }
            val controller = runtime.controller(store)
            try {
                controller.toggle(id, stage != "unload")
                fail("Cancellation must propagate from $stage")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }
            runtime.error = null
            store.onWrite = {}
            store.onRead = {}
            assertEquals(PluginToggleResult.Saved, controller.toggle(id, false))
        }
    }
}
