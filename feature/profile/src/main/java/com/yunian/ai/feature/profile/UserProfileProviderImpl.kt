package com.yunian.ai.feature.profile

import android.content.Context
import com.yunian.ai.database.repository.UserRepository
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.UserProfileProvider
import com.yunian.ai.domain.UserProfileSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UserProfileProviderImpl(
    context: Context,
    repository: UserRepository? = null
) : UserProfileProvider {

    private val repository: UserRepository = repository
        ?: ServiceRegistry.get(UserRepository::class.java)
        ?: UserRepository(context.applicationContext)

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun getUserId(): String = "default_user"

    override fun getNickname(): String = repository.userName.value

    override fun getAvatar(): String? = repository.userAvatar.value

    override fun observeAvatar(onChange: (String?) -> Unit): () -> Unit {
        val job = observerScope.launch {
            repository.userAvatar.collect { avatar ->
                onChange(avatar)
            }
        }
        return { job.cancel() }
    }

    override fun observeNickname(onChange: (String) -> Unit): () -> Unit {
        val job = observerScope.launch {
            repository.userName.collect { name ->
                onChange(name)
            }
        }
        return { job.cancel() }
    }

    override fun isLoggedIn(): Boolean = true

    /**
     * 读取用户自述资料的最新快照（只读、无网络、无新 IO）。
     *
     * 数据源是 [UserRepository] 的 SharedPreferences 派生 StateFlow，与
     * [getNickname] / [getAvatar] 完全同源，每次调用都取 `.value` 最新值，不做任何缓存。
     *
     * 安全边界：
     * - 不含 user_id / logged_in —— :23 的 `"default_user"` 与 :47 的 `true` 只是占位常量，
     *   不是认证结果，一律不进入快照。
     * - 头像只暴露「是否设置过」的布尔值，不暴露 URI（SharedPreferences 里存的是本机文件路径）。
     * - 空白串归一化为 null，表示「用户未填写」，避免下游把空串当真实内容。
     */
    override fun snapshot(): UserProfileSnapshot = UserProfileSnapshot(
        userName = repository.userName.value.trimOrNull(),
        status = repository.userStatus.value.trimOrNull(),
        signature = repository.userSignature.value.trimOrNull(),
        gender = repository.userGender.value.trimOrNull(),
        region = repository.userRegion.value.trimOrNull(),
        hasAvatar = !repository.userAvatar.value.isNullOrBlank(),
    )

    /** 空白（含纯空格）归一化为 null：语义为「未填写」。 */
    private fun String.trimOrNull(): String? = trim().ifEmpty { null }
}
