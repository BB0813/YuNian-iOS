package com.yunian.ai.feature.chat.ui.viewmodel

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

private val sharedExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
    Thread(runnable).apply {
        name = "runInterruptibleSafe-${threadCounter.incrementAndGet()}"
        isDaemon = true
    }
}

private val threadCounter = AtomicInteger(0)

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
    } catch (e: ExecutionException) {
        // future.get 会把业务异常包一层 ExecutionException。若不拆包，
        // 上游拿到的 message 会变成 "java.lang.Exception: xxx"，
        // 导致 ChatGenerationManager 的 removePrefix("[TOAST]") 失效、并把内部类名暴露给用户。
        throw e.cause ?: e
    }
}
