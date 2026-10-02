package com.yunian.ai.domain

/**
 * 本机 App 用户「主动填写」的自述资料快照（只读）。
 *
 * 设计约束：
 * - 只承载用户在个人资料设置页里自己填写的字段：昵称 / 状态 / 签名 / 性别 / 地区。
 * - 空串统一归一化为 null，语义为「用户未填写」，调用方不得据 null 编造内容。
 * - **不包含** user_id / logged_in 等身份与凭据信息：这里的 [userName] 默认值
 *   （UserRepository 里为 "我"）不是认证结果，任何字段都不得当作身份凭据使用。
 * - [hasAvatar] 只是「用户是否设置过头像」的布尔事实，**不带**头像 URI 或本地路径，
 *   避免把本机文件路径泄漏到模型上下文。
 *
 * 零依赖 data class，保持 core:domain 不引入任何业务/框架依赖。
 */
data class UserProfileSnapshot(
    val userName: String? = null,
    val status: String? = null,
    val signature: String? = null,
    val gender: String? = null,
    val region: String? = null,
    val hasAvatar: Boolean = false,
)

interface UserProfileProvider {
    fun getUserId(): String
    fun getNickname(): String
    fun getAvatar(): String?

    fun observeAvatar(onChange: (String?) -> Unit): () -> Unit

    fun observeNickname(onChange: (String) -> Unit): () -> Unit

    fun isLoggedIn(): Boolean

    /**
     * 读取用户自述资料的**当前**快照（实现类每次调用都重新读取最新值，不做缓存）。
     *
     * 默认实现返回空快照，保证既有实现类无需改动即可编译（向后兼容）；
     * 有真实数据源的实现应覆盖本方法。
     */
    fun snapshot(): UserProfileSnapshot = UserProfileSnapshot()
}
