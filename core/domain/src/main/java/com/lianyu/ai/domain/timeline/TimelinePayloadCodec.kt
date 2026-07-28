package com.lianyu.ai.domain.timeline

import java.util.concurrent.ConcurrentHashMap

/**
 * 载荷编解码契约。
 *
 * domain 不依赖 JSON 库；实现可放 core:database 或 core:common。
 * 与 [com.lianyu.ai.domain.ToolRegistry] 相同：注册表模式，零 feature 依赖。
 */
interface TimelinePayloadCodec {
    val kind: TimelineEventKind
    fun serialize(payload: TimelinePayload): String
    fun deserialize(raw: String): TimelinePayload
}

/**
 * 纯文本载荷的默认编解码（REASONING / ASSISTANT_TEXT）。
 * 复杂 TOOL/SYSTEM 由后续切片注册专用 codec。
 */
class PlainTextReasoningCodec : TimelinePayloadCodec {
    override val kind: TimelineEventKind = TimelineEventKind.REASONING

    override fun serialize(payload: TimelinePayload): String {
        val p = payload as? ReasoningPayload
            ?: error("PlainTextReasoningCodec expects ReasoningPayload")
        // 正文存 text；duration 由 Message 元数据列承载，不塞进 content
        return p.text
    }

    override fun deserialize(raw: String): TimelinePayload =
        ReasoningPayload(text = raw, durationMs = null)
}

class PlainTextAssistantCodec : TimelinePayloadCodec {
    override val kind: TimelineEventKind = TimelineEventKind.ASSISTANT_TEXT

    override fun serialize(payload: TimelinePayload): String {
        val p = payload as? AssistantTextPayload
            ?: error("PlainTextAssistantCodec expects AssistantTextPayload")
        return p.text
    }

    override fun deserialize(raw: String): TimelinePayload =
        AssistantTextPayload(text = raw, segmentIndex = 0)
}

/**
 * Codec 注册中心（线程安全）。
 * app 启动时注册内置 codec；Agent 扩展注册自己的 kind。
 */
object TimelinePayloadCodecRegistry {
    private val codecs = ConcurrentHashMap<TimelineEventKind, TimelinePayloadCodec>()

    fun register(codec: TimelinePayloadCodec) {
        codecs[codec.kind] = codec
    }

    fun unregister(kind: TimelineEventKind) {
        codecs.remove(kind)
    }

    fun get(kind: TimelineEventKind): TimelinePayloadCodec? = codecs[kind]

    fun require(kind: TimelineEventKind): TimelinePayloadCodec =
        codecs[kind] ?: error("No TimelinePayloadCodec registered for $kind")

    fun serialize(payload: TimelinePayload): String =
        require(payload.kind).serialize(payload)

    fun deserialize(kind: TimelineEventKind, raw: String): TimelinePayload =
        require(kind).deserialize(raw)

    /** 注册 REASONING / ASSISTANT_TEXT 默认纯文本 codec（可重复调用，幂等覆盖） */
    fun registerBuiltins() {
        register(PlainTextReasoningCodec())
        register(PlainTextAssistantCodec())
    }

    fun clear() {
        codecs.clear()
    }
}
