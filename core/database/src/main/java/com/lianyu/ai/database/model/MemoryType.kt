package com.lianyu.ai.database.model

/**
 * 统一记忆类型。
 *
 * 对应 [2026-07-09 现代化记忆系统设计方案] 的 7 种记忆模式：
 * - WORKING:    短期工作记忆（当前对话上下文，几分钟内有效）
 * - EPISODIC:   情景记忆（"昨天用户和角色聊了搬家"）
 * - SEMANTIC:   语义记忆（"用户是软件工程师"，稳定事实）
 * - PREFERENCE: 偏好记忆（"用户喜欢安静的环境"）
 * - RELATIONSHIP: 关系记忆（"用户把角色当亲密伙伴"）
 * - PROCEDURAL: 程序化记忆（"用户抱怨时先安抚再给建议"）
 * - FUZZY:      模糊记忆（低置信度、近似匹配的候选记忆）
 */
enum class MemoryType {
    WORKING,
    EPISODIC,
    SEMANTIC,
    PREFERENCE,
    RELATIONSHIP,
    PROCEDURAL,
    FUZZY
}
