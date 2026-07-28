package com.lianyu.ai.domain.timeline

/**
 * 回合与事件身份（纯值类型，零依赖）。
 *
 * 生成策略由调用方注入（UUID / 雪花等），domain 不绑定具体实现，避免耦合。
 */
@JvmInline
value class TurnId(val value: String) {
    init {
        require(value.isNotBlank()) { "TurnId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * 持久化后的事件 id（通常映射 messages.id）。
 * 流式未落库前可为 null，不使用本类型。
 */
@JvmInline
value class EventId(val value: Long) {
    init {
        require(value > 0L) { "EventId must be positive" }
    }

    override fun toString(): String = value.toString()
}
