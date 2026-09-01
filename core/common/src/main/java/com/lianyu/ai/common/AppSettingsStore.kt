package com.lianyu.ai.common

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppSettingsStore(context: Context) {

    private val dataStore = context.applicationContext.appSettingsDataStore

    private companion object {
        private val SHOW_REASONING_KEY = booleanPreferencesKey("show_reasoning")
        private const val DEFAULT_SHOW_REASONING = false

        private val REASONING_RESPONSE_FIELD_KEY = stringPreferencesKey("reasoning_response_field")
        private const val DEFAULT_REASONING_RESPONSE_FIELD = "reasoning_content"

        private val REASONING_REQUEST_FIELD_KEY = stringPreferencesKey("reasoning_request_field")
        private const val DEFAULT_REASONING_REQUEST_FIELD = "reasoning_content"

        private val SEND_REASONING_KEY = booleanPreferencesKey("send_reasoning")
        private const val DEFAULT_SEND_REASONING = false

        private val AUTO_COLLAPSE_REASONING_KEY = booleanPreferencesKey("auto_collapse_reasoning")
        private const val DEFAULT_AUTO_COLLAPSE_REASONING = true

        private val VISION_ENABLED_KEY = booleanPreferencesKey("vision_enabled")
        private const val DEFAULT_VISION_ENABLED = true

        private val VISION_MODEL_KEY = stringPreferencesKey("vision_model")
        private const val DEFAULT_VISION_MODEL = "auto"

        private val VISION_PROVIDER_KEY = stringPreferencesKey("vision_provider")
        private const val DEFAULT_VISION_PROVIDER = "auto"

        private val VISION_API_URL_KEY = stringPreferencesKey("vision_api_url")
        private const val DEFAULT_VISION_API_URL = ""

        private val VISION_API_KEY_KEY = stringPreferencesKey("vision_api_key")
        private const val DEFAULT_VISION_API_KEY = ""

        private val DIARY_ENABLED_KEY = booleanPreferencesKey("diary_enabled")
        private const val DEFAULT_DIARY_ENABLED = false

        private val DIARY_MODEL_KEY = stringPreferencesKey("diary_model")
        private const val DEFAULT_DIARY_MODEL = ""

        private val DIARY_BASE_URL_KEY = stringPreferencesKey("diary_base_url")
        private const val DEFAULT_DIARY_BASE_URL = ""

        private val DIARY_API_KEY_KEY = stringPreferencesKey("diary_api_key")
        private const val DEFAULT_DIARY_API_KEY = ""

        private val INNER_THOUGHT_ENABLED_KEY = booleanPreferencesKey("inner_thought_enabled")
        private const val DEFAULT_INNER_THOUGHT_ENABLED = false

        private val YANDERE_MODE_ENABLED_KEY = booleanPreferencesKey("yandere_mode_enabled")
        private const val DEFAULT_YANDERE_MODE_ENABLED = false

        private val YANDERE_MODE_USAGE_STATS_KEY = booleanPreferencesKey("yandere_mode_usage_stats")
        private const val DEFAULT_YANDERE_MODE_USAGE_STATS = true

        private val YANDERE_MODE_INSTALLED_APPS_KEY = booleanPreferencesKey("yandere_mode_installed_apps")
        private const val DEFAULT_YANDERE_MODE_INSTALLED_APPS = true

        private val SHOW_TYPING_SPINNER_KEY = booleanPreferencesKey("show_typing_spinner")
        private const val DEFAULT_SHOW_TYPING_SPINNER = false
        private val SEARCH_API_KEY_KEY = stringPreferencesKey("search_api_key")
        private const val DEFAULT_SEARCH_API_KEY = ""
    }

    object VisionModels {
        const val VISION_AUTO = "auto"
        const val VISION_GPT4O = "gpt-4o"
        const val VISION_GPT4_VISION = "gpt-4-vision-preview"
        const val VISION_CLAUDE_SONNET = "claude-3-5-sonnet-20241022"
        const val VISION_GEMINI_PRO = "gemini-1.5-pro-vision"
        const val VISION_DEEPSEEK_VL = "deepseek-vl"
        const val VISION_KIMI_K26 = "kimi-k2.6"

        val VISION_MODEL_OPTIONS = listOf(
            Triple(VISION_AUTO, "自动检测", "根据当前AI提供商自动选择视觉模型"),
            Triple(VISION_GPT4O, "GPT-4o", "OpenAI 多模态模型"),
            Triple(VISION_GPT4_VISION, "GPT-4 Vision", "OpenAI 视觉模型"),
            Triple(VISION_CLAUDE_SONNET, "Claude 3.5 Sonnet Vision", "Anthropic 视觉模型"),
            Triple(VISION_GEMINI_PRO, "Gemini Pro Vision", "Google 视觉模型"),
            Triple(VISION_DEEPSEEK_VL, "DeepSeek-VL", "DeepSeek 视觉模型"),
            Triple(VISION_KIMI_K26, "Kimi K2.6", "Moonshot AI 最新视觉模型")
        )

        fun getVisionModelDisplayName(modelId: String): String {
            return VISION_MODEL_OPTIONS.find { it.first == modelId }?.second ?: modelId
        }

        fun resolveVisionModel(visionModelSetting: String, providerName: String): String {
            if (visionModelSetting != VISION_AUTO) return visionModelSetting
            return when (providerName.lowercase()) {
                "openai", "partner" -> VISION_GPT4O
                "anthropic" -> VISION_CLAUDE_SONNET
                "gemini", "google" -> VISION_GEMINI_PRO
                "deepseek" -> VISION_DEEPSEEK_VL
                "kimi", "moonshot" -> VISION_KIMI_K26
                "custom" -> VISION_GPT4O // Default for custom, user should specify model explicitly
                else -> VISION_GPT4O
            }
        }
    }

    val showReasoningFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SHOW_REASONING_KEY] ?: DEFAULT_SHOW_REASONING
    }

    suspend fun getShowReasoning(): Boolean = showReasoningFlow.first()

    suspend fun setShowReasoning(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SHOW_REASONING_KEY] = enabled }
    }

    val reasoningResponseFieldFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[REASONING_RESPONSE_FIELD_KEY] ?: DEFAULT_REASONING_RESPONSE_FIELD
    }

    suspend fun getReasoningResponseField(): String = reasoningResponseFieldFlow.first()

    suspend fun setReasoningResponseField(field: String) {
        dataStore.edit { prefs ->
            prefs[REASONING_RESPONSE_FIELD_KEY] =
                field.ifBlank { DEFAULT_REASONING_RESPONSE_FIELD }
        }
    }

    val reasoningRequestFieldFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[REASONING_REQUEST_FIELD_KEY] ?: DEFAULT_REASONING_REQUEST_FIELD
    }

    suspend fun getReasoningRequestField(): String = reasoningRequestFieldFlow.first()

    suspend fun setReasoningRequestField(field: String) {
        dataStore.edit { prefs ->
            prefs[REASONING_REQUEST_FIELD_KEY] =
                field.ifBlank { DEFAULT_REASONING_REQUEST_FIELD }
        }
    }

    val sendReasoningFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SEND_REASONING_KEY] ?: DEFAULT_SEND_REASONING
    }

    suspend fun getSendReasoning(): Boolean = sendReasoningFlow.first()

    suspend fun setSendReasoning(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SEND_REASONING_KEY] = enabled }
    }

    val autoCollapseReasoningFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AUTO_COLLAPSE_REASONING_KEY] ?: DEFAULT_AUTO_COLLAPSE_REASONING
    }

    suspend fun getAutoCollapseReasoning(): Boolean = autoCollapseReasoningFlow.first()

    suspend fun setAutoCollapseReasoning(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[AUTO_COLLAPSE_REASONING_KEY] = enabled }
    }

    /**
     * 原子保存思考相关设置（单次 edit，避免多字段分次写入半状态）。
     * 调用方应在稳定作用域（如 ViewModel.viewModelScope）中执行。
     */
    suspend fun saveThinkingSettings(
        showReasoning: Boolean,
        autoCollapseReasoning: Boolean,
        responseField: String,
        requestField: String,
        sendReasoning: Boolean
    ) {
        dataStore.edit { prefs ->
            prefs[SHOW_REASONING_KEY] = showReasoning
            prefs[AUTO_COLLAPSE_REASONING_KEY] = autoCollapseReasoning
            prefs[REASONING_RESPONSE_FIELD_KEY] =
                responseField.ifBlank { DEFAULT_REASONING_RESPONSE_FIELD }
            prefs[REASONING_REQUEST_FIELD_KEY] =
                requestField.ifBlank { DEFAULT_REASONING_REQUEST_FIELD }
            prefs[SEND_REASONING_KEY] = sendReasoning
        }
    }

    val visionEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[VISION_ENABLED_KEY] ?: DEFAULT_VISION_ENABLED
    }

    suspend fun getVisionEnabled(): Boolean = visionEnabledFlow.first()

    suspend fun setVisionEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[VISION_ENABLED_KEY] = enabled }
    }

    val visionModelFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[VISION_MODEL_KEY] ?: DEFAULT_VISION_MODEL
    }

    suspend fun getVisionModel(): String = visionModelFlow.first()

    suspend fun setVisionModel(model: String) {
        dataStore.edit { prefs -> prefs[VISION_MODEL_KEY] = model }
    }

    val visionProviderFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[VISION_PROVIDER_KEY] ?: DEFAULT_VISION_PROVIDER
    }

    suspend fun getVisionProvider(): String = visionProviderFlow.first()

    suspend fun setVisionProvider(provider: String) {
        dataStore.edit { prefs -> prefs[VISION_PROVIDER_KEY] = provider }
    }

    val visionApiUrlFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[VISION_API_URL_KEY] ?: DEFAULT_VISION_API_URL
    }

    suspend fun getVisionApiUrl(): String = visionApiUrlFlow.first()

    suspend fun setVisionApiUrl(url: String) {
        dataStore.edit { prefs -> prefs[VISION_API_URL_KEY] = url }
    }

    val visionApiKeyFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[VISION_API_KEY_KEY] ?: DEFAULT_VISION_API_KEY
    }

    suspend fun getVisionApiKey(): String = visionApiKeyFlow.first()

    suspend fun setVisionApiKey(key: String) {
        dataStore.edit { prefs -> prefs[VISION_API_KEY_KEY] = key }
    }

    val diaryEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DIARY_ENABLED_KEY] ?: DEFAULT_DIARY_ENABLED
    }

    suspend fun getDiaryEnabled(): Boolean = diaryEnabledFlow.first()

    suspend fun setDiaryEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DIARY_ENABLED_KEY] = enabled }
    }

    val diaryModelFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[DIARY_MODEL_KEY] ?: DEFAULT_DIARY_MODEL
    }

    suspend fun getDiaryModel(): String = diaryModelFlow.first()

    suspend fun setDiaryModel(model: String) {
        dataStore.edit { prefs -> prefs[DIARY_MODEL_KEY] = model }
    }

    val diaryBaseUrlFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[DIARY_BASE_URL_KEY] ?: DEFAULT_DIARY_BASE_URL
    }

    suspend fun getDiaryBaseUrl(): String = diaryBaseUrlFlow.first()

    suspend fun setDiaryBaseUrl(url: String) {
        dataStore.edit { prefs -> prefs[DIARY_BASE_URL_KEY] = url }
    }

    val diaryApiKeyFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[DIARY_API_KEY_KEY] ?: DEFAULT_DIARY_API_KEY
    }

    suspend fun getDiaryApiKey(): String = diaryApiKeyFlow.first()

    suspend fun setDiaryApiKey(key: String) {
        dataStore.edit { prefs -> prefs[DIARY_API_KEY_KEY] = key }
    }

    val innerThoughtEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[INNER_THOUGHT_ENABLED_KEY] ?: DEFAULT_INNER_THOUGHT_ENABLED
    }

    suspend fun getInnerThoughtEnabled(): Boolean = innerThoughtEnabledFlow.first()

    suspend fun setInnerThoughtEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[INNER_THOUGHT_ENABLED_KEY] = enabled }
    }

    val yandereModeEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[YANDERE_MODE_ENABLED_KEY] ?: DEFAULT_YANDERE_MODE_ENABLED
    }

    suspend fun getYandereModeEnabled(): Boolean = yandereModeEnabledFlow.first()

    suspend fun setYandereModeEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[YANDERE_MODE_ENABLED_KEY] = enabled }
    }

    val yandereModeUsageStatsFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[YANDERE_MODE_USAGE_STATS_KEY] ?: DEFAULT_YANDERE_MODE_USAGE_STATS
    }

    suspend fun getYandereModeUsageStats(): Boolean = yandereModeUsageStatsFlow.first()

    suspend fun setYandereModeUsageStats(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[YANDERE_MODE_USAGE_STATS_KEY] = enabled }
    }

    val yandereModeInstalledAppsFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[YANDERE_MODE_INSTALLED_APPS_KEY] ?: DEFAULT_YANDERE_MODE_INSTALLED_APPS
    }

    suspend fun getYandereModeInstalledApps(): Boolean = yandereModeInstalledAppsFlow.first()

    suspend fun setYandereModeInstalledApps(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[YANDERE_MODE_INSTALLED_APPS_KEY] = enabled }
    }

    /**
     * 聊天页顶栏「对方正在输入」是否显示转圈动画。
     * 默认关闭（false）：仅显示文字，仿微信风格。
     */
    val showTypingSpinnerFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SHOW_TYPING_SPINNER_KEY] ?: DEFAULT_SHOW_TYPING_SPINNER
    }

    suspend fun getShowTypingSpinner(): Boolean = showTypingSpinnerFlow.first()

    suspend fun setShowTypingSpinner(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SHOW_TYPING_SPINNER_KEY] = enabled }
    }

    val searchApiKeyFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[SEARCH_API_KEY_KEY] ?: DEFAULT_SEARCH_API_KEY
    }

    suspend fun getSearchApiKey(): String = searchApiKeyFlow.first()

    suspend fun setSearchApiKey(key: String) {
        dataStore.edit { prefs -> prefs[SEARCH_API_KEY_KEY] = key }
    }

    // Generic string preference access for dynamic configs
    suspend fun getString(key: String): String {
        val prefKey = stringPreferencesKey(key)
        return dataStore.data.map { prefs -> prefs[prefKey] ?: "" }.first()
    }

    suspend fun setString(key: String, value: String) {
        val prefKey = stringPreferencesKey(key)
        dataStore.edit { prefs -> prefs[prefKey] = value }
    }

    suspend fun getString(key: String, defaultValue: String): String {
        val prefKey = stringPreferencesKey(key)
        return dataStore.data.map { prefs -> prefs[prefKey] ?: defaultValue }.first()
    }
}