package com.lianyu.ai.database.model

/**
 * 记忆作用域：决定记忆在哪些场景下可见。
 *
 * - GLOBAL:    跨所有会话共享的用户级记忆（偏好、事实、习惯）
 * - COMPANION: 按 companionId 隔离的角色互动记忆
 * - GROUP:     按 groupId 隔离的群聊记忆
 * - PRIVATE:   仅系统内部使用、不注入到任何对话的记忆
 */
enum class MemoryScope {
    GLOBAL,
    COMPANION,
    GROUP,
    PRIVATE
}
