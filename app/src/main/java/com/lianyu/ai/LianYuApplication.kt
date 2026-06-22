package com.lianyu.ai

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.lianyu.ai.common.ContentFilter
import com.lianyu.ai.common.RomUtils
import com.lianyu.ai.common.SaltStore
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.embedding.VectorLibrary
import com.lianyu.ai.common.safety.ContentSafetyVerifier
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.DefaultCompanionSeeder
import com.lianyu.ai.database.SecurityDataSeeder
import com.lianyu.ai.domain.CompanionProvider
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.domain.LocalModelProvider
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.domain.UserProfileProvider

import com.lianyu.ai.feature.notification.NotificationHelper
import com.lianyu.ai.push.PushManager
import com.lianyu.ai.feature.wechat.data.WeChatTokenStore
import com.lianyu.ai.feature.wechat.service.WeChatNotificationHelper
import com.lianyu.ai.feature.wechat.service.WeChatPollingService
import com.lianyu.ai.feature.wechat.service.WeChatPollingWorker
import com.lianyu.ai.network.AiService
import com.lianyu.ai.network.NtpTimeProvider
import com.lianyu.ai.security.G0
import com.lianyu.ai.security.SecurityState
import com.lianyu.ai.uicommon.component.ChatBackgroundCache
import com.lianyu.ai.uicommon.component.getChatBackgroundKey
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class LianYuApplication : Application(), ImageLoaderFactory {

    private val _startupState = MutableStateFlow<AppStartupState>(AppStartupState.CriticalInit)
    val startupState: StateFlow<AppStartupState> = _startupState.asStateFlow()

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this).maxSizeBytes(128 * 1024 * 1024).build()
        }
        .build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }

        override fun onCreate() {
        // Global DNS resolution timeout (5s) — prevents DNS hang blocking all OkHttp calls
        java.security.Security.setProperty("networkaddress.cache.ttl", "0")
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0")
        System.setProperty("sun.net.spi.nameservice.nameservers", "8.8.8.8")
        System.setProperty("sun.net.spi.nameservice.domain", ".")
        super.onCreate()
        instance = this
        initBusiness(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStoredLanguage(this)
    }

    override fun onTerminate() {
        bgScope.launch {
            ContentFilter.destroy()
            SaltStore.shutdown()
            // EncryptedDatabaseWrapper.sealDatabase(this@LianYuApplication)
            AppDatabase.shutdown()
            ChatBackgroundCache.clear()
        }
        ServiceRegistry.clear()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: LianYuApplication
            private set

        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun initBusiness(app: Application) {
            SaltStore.init(app)
            SecureLog.init(false)
            applyStoredLanguage(app)
            AiService.initialize(app)
            NtpTimeProvider.initialize(app)
            registerServiceProviders(app)
            clearUpdateIgnore(app)

            // 注入应用级后台作用域，供跨越 ViewModel 生命周期的任务使用
            com.lianyu.ai.common.ApplicationScopeProvider.init(bgScope)

            // Seed default companion synchronously — must exist before any chat opens
            seedDefaultCompanion(app)

            bgScope.launch { ContentFilter.initialize(app) }
            bgScope.launch { preloadBackground(app) }
            bgScope.launch { initWeChat(app) }
            bgScope.launch { initSecurityData(app) }
            bgScope.launch { autoBackupDatabase(app) }
            bgScope.launch { initVectorLibrary(app) }
            bgScope.launch { initSafetyClassifier(app) }
            bgScope.launch { initSafetyVerifier(app) }
        }

        private fun preloadBackground(app: Application) {
            ChatBackgroundCache.preload(app, getChatBackgroundKey(app))
        }

        private fun seedDefaultCompanion(app: Application) {
            DefaultCompanionSeeder.seedIfNeeded(app)
        }

        private suspend fun initWeChat(app: Application) {
            // 提前创建通知渠道，避免 OPPO/vivo 首次通知被系统折叠或延迟。
            NotificationHelper.createNotificationChannel(app)
            WeChatNotificationHelper.createChannel(app)
            SecureLog.d("LianYuApplication", "ROM: ${RomUtils.getRomDisplayName()} ${RomUtils.romVersion}")
            // 初始化厂商 Push SDK，提升 OPPO / vivo / 小米 / 华为 设备的消息到达率
            runCatching { PushManager.init(app) }
            val tokenStore = WeChatTokenStore(app)
            if (runCatching { tokenStore.isLoggedIn() }.getOrDefault(false)) {
                WeChatPollingService.start(app)
                WeChatPollingWorker.schedule(app)
            }
        }

        private suspend fun initSecurityData(app: Application) {
            SecurityDataSeeder.seedIfNeeded(app)
        }

        private fun autoBackupDatabase(app: Application) {
            runCatching { AppDatabase.autoBackupIfNeeded(app.applicationContext) }
        }

        private fun initVectorLibrary(app: Application) {
            runCatching {
                app.assets.open("safety/violation_vectors.bin").use { it.readBytes() }
                    .let { VectorLibrary.Loader().load(it) }
                    ?.let { ContentFilter.setVectorLibrary(it) }
            }
        }

        private fun initSafetyClassifier(app: Application) {
            runCatching {
                if (!com.lianyu.ai.feature.localmodel.LocalAiService.isNativeLibrarySupported) return
                val svc = com.lianyu.ai.feature.localmodel.LocalAiService.getInstance(app)
                com.lianyu.ai.feature.localmodel.LocalModelCatalog.all.firstOrNull { it.modelFile(app).exists() }
                    ?.let { svc.setActiveModel(it); ContentFilter.setSafetyClassifier(com.lianyu.ai.feature.localmodel.LocalSafetyClassifier(svc)) }
            }
        }

        private suspend fun initSafetyVerifier(app: Application) {
            runCatching {
                ContentSafetyVerifier.init(app)
                val keywords = SecurityDataSeeder.getEnabledKeywords(app)
                if (keywords.isNotEmpty()) {
                    ContentSafetyVerifier.bootstrap(keywords)
                }
            }
        }

        private fun registerServiceProviders(app: Application) {
            ServiceRegistry.register(LocalModelProvider::class.java) {
                com.lianyu.ai.feature.localmodel.LocalModelProviderImpl(app)
            }
            ServiceRegistry.register(UserProfileProvider::class.java) {
                com.lianyu.ai.feature.profile.UserProfileProviderImpl(app)
            }
            ServiceRegistry.register(CompanionProvider::class.java) {
                com.lianyu.ai.feature.companion.CompanionProviderImpl(app)
            }
            ServiceRegistry.register(AiServiceProvider::class.java) {
                AiService(app)
            }
        }

        private fun clearUpdateIgnore(app: Application) {
            app.getSharedPreferences("update_config", android.content.Context.MODE_PRIVATE)
                .edit().remove("ignored_version").apply()
        }

        private fun applyStoredLanguage(app: Application) {
            Locale.setDefault(
                when (app.getSharedPreferences("language_prefs", android.content.Context.MODE_PRIVATE)
                    .getString("language", "zh-CN") ?: "zh-CN"
                ) {
                    "zh-TW" -> Locale.TRADITIONAL_CHINESE
                    "en" -> Locale.ENGLISH
                    "ja" -> Locale.JAPANESE
                    "ko" -> Locale.KOREAN
                    else -> Locale.SIMPLIFIED_CHINESE
                }
            )
        }
    }
}
