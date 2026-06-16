package com.lianyu.ai.domain

/**
 * AI 对话服务提供者接口。
 * 由 core:network 实现，通过 ServiceRegistry 注入到 feature 模块。
 *
 * 注意：此接口属于 core:domain 零依赖模块，
 * 使用领域层数据类而非 core:database 实体，避免模块间耦合。
 */

/** AI 对话响应 */
data class AiResponse(
    val content: String,
    val reasoningContent: String? = null
)

/** 伴侣角色摘要信息，供 AI 对话使用 */
data class AiCompanionInfo(
    val id: Long,
    val name: String,
    val personality: String,
    val age: Int? = null,
    val backstory: String? = null,
    val speakingStyle: String? = null,
    val systemPrompt: String? = null
)

/** 消息类型 */
enum class AiMessageType {
    TEXT, IMAGE
}

/** 聊天消息摘要，供 AI 对话使用 */
data class AiChatMessage(
    val isFromUser: Boolean,
    val content: String,
    val timestamp: Long,
    val type: AiMessageType = AiMessageType.TEXT,
    val companionId: Long = 0
)

interface AiServiceProvider {
    /**
     * 发送文本消息并获取 AI 响应。
     *
     * @param companion 伴侣角色信息
     * @param history 聊天历史消息
     * @param stickerProbability 表情包发送概率 (0-100)
     * @return AI 响应
     */
    suspend fun sendMessage(
        companion: AiCompanionInfo,
        history: List<AiChatMessage>,
        stickerProbability: Int = 0
    ): AiResponse

    /**
     * 发送图片消息并调用视觉 AI 模型进行识别。
     *
     * @param companion 伴侣角色信息
     * @param history 聊天历史消息
     * @param imagePath 本地图片文件路径
     * @param stickerProbability 表情包发送概率 (0-100)
     * @return AI 响应
     */
    suspend fun sendMessageWithImage(
        companion: AiCompanionInfo,
        history: List<AiChatMessage>,
        imagePath: String,
        stickerProbability: Int = 0
    ): AiResponse

    /**
     * 判断是否需要发送主动消息。
     *
     * @param companion 伴侣角色信息
     * @param recentMessages 最近聊天消息
     * @return 是否应发送主动消息
     */
    fun shouldProactivelyMessage(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>
    ): Boolean

    /**
     * 生成主动消息内容。
     *
     * @param companion 伴侣角色信息
     * @param recentMessages 最近聊天消息
     * @return 主动消息内容，null 表示不发送
     */
    suspend fun generateProactiveMessage(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>
    ): String?

    /**
     * 使用自定义系统提示词发送消息（群聊场景）。
     *
     * @param companion 伴侣角色信息
     * @param history 聊天历史消息
     * @param customSystemPrompt 自定义系统提示词
     * @param stickerProbability 表情包发送概率 (0-100)
     * @param companionNameMap 角色 ID→名称 映射
     * @return AI 响应文本
     */
    suspend fun sendMessageWithCustomSystem(
        companion: AiCompanionInfo,
        history: List<AiChatMessage>,
        customSystemPrompt: String,
        stickerProbability: Int = 30,
        companionNameMap: Map<Long, String> = emptyMap()
    ): String

    /**
     * 生成追问问题。AI回复后按概率触发，让对话继续下去。
     *
     * @param companion 伴侣角色信息
     * @param recentMessages 最近聊天消息
     * @param lastAiContent AI最后一条回复内容
     * @return 追问内容，null 表示不生成
     */
    suspend fun generateFollowUpQuestion(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>,
        lastAiContent: String
    ): String?
}
