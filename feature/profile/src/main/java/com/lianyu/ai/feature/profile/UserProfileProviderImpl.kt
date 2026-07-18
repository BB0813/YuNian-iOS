package com.lianyu.ai.feature.profile

import android.content.Context
import com.lianyu.ai.database.repository.UserRepository
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.domain.UserProfileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Adapter that bridges domain UserProfileProvider to the shared UserRepository singleton.
 * Registered in LianYuApplication via ServiceRegistry.
 *
 * 必须复用 ServiceRegistry 中的 UserRepository，避免各自 new 一份导致资料更新无法跨页面同步。
 */
class UserProfileProviderImpl(
    context: Context,
    repository: UserRepository? = null
) : UserProfileProvider {

    private val repository: UserRepository = repository
        ?: ServiceRegistry.get(UserRepository::class.java)
        ?: UserRepository(context.applicationContext)

    // 用于订阅 StateFlow 的内部作用域，生命周期与 Application 一致
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
}
