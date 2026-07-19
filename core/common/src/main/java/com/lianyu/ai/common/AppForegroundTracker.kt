package com.lianyu.ai.common

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级前后台状态。
 *
 * 优先由 [ProcessLifecycleOwner] 驱动；若尚未 [init]，仍允许
 * Activity 侧写入以兼容启动早期窗口。
 */
object AppForegroundTracker {

    @Volatile
    var isInForeground: Boolean = false

    private val initialized = AtomicBoolean(false)

    /**
     * 在 Application.onCreate 中调用一次，绑定进程生命周期。
     * 幂等：重复调用无效。
     */
    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        // ProcessLifecycleOwner 回调在主线程；启动时可能已在 STARTED
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        isInForeground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isInForeground = true
            }

            override fun onStop(owner: LifecycleOwner) {
                isInForeground = false
            }
        })
    }
}
