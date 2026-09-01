package com.lianyu.ai.network.transformers

import com.lianyu.ai.domain.LorebookProvider
import com.lianyu.ai.domain.LorebookEntry
import com.lianyu.ai.domain.TriggeredEntry
import com.lianyu.ai.domain.PlaceholderProvider
import com.lianyu.ai.network.Message

/**
 * 消息转换器上下文。
 *
 * 封装转换管道所需的运行时信息，避免在转换器中散落依赖。
 */
data class TransformerContext(
    /** 当前伴侣 ID（单聊）或群组 ID（群聊，负值区分） */
    val sessionId: Long,
    /** 是否为群聊 */
    val isGroupChat: Boolean = false,
    /** 当前使用的模型 ID */
    val modelId: String? = null,
    /** 当前使用的模型名称 */
    val modelName: String? = null,
    /** 伴侣/人设名称（用于 {{char}} 占位符） */
    val characterName: String? = null,
    /** 用户昵称（用于 {{user}} 占位符） */
    val userNickname: String? = null,
    /** 占位符提供者 */
    val placeholderProvider: PlaceholderProvider? = null,
    /** 世界书提供者 */
    val lorebookProvider: LorebookProvider? = null,
    /** 当前时间戳（毫秒） */
    val currentTimeMillis: Long = System.currentTimeMillis(),
    /** 可选：工作目录（未来 workspace 功能用） */
    val workspaceCwd: String? = null,
    /** 可选：处理状态回调（用于 typing、进度等） */
    val processingStatus: ((String) -> Unit)? = null,
)

/**
 * 消息转换器接口。
 *
 * 统一处理输入消息（发送给 AI 前）和输出消息（AI 返回后）的转换逻辑。
 * 所有转换器按注册顺序组成管道，前一个的输出作为后一个的输入。
 */
interface MessageTransformer {
    /** 转换器唯一标识（用于调试/排序） */
    val id: String

    /** 是否为输入转换器（发送给 AI 前）；false 为输出转换器（AI 返回后） */
    val isInput: Boolean

    /**
     * 转换消息列表。
     *
     * @param context 转换上下文
     * @param messages 待转换消息（输入转换器为组装后的上下文，输出转换器为 AI 返回的单条消息）
     * @return 转换后的消息列表
     */
    suspend fun transform(
        context: TransformerContext,
        messages: List<Message>
    ): List<Message>

    /** 转换器优先级（越大越先执行，输入转换器通常优先级高） */
    val priority: Int
}

/**
 * 转换器管道扩展函数。
 */
suspend fun List<MessageTransformer>.runPipeline(
    context: TransformerContext,
    messages: List<Message>,
    isInput: Boolean
): List<Message> {
    var current = messages
    for (transformer in this.filter { it.isInput == isInput }.sortedByDescending { it.priority }) {
        current = transformer.transform(context, current)
    }
    return current
}