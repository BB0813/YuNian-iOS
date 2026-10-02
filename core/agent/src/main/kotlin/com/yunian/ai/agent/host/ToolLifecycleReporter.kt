package com.yunian.ai.agent.host

import com.yunian.ai.domain.plugin.EventKey
import com.yunian.ai.domain.plugin.PluginEventPublisher
import com.yunian.ai.domain.plugin.ToolFinishStatus
import com.yunian.ai.domain.plugin.ToolFinished
import com.yunian.ai.domain.plugin.ToolLifecycleEvents
import com.yunian.ai.domain.plugin.ToolStarted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Android/FFI 无关的工具生命周期守卫，便于用真实 Cordis 总线验证所有终态。 */
internal class ToolLifecycleReporter(
    private val publisher: PluginEventPublisher?,
    private val streamId: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun started(toolName: String): Call {
        val call = Call(
            publisher = publisher,
            streamId = streamId,
            callId = NEXT_CALL_ID.incrementAndGet().toString(),
            toolName = toolName,
            startedAtMs = nowMs(),
            nowMs = nowMs,
        )
        call.publishStarted()
        return call
    }

    class Call internal constructor(
        private val publisher: PluginEventPublisher?,
        private val streamId: String,
        private val callId: String,
        private val toolName: String,
        private val startedAtMs: Long,
        private val nowMs: () -> Long,
    ) {
        private val finished = AtomicBoolean(false)

        internal fun publishStarted() {
            publish(ToolLifecycleEvents.STARTED, ToolStarted(streamId, callId, toolName, startedAtMs))
        }

        fun finish(status: ToolFinishStatus) {
            if (!finished.compareAndSet(false, true)) return
            val finishedAt = nowMs()
            publish(
                ToolLifecycleEvents.FINISHED,
                ToolFinished(
                    streamId = streamId,
                    callId = callId,
                    toolName = toolName,
                    status = status,
                    startedAtMs = startedAtMs,
                    finishedAtMs = finishedAt,
                    elapsedMs = (finishedAt - startedAtMs).coerceAtLeast(0L),
                ),
            )
        }

        private fun <T : Any> publish(key: EventKey<T>, payload: T) {
            // 观察侧故障/无订阅者均为 no-op，绝不能改变工具结果或异常。
            runCatching { publisher?.emit(key, payload) }
        }
    }

    companion object {
        private val NEXT_CALL_ID = AtomicLong(0L)
    }
}

/** started 与 finished 的结构化 finally；调用方可把被转换成结果的失败显式标记。 */
internal inline fun <T> withToolLifecycle(
    reporter: ToolLifecycleReporter,
    toolName: String,
    block: ((ToolFinishStatus) -> Unit) -> T,
): T {
    val call = reporter.started(toolName)
    var status = ToolFinishStatus.SUCCEEDED
    return try {
        block { status = it }
    } catch (timeout: TimeoutCancellationException) {
        status = ToolFinishStatus.TIMED_OUT
        throw timeout
    } catch (cancelled: CancellationException) {
        status = ToolFinishStatus.CANCELLED
        throw cancelled
    } catch (failure: Throwable) {
        status = ToolFinishStatus.FAILED
        throw failure
    } finally {
        call.finish(status)
    }
}