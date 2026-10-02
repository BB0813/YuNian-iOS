package com.yunian.ai.feature.qqbot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yunian.ai.feature.qqbot.data.model.QQBotAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.qqbotDataStore: DataStore<Preferences> by preferencesDataStore(name = "qqbot_prefs")

class QQBotTokenStore(context: Context) : QQProactiveTargetStore {
    private val dataStore = context.applicationContext.qqbotDataStore
    private val secureStore = QQBotSecureStore(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    private val accountState = MutableStateFlow(readAccount())

    companion object {
        private val AUTO_REPLY_KEY = booleanPreferencesKey("qqbot_auto_reply")
        private val NOTIFY_ENABLED_KEY = booleanPreferencesKey("qqbot_notify_enabled")
        private val FORWARD_ENABLED_KEY = booleanPreferencesKey("qqbot_forward_enabled")
        private val DEFAULT_COMPANION_ID_KEY = longPreferencesKey("qqbot_default_companion_id")
        private val USER_COMPANION_MAP_KEY = stringPreferencesKey("qqbot_user_companion_map")
        private val CUSTOM_BOT_NAME_KEY = stringPreferencesKey("qqbot_custom_bot_name")
        private val ACCESS_TOKEN_KEY = stringPreferencesKey("qqbot_access_token")
        private val TOKEN_EXPIRE_AT_KEY = longPreferencesKey("qqbot_token_expire_at")
        private val SESSION_ID_KEY = stringPreferencesKey("qqbot_session_id")
        private val LAST_SEQUENCE_KEY = longPreferencesKey("qqbot_last_sequence")
        private val BOT_OPENID_KEY = stringPreferencesKey("qqbot_bot_openid")

        /**
         * **宿主（用户本人）的 user_openid**——主动发送的默认目标。
         *
         * 与 [BOT_OPENID_KEY]（机器人自己的 openid，READY 事件下发）是**两个不同的标识**，
         * 绝不能混用：拿机器人 openid 当目标发消息必然失败。
         *
         * 两个来源都会写入本键：
         * 1. 扫码绑定完成时 `poll_bind_result` 返回的 `user_openid`（现成的）；
         * 2. 入站 C2C 消息的 `author.user_openid`——保证「改动前就已绑定」的用户也能补上。
         */
        private val HOST_USER_OPENID_KEY = stringPreferencesKey("qqbot_host_user_openid")

        /** 最近一次收到入站消息的 QQ 群 group_openid（主动发送 `target=group` 的可信路由）。 */
        private val RECENT_GROUP_OPENID_KEY = stringPreferencesKey("qqbot_recent_group_openid")
    }

    val accountFlow: Flow<QQBotAccount?> = accountState.asStateFlow()

    private fun readAccount(): QQBotAccount? {
        val accountJson = secureStore.getAccountJson() ?: return null
        return runCatching { json.decodeFromString<QQBotAccount>(accountJson) }
            .onFailure { secureStore.clearAccount() }
            .getOrNull()
    }

    suspend fun getAccount(): QQBotAccount? = accountState.value

    suspend fun saveAccount(account: QQBotAccount) {
        secureStore.setAccountJson(json.encodeToString(account))
        accountState.value = account
    }

    suspend fun clearAccount() {
        secureStore.clearAccount()
        accountState.value = null
        dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN_KEY)
            prefs.remove(TOKEN_EXPIRE_AT_KEY)
            prefs.remove(SESSION_ID_KEY)
            prefs.remove(LAST_SEQUENCE_KEY)
            // 换绑后宿主 openid 属于上一个宿主：必须一并清掉，
            // 否则主动发送会把消息发到旧宿主（旧 openid 对新机器人无效）。
            prefs.remove(HOST_USER_OPENID_KEY)
            prefs.remove(RECENT_GROUP_OPENID_KEY)
        }
    }

    suspend fun isLoggedIn(): Boolean = getAccount() != null

    /**
     * 是否已有账号（**同步**，读内存态 [accountState]，不碰 DataStore）。
     *
     * 供 [com.yunian.ai.domain.AiTool.isAvailable] 这类非 suspend 的可用性判定使用；
     * 语义与 [isLoggedIn] 一致（两者都看同一个 [accountState]）。
     */
    fun hasAccount(): Boolean = accountState.value != null

    val autoReplyFlow: Flow<Boolean> = dataStore.data.map { it[AUTO_REPLY_KEY] ?: true }
    suspend fun getAutoReply(): Boolean = autoReplyFlow.first()
    suspend fun setAutoReply(enabled: Boolean) = dataStore.edit { it[AUTO_REPLY_KEY] = enabled }

    val notifyEnabledFlow: Flow<Boolean> = dataStore.data.map { it[NOTIFY_ENABLED_KEY] ?: true }
    suspend fun getNotifyEnabled(): Boolean = notifyEnabledFlow.first()
    suspend fun setNotifyEnabled(enabled: Boolean) = dataStore.edit { it[NOTIFY_ENABLED_KEY] = enabled }

    val forwardEnabledFlow: Flow<Boolean> = dataStore.data.map { it[FORWARD_ENABLED_KEY] ?: true }
    suspend fun getForwardEnabled(): Boolean = forwardEnabledFlow.first()
    suspend fun setForwardEnabled(enabled: Boolean) = dataStore.edit { it[FORWARD_ENABLED_KEY] = enabled }

    val defaultCompanionIdFlow: Flow<Long?> = dataStore.data.map { it[DEFAULT_COMPANION_ID_KEY] }
    suspend fun getDefaultCompanionId(): Long? = defaultCompanionIdFlow.first()
    suspend fun setDefaultCompanionId(companionId: Long?) = dataStore.edit { prefs ->
        if (companionId != null) prefs[DEFAULT_COMPANION_ID_KEY] = companionId else prefs.remove(DEFAULT_COMPANION_ID_KEY)
    }

    val customBotNameFlow: Flow<String?> = dataStore.data.map { it[CUSTOM_BOT_NAME_KEY] }
    suspend fun getCustomBotName(): String? = customBotNameFlow.first()
    suspend fun setCustomBotName(name: String?) = dataStore.edit { prefs ->
        if (name != null) prefs[CUSTOM_BOT_NAME_KEY] = name else prefs.remove(CUSTOM_BOT_NAME_KEY)
    }

    suspend fun getAccessToken(): String? = dataStore.data.first()[ACCESS_TOKEN_KEY]
    suspend fun setAccessToken(token: String?) = dataStore.edit { prefs ->
        if (token != null) prefs[ACCESS_TOKEN_KEY] = token else prefs.remove(ACCESS_TOKEN_KEY)
    }

    suspend fun getTokenExpireAt(): Long = dataStore.data.first()[TOKEN_EXPIRE_AT_KEY] ?: 0L
    suspend fun setTokenExpireAt(timestamp: Long) = dataStore.edit { it[TOKEN_EXPIRE_AT_KEY] = timestamp }

    suspend fun getSessionId(): String? = dataStore.data.first()[SESSION_ID_KEY]
    suspend fun setSessionId(sessionId: String?) = dataStore.edit { prefs ->
        if (sessionId != null) prefs[SESSION_ID_KEY] = sessionId else prefs.remove(SESSION_ID_KEY)
    }

    suspend fun getLastSequence(): Long = dataStore.data.first()[LAST_SEQUENCE_KEY] ?: 0L
    suspend fun setLastSequence(seq: Long) = dataStore.edit { it[LAST_SEQUENCE_KEY] = seq }

    /** 机器人自身 OpenID（READY 事件下发）。全量群消息模式靠它判断是否被 @。 */
    suspend fun getBotOpenId(): String? = dataStore.data.first()[BOT_OPENID_KEY]
    suspend fun setBotOpenId(openId: String?) = dataStore.edit { prefs ->
        if (!openId.isNullOrBlank()) prefs[BOT_OPENID_KEY] = openId else prefs.remove(BOT_OPENID_KEY)
    }

    /**
     * **宿主（用户本人）的 user_openid**——主动发送的默认目标（键名 `qqbot_host_user_openid`）。
     *
     * 与 [getBotOpenId] 的机器人 openid 是**两个不同的标识**，不得混用。
     * 写入来源见 [HOST_USER_OPENID_KEY] 的 KDoc；空值一律不写（保持「没有目标」的诚实状态，
     * 上层据此明确失败，而不是发到一个空字符串目标上）。
     */
    override suspend fun getHostUserOpenId(): String? =
        dataStore.data.first()[HOST_USER_OPENID_KEY]?.takeIf { it.isNotBlank() }

    override suspend fun setHostUserOpenId(openId: String?) {
        dataStore.edit { prefs ->
            val trimmed = openId?.trim()
            if (!trimmed.isNullOrEmpty()) prefs[HOST_USER_OPENID_KEY] = trimmed
            else prefs.remove(HOST_USER_OPENID_KEY)
        }
    }

    override suspend fun getRecentGroupOpenId(): String? =
        dataStore.data.first()[RECENT_GROUP_OPENID_KEY]?.takeIf { it.isNotBlank() }

    override suspend fun setRecentGroupOpenId(openId: String?) {
        dataStore.edit { prefs ->
            val trimmed = openId?.trim()
            if (!trimmed.isNullOrEmpty()) prefs[RECENT_GROUP_OPENID_KEY] = trimmed
            else prefs.remove(RECENT_GROUP_OPENID_KEY)
        }
    }


    private val userCompanionMapFlow: Flow<Map<String, Long>> = dataStore.data.map { prefs ->
        prefs[USER_COMPANION_MAP_KEY]?.let {
            try { json.decodeFromString(it) } catch (_: Exception) { emptyMap() }
        } ?: emptyMap()
    }

    suspend fun getCompanionIdForQQUser(qqUserId: String): Long? = userCompanionMapFlow.first()[qqUserId]

    suspend fun setCompanionIdForQQUser(qqUserId: String, companionId: Long) {
        val current = userCompanionMapFlow.first().toMutableMap()
        current[qqUserId] = companionId
        dataStore.edit { it[USER_COMPANION_MAP_KEY] = json.encodeToString(current) }
    }

    suspend fun removeQQUserMapping(qqUserId: String) {
        val current = userCompanionMapFlow.first().toMutableMap()
        current.remove(qqUserId)
        dataStore.edit { it[USER_COMPANION_MAP_KEY] = json.encodeToString(current) }
    }

    suspend fun getAllQQUserMappings(): Map<String, Long> = userCompanionMapFlow.first()
}
