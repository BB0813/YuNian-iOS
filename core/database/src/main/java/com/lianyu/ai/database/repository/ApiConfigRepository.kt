package com.lianyu.ai.database.repository

import com.lianyu.ai.common.SuFlowApi
import com.lianyu.ai.database.dao.ApiConfigDao
import com.lianyu.ai.database.dao.ApiProviderPresetDao
import com.lianyu.ai.database.model.ApiConfig
import com.lianyu.ai.database.model.ApiProvider
import com.lianyu.ai.database.model.ApiProviderPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.URI

class ApiConfigRepository(
    private val apiConfigDao: ApiConfigDao,
    private val apiProviderPresetDao: ApiProviderPresetDao? = null,
    private val secretCodec: SecretCodec = S0
) {
    interface SecretCodec {
        fun encrypt(plaintext: String): String
        fun decrypt(value: String): String?
        fun isEncrypted(value: String): Boolean
    }

    fun getAllConfigs(): Flow<List<ApiConfig>> = apiConfigDao.getAllConfigs().map { list ->
        list.mapNotNull { decryptForUse(it, secretCodec) }
    }

    fun getConfigsByProvider(provider: ApiProvider): Flow<List<ApiConfig>> = 
        apiConfigDao.getConfigsByProvider(provider).map { list ->
            list.mapNotNull { decryptForUse(it, secretCodec) }
        }

    suspend fun getConfigById(id: Long): ApiConfig? =
        apiConfigDao.getConfigById(id)?.let { decryptAndMigrateIfNeeded(it) }

    suspend fun getConfigByProvider(provider: ApiProvider): ApiConfig? =
        apiConfigDao.getConfigByProvider(provider)?.let { decryptAndMigrateIfNeeded(it) }

    suspend fun getActiveConfig(): ApiConfig? =
        apiConfigDao.getActiveConfig()?.let { decryptAndMigrateIfNeeded(it) }

    fun getAllConfiguredConfigs(): Flow<List<ApiConfig>> = apiConfigDao.getAllConfiguredConfigs().map { list ->
        list.mapNotNull { decryptForUse(it, secretCodec) }
    }

    fun getVisibleProviderPresets(): Flow<List<ApiProviderPreset>> {
        val dao = requireNotNull(apiProviderPresetDao) { "ApiProviderPresetDao is required" }
        return dao.getVisiblePresets().map { list ->
            list.map { sanitizePreset(it) }
        }
    }

    suspend fun getProviderPreset(provider: ApiProvider): ApiProviderPreset? {
        val dao = requireNotNull(apiProviderPresetDao) { "ApiProviderPresetDao is required" }
        return dao.getPreset(provider)?.let { sanitizePreset(it) }
    }

    suspend fun getActiveEnabledConfig(): ApiConfig? =
        apiConfigDao.getActiveEnabledConfig()?.let { decryptAndMigrateIfNeeded(it) }

    suspend fun saveConfig(config: ApiConfig) = apiConfigDao.insertConfig(encryptForStorage(config, secretCodec))

    suspend fun updateConfig(config: ApiConfig) = apiConfigDao.updateConfig(encryptForStorage(config, secretCodec))

    suspend fun deleteConfigById(id: Long) = apiConfigDao.deleteConfigById(id)

    suspend fun deleteConfig(provider: ApiProvider) = apiConfigDao.deleteConfig(provider)

    suspend fun disableOtherConfigs(id: Long) = apiConfigDao.disableOtherConfigs(id)

    suspend fun enableConfig(id: Long) = apiConfigDao.enableConfig(id)

    private suspend fun decryptAndMigrateIfNeeded(config: ApiConfig): ApiConfig? {
        val decrypted = decryptForUse(config, secretCodec) ?: return null
        // 自愈：PARTNER 配置被污染为内网/本地地址时，写回生产地址
        val needsFix = needsSecretMigration(config, secretCodec) ||
            (config.provider == ApiProvider.PARTNER && config.baseUrl != decrypted.baseUrl)
        if (needsFix) {
            apiConfigDao.updateConfig(encryptForStorage(decrypted, secretCodec))
        }
        return decrypted
    }

    companion object {
        /** 正式发布版 PARTNER (Clove API) 固定指向生产网关 */
        const val PARTNER_PRODUCTION_BASE_URL: String = SuFlowApi.CHAT_BASE_URL

        /**
         * PARTNER baseUrl 净化：正式版仅允许 https 公网地址。
         * 内网 / 本地 / 明文地址一律回退到生产网关 suflow.cloud。
         * 防止测试期间写入数据库的 192.168.x.x 等地址污染正式版。
         */
        fun sanitizePartnerBaseUrl(raw: String?): String {
            val url = raw?.trim().orEmpty()
            if (url.isBlank()) return PARTNER_PRODUCTION_BASE_URL
            return try {
                val lower = url.lowercase()
                if (!lower.startsWith("https://")) return PARTNER_PRODUCTION_BASE_URL
                val host = URI(lower).host ?: return PARTNER_PRODUCTION_BASE_URL
                val isLocal = host == "localhost" ||
                    host.startsWith("127.") ||
                    host.startsWith("10.") ||
                    host.startsWith("192.168.") ||
                    (host.startsWith("172.") && (host.substringAfter("172.").substringBefore(".").toIntOrNull()?.let { it in 16..31 } == true))
                if (isLocal) PARTNER_PRODUCTION_BASE_URL else url
            } catch (e: Exception) {
                PARTNER_PRODUCTION_BASE_URL
            }
        }

        /** PARTNER 预设同样净化 baseUrl */
        fun sanitizePreset(preset: ApiProviderPreset): ApiProviderPreset =
            if (preset.provider == ApiProvider.PARTNER) {
                preset.copy(baseUrl = sanitizePartnerBaseUrl(preset.baseUrl))
            } else {
                preset
            }

        fun encryptForStorage(config: ApiConfig, codec: SecretCodec): ApiConfig {
            var result = config
            if (result.provider == ApiProvider.PARTNER) {
                result = result.copy(baseUrl = sanitizePartnerBaseUrl(result.baseUrl))
            }
            if (result.apiKey.isBlank() || codec.isEncrypted(result.apiKey)) return result
            return result.copy(apiKey = codec.encrypt(result.apiKey))
        }

        fun decryptForUse(config: ApiConfig, codec: SecretCodec): ApiConfig? {
            var result = config
            if (result.provider == ApiProvider.PARTNER) {
                result = result.copy(baseUrl = sanitizePartnerBaseUrl(result.baseUrl))
            }
            if (result.apiKey.isBlank()) return result
            if (!codec.isEncrypted(result.apiKey)) return result
            val plaintext = codec.decrypt(result.apiKey)
            if (plaintext == null) {
                return null
            }
            return result.copy(apiKey = plaintext)
        }

        fun needsSecretMigration(config: ApiConfig, codec: SecretCodec): Boolean =
            config.apiKey.isNotBlank() && !codec.isEncrypted(config.apiKey)
    }
}
