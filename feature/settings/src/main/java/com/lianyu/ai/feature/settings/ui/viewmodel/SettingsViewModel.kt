package com.lianyu.ai.feature.settings.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.common.AppSettingsStore
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ApiConfig
import com.lianyu.ai.database.model.ApiProvider
import com.lianyu.ai.database.repository.ApiConfigRepository
import com.lianyu.ai.domain.LocalModelProvider
import com.lianyu.ai.domain.ModelState
import com.lianyu.ai.domain.ModelStatus
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.network.AiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ApiConfigRepository
    private val localModelProvider = ServiceRegistry.get(LocalModelProvider::class.java)
    private val appSettingsStore = AppSettingsStore(application)
    val configs: Flow<List<ApiConfig>>
    private val _localModelState = MutableStateFlow(ModelState())
    val localModelState: StateFlow<ModelState> = _localModelState.asStateFlow()
    private val _modelStates = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val modelStates: StateFlow<Map<String, ModelState>> = _modelStates.asStateFlow()

    private val _connectionStatus = mutableStateMapOf<String, ConnectionResult>()
    val connectionStatus: Map<String, ConnectionResult> = _connectionStatus

    // 保存测试连接后更新的配置（包含远程密钥和随机选择的模型）
    private val _testedConfigs = mutableStateMapOf<String, ApiConfig>()
    val testedConfigs: Map<String, ApiConfig> = _testedConfigs

    private val _saveResult = MutableSharedFlow<SaveResult>()
    val saveResult: SharedFlow<SaveResult> = _saveResult

    data class TestCompletionEvent(
        val isSuccess: Boolean,
        val providerName: String,
        val latencyMs: Long = 0L,
        val errorMessage: String? = null
    )
    private val _testCompletionEvent = MutableSharedFlow<TestCompletionEvent>(extraBufferCapacity = 1)
    val testCompletionEvent: SharedFlow<TestCompletionEvent> = _testCompletionEvent

    private val _contextLimit = MutableStateFlow(50)
    val contextLimit: StateFlow<Int> = _contextLimit.asStateFlow()

    private val _visionEnabled = MutableStateFlow(true)
    val visionEnabled: StateFlow<Boolean> = _visionEnabled.asStateFlow()
    private val _visionModel = MutableStateFlow(AppSettingsStore.VisionModels.VISION_AUTO)
    val visionModel: StateFlow<String> = _visionModel.asStateFlow()
    private val _visionProvider = MutableStateFlow("auto")
    val visionProvider: StateFlow<String> = _visionProvider.asStateFlow()
    private val _visionApiUrl = MutableStateFlow("")
    val visionApiUrl: StateFlow<String> = _visionApiUrl.asStateFlow()
    private val _visionApiKey = MutableStateFlow("")
    val visionApiKey: StateFlow<String> = _visionApiKey.asStateFlow()

    private val _innerThoughtEnabled = MutableStateFlow(false)
    val innerThoughtEnabled: StateFlow<Boolean> = _innerThoughtEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            appSettingsStore.contextLimitFlow.collect { limit ->
                _contextLimit.value = limit
            }
        }

        viewModelScope.launch {
            appSettingsStore.visionEnabledFlow.collect { enabled ->
                _visionEnabled.value = enabled
            }
        }

        viewModelScope.launch {
            appSettingsStore.visionModelFlow.collect { model ->
                _visionModel.value = model
            }
        }

        viewModelScope.launch {
            appSettingsStore.visionProviderFlow.collect { provider ->
                _visionProvider.value = provider
            }
        }

        viewModelScope.launch {
            appSettingsStore.visionApiUrlFlow.collect { url ->
                _visionApiUrl.value = url
            }
        }

        viewModelScope.launch {
            appSettingsStore.visionApiKeyFlow.collect { key ->
                _visionApiKey.value = key
            }
        }

        viewModelScope.launch {
            appSettingsStore.innerThoughtEnabledFlow.collect { enabled ->
                _innerThoughtEnabled.value = enabled
            }
        }
    }

    fun setContextLimit(limit: Int) {
        viewModelScope.launch {
            appSettingsStore.setContextLimit(limit)
        }
    }

    fun setVisionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsStore.setVisionEnabled(enabled)
        }
    }

    fun setVisionModel(model: String) {
        viewModelScope.launch {
            appSettingsStore.setVisionModel(model)
        }
    }

    fun setVisionProvider(provider: String) {
        viewModelScope.launch {
            appSettingsStore.setVisionProvider(provider)
        }
    }

    fun setVisionApiUrl(url: String) {
        viewModelScope.launch {
            appSettingsStore.setVisionApiUrl(url)
        }
    }

    fun setVisionApiKey(key: String) {
        viewModelScope.launch {
            appSettingsStore.setVisionApiKey(key)
        }
    }

    fun setInnerThoughtEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsStore.setInnerThoughtEnabled(enabled)
        }
    }

    suspend fun testVisionConnection(provider: String, baseUrl: String, apiKey: String, model: String): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                if (baseUrl.isBlank() || apiKey.isBlank()) {
                    throw IllegalArgumentException("API 地址和密钥不能为空")
                }

                val resolvedProvider = when (provider.uppercase()) {
                    "OPENAI" -> ApiProvider.OPENAI
                    "ANTHROPIC" -> ApiProvider.ANTHROPIC
                    "GEMINI" -> ApiProvider.GEMINI
                    "KIMI" -> ApiProvider.KIMI
                    "DEEPSEEK" -> ApiProvider.DEEPSEEK
                    "DASHSCOPE" -> ApiProvider.DASHSCOPE
                    "ZHIPU" -> ApiProvider.ZHIPU
                    "CUSTOM" -> ApiProvider.CUSTOM
                    "IFLYTEK" -> ApiProvider.IFLYTEK
                    else -> ApiProvider.OPENAI
                }

                val config = ApiConfig(
                    provider = resolvedProvider,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = model
                )

                val aiService = AiService(getApplication())
                val testMessages = listOf(
                    com.lianyu.ai.network.Message("system", "You are a helpful assistant."),
                    com.lianyu.ai.network.Message("user", "Hi")
                )

                val response = when (resolvedProvider) {
                    ApiProvider.ANTHROPIC -> aiService.callAnthropicForTest(config, testMessages, "Be helpful.")
                    else -> aiService.callOpenAiCompatibleForTest(config, testMessages)
                }

                "连接成功！响应: ${response.take(50)}"
            }
        }
    }

    enum class ConnectionStatus {
        UNKNOWN, CONNECTED, FAILED, TESTING
    }

    data class ConnectionResult(
        val status: ConnectionStatus,
        val latencyMs: Long = 0L,
        val errorMessage: String? = null
    )

    fun connectionKey(config: ApiConfig): String =
        if (config.id > 0) "id:${config.id}" else "draft:${config.provider.name}:${config.name}:${config.baseUrl}"

    sealed class SaveResult {
        data class Success(val message: String) : SaveResult()
        data class Error(val message: String) : SaveResult()
    }

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ApiConfigRepository(database.apiConfigDao())
        configs = repository.getAllConfigs()
        // P2-15: 同步恢复已保存配置的连接状态（不用 Flow.collect 异步等待）
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            val saved = repository.getAllConfigs().first()
            saved.forEach { config ->
                val key = connectionKey(config)
                if (config.connectionTested && _connectionStatus[key] == null) {
                    _connectionStatus[key] = ConnectionResult(ConnectionStatus.CONNECTED, config.latencyMs)
                }
            }
        }
        // 同时启动异步持续监听（供 refreshConnectionStatus 和后续配置变更）
        refreshConnectionStatus()

        viewModelScope.launch {
            localModelProvider?.let { provider ->
                // Poll for model states periodically
                while (true) {
                    _modelStates.value = provider.getAllModelStates()
                    val selected = _modelStates.value.values.find { it.isSelected }
                    if (selected != null) _localModelState.value = selected
                    kotlinx.coroutines.delay(500)
                }
            }
        }
    }

    fun saveConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val key = connectionKey(config)
                val draftStatus = _connectionStatus[key]
                
                // 优先使用测试连接后保存的配置（包含远程密钥和测试结果）
                val testedConfig = _testedConfigs[key]

                val configToSave = if (testedConfig != null && draftStatus?.status == ConnectionStatus.CONNECTED) {
                    // 合并测试后的远程密钥/连接状态，但保留用户手动填写的字段
                    testedConfig.copy(
                        id = config.id,  // 保持原始ID
                        // 如果用户手动填写了密钥/地址/模型，优先尊重用户选择；否则沿用测试后的值
                        apiKey = config.apiKey.takeIf { it.isNotBlank() } ?: testedConfig.apiKey,
                        extraApiKeys = config.extraApiKeys.takeIf { it.isNotBlank() } ?: testedConfig.extraApiKeys,
                        baseUrl = config.baseUrl.takeIf { it.isNotBlank() } ?: testedConfig.baseUrl,
                        model = config.model.takeIf { it.isNotBlank() } ?: testedConfig.model,
                        name = config.name,
                        temperature = config.temperature,
                        maxTokens = config.maxTokens,
                        connectionTested = true,
                        connectionTestedAt = System.currentTimeMillis(),
                        latencyMs = draftStatus.latencyMs
                    )
                } else if (draftStatus?.status == ConnectionStatus.CONNECTED) {
                    config.copy(
                        connectionTested = true,
                        connectionTestedAt = System.currentTimeMillis(),
                        latencyMs = draftStatus.latencyMs
                    )
                } else {
                    config
                }

                SecureLog.d("SettingsViewModel", "Saving config: provider=${configToSave.provider}, model=${configToSave.model}, keys=${configToSave.getAllApiKeys().size}")

                val savedId = repository.saveConfig(configToSave)
                // 保存时自动启用当前配置并禁用其他配置（单活跃模式）
                repository.disableOtherConfigs(savedId)
                repository.enableConfig(savedId)
                val savedKey = connectionKey(configToSave.copy(id = savedId))
                _connectionStatus[savedKey] = draftStatus ?: ConnectionResult(ConnectionStatus.UNKNOWN)
                // 同步测试后缓存，避免下次编辑时显示旧模型
                _testedConfigs[savedKey] = configToSave
                _saveResult.emit(SaveResult.Success("配置已保存并已启用"))
            } catch (e: Exception) {
                SecureLog.e("SettingsViewModel", "Save config failed: ${e.message}")
                _saveResult.emit(SaveResult.Error("保存失败，请检查配置"))
            }
        }
    }

    fun toggleConfigEnabled(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val actualConfig = resolveConfig(config)
                if (actualConfig.isEnabled) {
                    val updated = actualConfig.copy(isEnabled = false)
                    repository.updateConfig(updated)
                    _saveResult.emit(SaveResult.Success("${actualConfig.provider.displayName} 已禁用"))
                } else {
                    repository.disableOtherConfigs(actualConfig.id)
                    val updated = actualConfig.copy(isEnabled = true)
                    repository.updateConfig(updated)
                    _saveResult.emit(SaveResult.Success("${actualConfig.provider.displayName} 已启用"))
                }
            } catch (e: Exception) {
                _saveResult.emit(SaveResult.Error("操作失败"))
            }
        }
    }

    fun selectActiveConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val oldKey = connectionKey(config)
                val actualConfig = resolveConfig(config)
                val newKey = connectionKey(actualConfig)
                // Migrate connection status if key changed (new config got an id)
                if (oldKey != newKey) {
                    _connectionStatus[oldKey]?.let { _connectionStatus[newKey] = it }
                }
                repository.disableOtherConfigs(actualConfig.id)
                val updated = actualConfig.copy(isEnabled = true)
                repository.updateConfig(updated)
                _saveResult.emit(SaveResult.Success("已切换到 ${actualConfig.provider.displayName}"))
            } catch (e: Exception) {
                _saveResult.emit(SaveResult.Error("切换失败"))
            }
        }
    }

    private suspend fun resolveConfig(config: ApiConfig): ApiConfig {
        if (config.id > 0) return config
        val existing = repository.getConfigByProvider(config.provider)
        if (existing != null) return existing
        val newId = repository.saveConfig(config)
        return config.copy(id = newId)
    }

    fun deleteConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            if (config.id > 0) {
                repository.deleteConfigById(config.id)
            } else {
                repository.deleteConfig(config.provider)
            }
            _connectionStatus.remove(connectionKey(config))
        }
    }

    fun deleteConfigByProvider(provider: ApiProvider) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteConfig(provider)
            _connectionStatus.entries.removeAll { it.key.startsWith("id:") }
        }
    }

    fun refreshConnectionStatus() {
        viewModelScope.launch {
            val allConfigs = repository.getAllConfigs()
            allConfigs.collect { list ->
                list.forEach { config ->
                    val key = connectionKey(config)
                    val current = _connectionStatus[key]
                    val status = when {
                        // Preserve current status — don't downgrade from known state
                        current?.status == ConnectionStatus.TESTING -> ConnectionResult(ConnectionStatus.TESTING, current.latencyMs)
                        current?.status == ConnectionStatus.CONNECTED -> ConnectionResult(ConnectionStatus.CONNECTED, current.latencyMs)
                        current?.status == ConnectionStatus.FAILED -> ConnectionResult(ConnectionStatus.FAILED, current.latencyMs, current.errorMessage)
                        // Only set from config if we don't already have a known state
                        config.connectionTested && current == null -> ConnectionResult(ConnectionStatus.CONNECTED, config.latencyMs)
                        // Keep UNKNOWN only for fresh starts (no prior state)
                        current != null -> current
                        else -> ConnectionResult(ConnectionStatus.UNKNOWN)
                    }
                    _connectionStatus[key] = status
                }
            }
        }
    }

    fun testConnection(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            val key = connectionKey(config)
            _connectionStatus[key] = ConnectionResult(ConnectionStatus.TESTING)
            var allKeys = config.getAllApiKeys()

            SecureLog.d("SettingsViewModel", "=== TEST CONNECTION START ===")
            SecureLog.d("SettingsViewModel", "Testing config: provider=${config.provider}, baseUrl=${config.baseUrl}, model=${config.model}")
            SecureLog.d("SettingsViewModel", "Available keys: ${allKeys.size} (userKey=${config.apiKey.takeIf { it.isNotBlank() }?.take(8)}..., remote=${allKeys.size > 1})")
            SecureLog.d("SettingsViewModel", "isEnabled=${config.isEnabled}, connectionTested=${config.connectionTested}")
            SecureLog.d("SettingsViewModel", "isPARTNER=${config.provider == ApiProvider.PARTNER}, providerName=${config.provider.name}")

            // 对于 PARTNER 类型：总是从远程服务器获取最新密钥
            var currentConfig = config
            if (config.provider == ApiProvider.PARTNER) {
                SecureLog.d("SettingsViewModel", "PARTNER mode: fetching fresh keys from remote server...")
                var earlyReturn = false
                try {
                    val remoteKeys = com.lianyu.ai.common.RemoteKeyProvider.fetchKeysAsync(getApplication(), forceRefresh = true)
                    if (remoteKeys.isNotEmpty()) {
                        allKeys = remoteKeys
                        // 更新 config 对象，让后续调用能使用远程密钥
                        currentConfig = config.copy(
                            apiKey = remoteKeys.first(),
                            extraApiKeys = remoteKeys.drop(1).joinToString(",")
                        )
                        SecureLog.d("SettingsViewModel", "Fetched ${remoteKeys.size} remote keys for testing")
                    } else {
                        _connectionStatus[key] = ConnectionResult(
                            ConnectionStatus.FAILED,
                            0L,
                            "无法从服务器获取密钥，请检查网络连接或服务器配置"
                        )
                        earlyReturn = true
                    }
                } catch (e: Exception) {
                    SecureLog.e("SettingsViewModel", "Failed to fetch remote keys: ${e.message}")
                    _connectionStatus[key] = ConnectionResult(
                        ConnectionStatus.FAILED,
                        0L,
                        "远程密钥获取失败: ${e.message}"
                    )
                    earlyReturn = true
                }
                if (earlyReturn) return@launch
            }

            if (allKeys.isEmpty()) {
                _connectionStatus[key] = ConnectionResult(ConnectionStatus.FAILED, 0L, "API Key 为空，请填写主密钥或检查远程Key服务")
                return@launch
            }

            val startTime = System.currentTimeMillis()
            
            // 如果用户已经手动填写了模型名，优先尊重用户选择；仅在未填写时自动拉取并兜底选择
            var testConfig = currentConfig
            val userModel = currentConfig.model.trim()

            if (userModel.isBlank() ||
                currentConfig.provider == ApiProvider.PARTNER ||
                currentConfig.provider == ApiProvider.CUSTOM) {

                val aiService = AiService(getApplication())
                val keyToUse = allKeys.firstOrNull() ?: currentConfig.apiKey
                SecureLog.d("SettingsViewModel", "Fetching models with key: ${keyToUse.take(8)}...")

                val modelsResult = aiService.fetchModels(currentConfig.baseUrl, keyToUse, currentConfig.provider, currentConfig.skipCertVerify)
                val models = modelsResult.getOrNull()

                if (models != null && models.isNotEmpty()) {
                    SecureLog.d("SettingsViewModel", "Found ${models.size} models: ${models.take(5).joinToString(", ")}")
                    _fetchedModels.value = _fetchedModels.value.toMutableMap().apply {
                        put(currentConfig.provider.name, models)
                    }

                    // P2-15: 优先选 chat 模型，避免随机到 embedding/vision-only 模型导致测试失败
                    // [FIX] 补上 kimi，避免 kimi-k2.7-code 等模型被排除
                    val chatKeywords = listOf("chat", "completion", "instruct", "gpt", "claude", "gemini",
                        "deepseek", "qwen", "glm", "moonshot", "kimi", "yi-", "ernie", "hunyuan", "doubao")
                    val chatModels = models.filter { m ->
                        chatKeywords.any { m.contains(it, ignoreCase = true) }
                    }

                    // [FIX] PARTNER 始终走本地随机选择，避免 server randomModel 固定导致每次相同
                    // 非 PARTNER: 用户已填模型名则尊重用户选择，未填则自动选第一个 chat 模型
                    val testModel = when {
                        currentConfig.provider == ApiProvider.PARTNER -> {
                            val serverModel = com.lianyu.ai.common.RemoteKeyProvider.getRandomModel(getApplication())?.takeIf { it.isNotBlank() }
                            // 若可用 chat 模型 > 1，随机选；否则从所有非 embedding 模型中随机
                            val candidatePool = if (chatModels.size > 1) chatModels
                            else models.filter { !it.contains("embed", ignoreCase = true) && !it.contains("moderation", ignoreCase = true) }
                            val chosenModel = if (candidatePool.size > 1) {
                                com.lianyu.ai.network.AiService.familyBalancedRandom(candidatePool)
                            } else {
                                serverModel ?: chatModels.firstOrNull()
                                ?: models.firstOrNull { !it.contains("embed", ignoreCase = true) && !it.contains("moderation", ignoreCase = true) }
                                ?: models.first()
                            }
                            SecureLog.d("SettingsViewModel", "PARTNER test model: chosen=$chosenModel, server=$serverModel, chatModels=${chatModels.size}, candidatePool=${candidatePool.size}")
                            chosenModel
                        }
                        // 用户已手动填写模型名 -> 尊重用户选择
                        userModel.isNotBlank() -> userModel
                        chatModels.isNotEmpty() -> chatModels.first()
                        else -> {
                            // Fallback: try first non-embedding model
                            models.firstOrNull { !it.contains("embed", ignoreCase = true) && !it.contains("moderation", ignoreCase = true) }
                                ?: models.first()
                        }
                    }

                    testConfig = currentConfig.copy(model = testModel)
                    SecureLog.d("SettingsViewModel", "Selected test model: $testModel (from ${models.size} models, ${chatModels.size} chat-capable)")
                } else {
                    SecureLog.w("SettingsViewModel", "No models fetched, using existing config: ${currentConfig.model}")
                }
            }

            // 确保有有效的模型名
            if (testConfig.model.isBlank()) {
                _connectionStatus[key] = ConnectionResult(ConnectionStatus.FAILED, 0L, "无法获取模型列表，请手动填写模型名称")
                return@launch
            }
            
            val result = runCatching {
                val aiService = AiService(getApplication())
                
                val testMessages = listOf(
                    com.lianyu.ai.network.Message("system", "You are a helpful assistant."),
                    com.lianyu.ai.network.Message("user", "Hi")
                )
                
                SecureLog.d("SettingsViewModel", "Calling API with: url=${testConfig.baseUrl}, model=${testConfig.model}")
                
                when (currentConfig.provider) {
                    ApiProvider.OPENAI,
                    ApiProvider.GEMINI,
                    ApiProvider.DEEPSEEK,
                    ApiProvider.DASHSCOPE,
                    ApiProvider.KIMI,
                    ApiProvider.XIAOMI,
                    ApiProvider.ZHIPU,
                    ApiProvider.SILICONFLOW,
                    ApiProvider.OPENROUTER,
                    ApiProvider.GROQ,
                    ApiProvider.CUSTOM,
                    ApiProvider.IFLYTEK,
                    ApiProvider.PARTNER -> aiService.callOpenAiCompatibleForTest(testConfig, testMessages)
                    ApiProvider.ANTHROPIC -> aiService.callAnthropicForTest(testConfig, testMessages, "Be helpful.")
                }
            }
            val latencyMs = System.currentTimeMillis() - startTime

            val isSuccess = result.isSuccess
            
            if (!isSuccess) {
                val errorMsg = result.exceptionOrNull()?.message ?: "未知错误"
                SecureLog.e("SettingsViewModel", "Connection test failed: $errorMsg")
                
                // 提供更友好的错误提示
                val friendlyError = when {
                    errorMsg.contains("401", ignoreCase = true) || errorMsg.contains("Unauthorized", ignoreCase = true) ->
                        "API Key 无效或已过期"
                    errorMsg.contains("403", ignoreCase = true) || errorMsg.contains("Forbidden", ignoreCase = true) ->
                        "API Key 没有权限访问此接口"
                    errorMsg.contains("404", ignoreCase = true) ->
                        "API 地址或模型名不正确"
                    errorMsg.contains("429", ignoreCase = true) ->
                        "请求过于频繁，请稍后再试"
                    errorMsg.contains("500", ignoreCase = true) || errorMsg.contains("502", ignoreCase = true) || errorMsg.contains("503", ignoreCase = true) ->
                        "服务器暂时不可用，请稍后再试"
                    errorMsg.contains("timeout", ignoreCase = true) || errorMsg.contains("Timeout", ignoreCase = true) ->
                        "连接超时，请检查网络或API地址"
                    errorMsg.contains("Unable to resolve host", ignoreCase = true) ->
                        "无法解析主机名，请检查API地址是否正确"
                    else -> errorMsg
                }
                
                _connectionStatus[key] = ConnectionResult(ConnectionStatus.FAILED, latencyMs, friendlyError)
            } else {
                _connectionStatus[key] = ConnectionResult(ConnectionStatus.CONNECTED, latencyMs)
            }

            _testCompletionEvent.emit(TestCompletionEvent(
                isSuccess = isSuccess,
                providerName = config.provider.displayName,
                latencyMs = latencyMs,
                errorMessage = if (!isSuccess) result.exceptionOrNull()?.message else null
            ))

            // 保存测试后的配置（包含远程密钥和随机选择的模型）
            if (isSuccess) {
                // 使用测试时的配置（包含远程密钥和随机模型）
                val finalConfig = testConfig.copy(
                    connectionTested = true,
                    connectionTestedAt = System.currentTimeMillis(),
                    latencyMs = latencyMs
                )
                // 保存到内存状态，供 saveConfig 使用
                _testedConfigs[key] = finalConfig
                
                if (config.id > 0) {
                    repository.updateConfig(finalConfig)
                    SecureLog.d("SettingsViewModel", "Saved tested config with remote keys and random model: ${finalConfig.model}")
                }
            } else if (config.id > 0) {
                // 测试失败，只更新测试状态
                val updatedConfig = config.copy(
                    connectionTested = false,
                    connectionTestedAt = 0L,
                    latencyMs = 0L
                )
                repository.updateConfig(updatedConfig)
            }
        }
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            localModelProvider?.enableModel(modelId)
        }
    }

    private val _fetchedModels = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val fetchedModels: StateFlow<Map<String, List<String>>> = _fetchedModels.asStateFlow()

    private val _balanceInfo = MutableStateFlow<AiService.BalanceInfo?>(null)
    val balanceInfo: StateFlow<AiService.BalanceInfo?> = _balanceInfo.asStateFlow()

    private val _balanceQueryFailed = MutableStateFlow(false)
    val balanceQueryFailed: StateFlow<Boolean> = _balanceQueryFailed.asStateFlow()

    data class ModelFetchState(
        val isLoading: Boolean = false,
        val errorMessage: String? = null
    )

    private val _modelFetchStates = MutableStateFlow<Map<String, ModelFetchState>>(emptyMap())
    val modelFetchStates: StateFlow<Map<String, ModelFetchState>> = _modelFetchStates.asStateFlow()

    fun fetchModels(baseUrl: String, apiKey: String, provider: String, skipCertVerify: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            _modelFetchStates.value = _modelFetchStates.value.toMutableMap().apply {
                put(provider, ModelFetchState(isLoading = true))
            }
            var earlyReturn = false
            try {
                val aiService = AiService(getApplication())
                val resolvedProvider = try {
                    ApiProvider.valueOf(provider)
                } catch (e: Exception) {
                    null
                }
                
                // PARTNER 模式下，如果 apiKey 为空，从服务器获取
                var keyToUse = apiKey
                if (resolvedProvider == ApiProvider.PARTNER && keyToUse.isBlank()) {
                    SecureLog.d("SettingsViewModel", "PARTNER fetchModels: fetching keys from remote server...")
                    val remoteKeys = com.lianyu.ai.common.RemoteKeyProvider.fetchKeysAsync(getApplication(), forceRefresh = true)
                    if (remoteKeys.isNotEmpty()) {
                        keyToUse = remoteKeys.first()
                        SecureLog.d("SettingsViewModel", "Using remote key for fetchModels: ${keyToUse.take(8)}...")
                    } else {
                        _modelFetchStates.value = _modelFetchStates.value.toMutableMap().apply {
                            put(provider, ModelFetchState(errorMessage = "无法从服务器获取密钥"))
                        }
                        earlyReturn = true
                    }
                }
                if (!earlyReturn) {
                val result = aiService.fetchModels(baseUrl, keyToUse, resolvedProvider, skipCertVerify)
                result.onSuccess { models ->
                    _fetchedModels.value = _fetchedModels.value.toMutableMap().apply {
                        put(provider, models)
                    }
                    _modelFetchStates.value = _modelFetchStates.value.toMutableMap().apply {
                        put(provider, ModelFetchState())
                    }
                }.onFailure { error ->
                    _modelFetchStates.value = _modelFetchStates.value.toMutableMap().apply {
                        put(provider, ModelFetchState(errorMessage = error.message ?: "模型列表获取失败"))
                    }
                }
                }
            } catch (e: Exception) {
                _modelFetchStates.value = _modelFetchStates.value.toMutableMap().apply {
                    put(provider, ModelFetchState(errorMessage = e.message ?: "模型列表获取失败"))
                }
            }
        }
    }

    fun queryBalance(configId: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _balanceQueryFailed.value = false
            val aiService = AiService(getApplication())
            val result = if (configId != null) {
                aiService.queryBalance(configId)
            } else {
                val activeConfig = aiService.getActiveConfig()
                if (activeConfig != null) {
                    aiService.queryBalance(activeConfig.id.takeIf { it > 0 })
                } else {
                    val builtinConfig = ApiConfig(
                        provider = ApiProvider.PARTNER,
                        apiKey = "",
                        baseUrl = ApiProvider.PARTNER.defaultBaseUrl,
                        model = ApiProvider.PARTNER.defaultModel
                    )
                    aiService.queryBalanceWithConfig(builtinConfig)
                }
            }
            result.onSuccess { balance ->
                _balanceInfo.value = balance
                _balanceQueryFailed.value = false
            }.onFailure {
                _balanceInfo.value = null
                _balanceQueryFailed.value = true
            }
        }
    }

    fun queryBalanceForConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            _balanceQueryFailed.value = false
            val aiService = AiService(getApplication())
            aiService.queryBalanceWithConfig(config)
                .onSuccess { balance ->
                    _balanceInfo.value = balance
                    _balanceQueryFailed.value = false
                }.onFailure {
                    _balanceInfo.value = null
                    _balanceQueryFailed.value = true
                }
        }
    }

    fun startGemmaDownload() {
        viewModelScope.launch {
            localModelProvider?.let { provider ->
                val selected = _modelStates.value.values.find { it.isSelected }?.modelId
                if (selected != null) provider.downloadModel(selected)
            }
        }
    }

    fun downloadModel(modelId: String) {
        viewModelScope.launch {
            localModelProvider?.downloadModel(modelId)
        }
    }

    fun cancelGemmaDownload() {
        viewModelScope.launch {
            localModelProvider?.let { provider ->
                val selected = _modelStates.value.values.find { it.isSelected }?.modelId
                if (selected != null) provider.cancelDownload(selected)
            }
        }
    }

    fun enableGemma() {
        viewModelScope.launch {
            localModelProvider?.let { provider ->
                val selected = _modelStates.value.values.find { it.isSelected }?.modelId
                if (selected != null) provider.enableModel(selected)
            }
        }
    }

    fun disableGemma() {
        viewModelScope.launch {
            localModelProvider?.let { provider ->
                val selected = _modelStates.value.values.find { it.isSelected }?.modelId
                if (selected != null) provider.disableModel(selected)
            }
        }
    }

    fun deleteGemma() {
        viewModelScope.launch {
            localModelProvider?.let { provider ->
                val selected = _modelStates.value.values.find { it.isSelected }?.modelId
                if (selected != null) provider.deleteModel(selected)
            }
        }
    }

    fun refreshLocalModel() {
        viewModelScope.launch {
            localModelProvider?.let { provider ->
                _modelStates.value = provider.getAllModelStates()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}