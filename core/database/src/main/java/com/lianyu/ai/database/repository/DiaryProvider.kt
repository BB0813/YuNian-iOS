package com.lianyu.ai.database.repository

import com.lianyu.ai.database.model.CompanionEntity

/**
 * 日记生成提供者接口。
 *
 * 定义在 core:database 中，避免 core:database → core:network 的依赖违规。
 * 实现类（DiaryService）位于 core:network，通过 app 模块的 ServiceRegistry 注入。
 *
 * 用途：根据每次会话总结，由 AI 生成第一人称情感日记。
 */
interface DiaryProvider {

    /**
    * 根据会话总结生成第一人称情感日记。
     *
     * @param companion      角色信息（名字、人设等）
    * @param conversationText 已格式化的会话总结或对话文本
     * @param memoryContext    当前已有的记忆上下文
     * @return 生成的日记内容，失败时返回 null
     */
    suspend fun generateDiary(
        companion: CompanionEntity,
        conversationText: String,
        memoryContext: String = ""
    ): String?
}
