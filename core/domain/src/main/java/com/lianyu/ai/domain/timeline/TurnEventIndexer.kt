package com.lianyu.ai.domain.timeline

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Turn 内 eventIndex 分配器（纯内存原子）。
 *
 * 与存储解耦：落库使用分配到的 index；进程重启后历史行已有 index，无需本类。
 */
class TurnEventIndexer {
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    fun next(turnId: TurnId): Int {
        val counter = counters.getOrPut(turnId.value) { AtomicInteger(0) }
        return counter.getAndIncrement()
    }

    fun peek(turnId: TurnId): Int =
        counters[turnId.value]?.get() ?: 0

    fun reset(turnId: TurnId) {
        counters.remove(turnId.value)
    }

    fun clear() {
        counters.clear()
    }
}
