package com.lianyu.ai.database.model

/**
 * 记忆来源。
 *
 * - CHAT:       单聊对话中提取
 * - GROUP_CHAT: 群聊对话中提取
 * - MANUAL:     用户手动添加
 * - SYSTEM:     系统自动推断/融合生成
 */
enum class MemorySource {
    CHAT,
    GROUP_CHAT,
    MANUAL,
    SYSTEM
}
