package com.yunian.ai.feature.settings.plugin

import com.yunian.ai.domain.plugin.PluginEnablementStore
import com.yunian.ai.domain.plugin.PluginLoadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** Pure JVM transaction; caller chooses the dispatcher. Runtime is never rolled back for a failed save. */
internal class PluginToggleController(
    private val load: (String) -> PluginLoadResult,
    private val unload: (String) -> Boolean,
    private val isLoaded: (String) -> Boolean,
    private val store: PluginEnablementStore?,
) {
    // Reject rather than queue stale taps, including toggles of different plugins sharing the KV set.
    private val mutex = Mutex()

    suspend fun toggle(id: String, enabled: Boolean): PluginToggleResult {
        currentCoroutineContext().ensureActive()
        if (!mutex.tryLock()) return PluginToggleResult.Busy
        try {
            try {
                if (enabled) {
                    when (val result = load(id)) {
                        PluginLoadResult.Loaded -> Unit
                        PluginLoadResult.NotFound -> return PluginToggleResult.Failed("插件未注册：$id")
                        is PluginLoadResult.Failed -> return PluginToggleResult.Failed(result.reason)
                    }
                } else {
                    unload(id)
                }
                // Host APIs are synchronous: cancellation can arrive while setup/dispose is executing.
                currentCoroutineContext().ensureActive()
                if (isLoaded(id) != enabled) {
                    return PluginToggleResult.Failed(if (enabled) "插件未装载" else "插件仍在运行，未能停用")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return PluginToggleResult.Failed(failure.message ?: "插件操作失败")
            }

            if (store == null) return PluginToggleResult.NotSaved
            return try {
                store.setEnabled(id, enabled)
                currentCoroutineContext().ensureActive()
                // Unit return does NOT imply a successful KV write: the store swallows write failures.
                val disabled = store.disabledIds()
                currentCoroutineContext().ensureActive()
                if ((id !in disabled) == enabled) PluginToggleResult.Saved else PluginToggleResult.NotSaved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PluginToggleResult.NotSaved
            }
        } finally {
            mutex.unlock()
        }
    }
}

internal sealed interface PluginToggleResult {
    data object Saved : PluginToggleResult
    data object NotSaved : PluginToggleResult
    data object Busy : PluginToggleResult
    data class Failed(val reason: String) : PluginToggleResult
}
