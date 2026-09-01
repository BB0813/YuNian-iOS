package com.lianyu.ai.domain

/**
 * 占位符提供者接口。
 *
 * 负责将模板变量（如 {{char}}、{{user}}、{{cur_date}} 等）解析为实际值。
 * 实现类在 core:common 中，通过 ServiceRegistry 注册，core:network 通过接口调用。
 */
interface PlaceholderProvider {
    /**
     * 解析单个占位符键。
     *
     * @param key 占位符键（不含花括号，如 "char"、"user"、"cur_date"）
     * @return 解析后的值，若不支持该键则返回 null
     */
    fun resolve(key: String): String?

    /**
     * 批量解析文本中的所有占位符。
     *
     * @param text 包含占位符的文本（支持 {{key}} 和 {key} 格式）
     * @param context 解析上下文（可选，提供额外信息如角色名、用户名等）
     * @return 解析后的文本
     */
    fun resolve(text: String, context: PlaceholderContext? = null): String
}

/**
 * 占位符解析上下文。
 *
 * 为解析器提供运行时信息，避免在实现类中硬编码依赖。
 */
data class PlaceholderContext(
    /** 角色/伴侣名称（用于 {{char}}） */
    val charName: String? = null,
    /** 用户昵称（用于 {{user}}） */
    val userName: String? = null,
    /** 模型 ID（用于 {{model_id}}） */
    val modelId: String? = null,
    /** 模型名称（用于 {{model_name}}） */
    val modelName: String? = null,
    /** 语言环境（用于 {{locale}}） */
    val locale: String? = null,
    /** 时区（用于 {{timezone}}） */
    val timezone: String? = null,
    /** 当前时间戳（毫秒，用于 {{cur_date}}、{{cur_time}}） */
    val currentTimeMillis: Long = System.currentTimeMillis(),
    /** 电池电量 0-100（用于 {{battery_level}}） */
    val batteryLevel: Int? = null,
    /** 自定义扩展字段 */
    val extras: Map<String, String> = emptyMap()
)