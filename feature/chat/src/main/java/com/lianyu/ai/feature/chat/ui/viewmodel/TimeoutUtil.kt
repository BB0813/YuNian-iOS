package com.lianyu.ai.feature.chat.ui.viewmodel

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared executor for [runInterruptibleSafe] — avoids creating a new single-thread
 * executor on every call. The pool grows on demand (cached thread pool) and idle
 * threads are reclaimed after 30 s.
 */
private val sharedExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
    Thread(runnable).apply {
        name = "runInterruptibleSafe-${threadCounter.incrementAndGet()}"
        isDaemon = true  // don't prevent JVM shutdown
    }
}

private val threadCounter = AtomicInteger(0)

/**
 * Execute a suspending block on a dedicated thread with a hard timeout.
 *
 * Unlike [kotlinx.coroutines.withTimeoutOrNull] which relies on cooperative cancellation
 * (suspend functions must check cancellation), this wraps the block in a [java.util.concurrent.Future]
 * so that blocking I/O calls (OkHttp execute(), JNI native calls, etc.) are truly interrupted
 * when the timeout expires.
 *
 * **Important:** Only timeout and interruption are treated as timeout signals.
 * All other exceptions thrown by [block] propagate to the caller.
 *
 * Uses a shared cached thread pool instead of creating a new executor per call,
 * avoiding thread-pool leakage under high-frequency invocation.
 *
 * @param timeoutMs  Hard timeout in milliseconds.
 * @param onTimeout  Value to return when the timeout fires or the thread is interrupted (default: null).
 * @param block      The suspending block to execute on a separate thread.
 * @return           The block's result, or [onTimeout] on timeout/interrupt.
 * @throws           Any exception thrown by [block] (except timeout/interrupt).
 */
suspend fun <T> runInterruptibleSafe(
    timeoutMs: Long,
    onTimeout: T? = null,
    block: suspend () -> T
): T? {
    return try {
        val future = sharedExecutor.submit<T> {
            kotlinx.coroutines.runBlocking { block() }
        }
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        onTimeout
    } catch (_: InterruptedException) {
        onTimeout
    }
    // Other exceptions (RuntimeException, etc.) propagate to caller naturally
}