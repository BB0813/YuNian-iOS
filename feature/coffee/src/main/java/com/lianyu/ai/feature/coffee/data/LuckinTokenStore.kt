package com.lianyu.ai.feature.coffee.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.coffeeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "luckin_coffee_prefs"
)

/**
 * 瑞幸 MCP Token 存储。
 *
 * 用户在 App 内输入从 https://open.lkcoffee.com/mcp 登录获取的 Token，
 * 持久化到 DataStore。Token 有效期约 30 天，过期后用户需重新获取。
 *
 * 安全说明：
 * - Token 与瑞幸账号会话绑定，严禁泄露
 * - 存储在应用私有目录，卸载后自动清除
 */
class LuckinTokenStore(private val context: Context) {

    private val tokenKey = stringPreferencesKey("luckin_mcp_token")
    private val tokenSaveTimeKey = stringPreferencesKey("luckin_mcp_token_save_time")

    /** 获取已存储的 Token，空字符串表示未配置 */
    val token: Flow<String> = context.coffeeDataStore.data.map { it[tokenKey] ?: "" }

    /** Token 保存时间戳（毫秒），用于判断是否过期 */
    private val tokenSaveTime: Flow<Long> = context.coffeeDataStore.data.map {
        it[tokenSaveTimeKey]?.toLongOrNull() ?: 0L
    }

    /**
     * 保存 Token。
     * @param token 用户从瑞幸开放平台获取的完整 Bearer Token
     */
    suspend fun saveToken(token: String) {
        context.coffeeDataStore.edit { prefs ->
            prefs[tokenKey] = token.trim()
            prefs[tokenSaveTimeKey] = System.currentTimeMillis().toString()
        }
    }

    /** 清除 Token（用户撤销授权时调用） */
    suspend fun clearToken() {
        context.coffeeDataStore.edit { it.remove(tokenKey); it.remove(tokenSaveTimeKey) }
    }

    /**
     * 检查 Token 是否可能已过期（超过 29 天）。
     * 这是客户端预判，实际过期以服务端返回 401 为准。
     */
    suspend fun isTokenLikelyExpired(): Boolean {
        var saveTime = 0L
        tokenSaveTime.collect { saveTime = it; return@collect }
        if (saveTime == 0L) return true
        val elapsed = System.currentTimeMillis() - saveTime
        return elapsed > 29L * 24 * 60 * 60 * 1000
    }
}
