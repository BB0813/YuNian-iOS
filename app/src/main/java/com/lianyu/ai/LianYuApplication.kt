package com.lianyu.ai

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.lianyu.ai.common.AppForegroundTracker
import com.lianyu.ai.common.ContentFilter
import com.lianyu.ai.common.DeviceIdProvider
import com.lianyu.ai.common.RomUtils
import com.lianyu.ai.common.SaltStore
import com.lianyu.ai.common.SafetyClassifier
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.embedding.VectorLibrary
import com.lianyu.ai.common.safety.ContentSafetyVerifier
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.DefaultCompanionSeeder
import com.lianyu.ai.database.SecurityDataSeeder
import com.lianyu.ai.database.repository.ApiConfigRepository
import com.lianyu.ai.database.repository.ChatRepository
import com.lianyu.ai.database.repository.CompanionRepository
import com.lianyu.ai.database.repository.GroupMessageRepository
import com.lianyu.ai.database.repository.MemoryRepository
import com.lianyu.ai.database.repository.MessageWriteCoordinator
import com.lianyu.ai.database.repository.EmbeddingProvider
import com.lianyu.ai.database.repository.SummaryProvider
import com.lianyu.ai.database.repository.DiaryProvider
import com.lianyu.ai.database.repository.UnifiedMemoryRepository
import com.lianyu.ai.database.repository.UserRepository
import com.lianyu.ai.database.timeline.RoomTimelineStore
import com.lianyu.ai.common.AppSettingsStore
import com.lianyu.ai.common.YandereModeManager
import com.lianyu.ai.domain.CompanionProvider
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.domain.CoffeeOrderProvider
import com.lianyu.ai.domain.BuiltinCloudAccessPolicy
import com.lianyu.ai.domain.LocalModelProvider
import com.lianyu.ai.domain.MemoryProvider
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.domain.UserProfileProvider
import com.lianyu.ai.domain.timeline.TimelinePayloadCodecRegistry
import com.lianyu.ai.domain.timeline.TimelineStore
import com.lianyu.ai.domain.wechat.WeChatDialoguePort
import com.lianyu.ai.domain.wechat.WeChatIdentityMapPort
import com.lianyu.ai.domain.wechat.WeChatOutboundPort
import com.lianyu.ai.wechat.WeChatDialoguePortImpl
import com.lianyu.ai.wechat.WeChatIdentityMapPortImpl
import com.lianyu.ai.wechat.WeChatOutboundPortImpl

import com.lianyu.ai.feature.automation.data.AutomationStore
import com.lianyu.ai.feature.notification.NotificationHelper
import com.lianyu.ai.push.PushManager
import com.lianyu.ai.feature.wechat.service.WeChatChannelKeeper
import com.lianyu.ai.feature.wechat.service.WeChatNotificationHelper
import com.lianyu.ai.network.AiService
import com.lianyu.ai.network.NtpTimeProvider
import com.lianyu.ai.security.G0
import com.lianyu.ai.security.NativeBridge
import com.lianyu.ai.security.SecurityState
import android.content.ComponentCallbacks2
import com.lianyu.ai.uicommon.component.ChatBackgroundCache
import com.lianyu.ai.uicommon.component.getChatBackgroundKey
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

class LianYuApplication : Application(), ImageLoaderFactory, androidx.work.Configuration.Provider {

    private val _startupState = MutableStateFlow<AppStartupState>(AppStartupState.CriticalInit)
    val startupState: StateFlow<AppStartupState> = _startupState.asStateFlow()

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this).maxSizeBytes(128 * 1024 * 1024).build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(cacheDir, "coil_images"))
                .maxSizeBytes(150L * 1024 * 1024)
                .build()
        }
        .build()

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
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
        // 进程级前后台：尽早绑定，供 Worker / 微信轮询判断
        AppForegroundTracker.init()
        initBusiness(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStoredLanguage(this)
    }

    /**
     * 多级内存回收：根据系统压力逐级释放缓存。
     * 目标设备 ≥4GB，但仍做最优适配：
     * - CRITICAL / LOW：清空所有重量缓存
     * - MODERATE / BACKGROUND：仅清 Coil 图片内存缓存
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val imageLoader = Coil.imageLoader(this)
        when (level) {
            // 系统濒临 OOM — 清空全部缓存
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                imageLoader.memoryCache?.clear()
                com.lianyu.ai.database.cache.MessageCache.clearAll()
                ChatBackgroundCache.clear()
            }
            // 内存压力大 — 清除图片和背景缓存，保留消息缓存（LruCache 自有淘汰）
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                imageLoader.memoryCache?.clear()
                ChatBackgroundCache.clear()
            }
            // 中等压力 / 切到后台 — 仅清理 Coil 内存缓存
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                imageLoader.memoryCache?.clear()
            }
            else -> {}
        }
    }

    override fun onTerminate() {
        bgScope.launch {
            ContentFilter.destroy()
            SaltStore.shutdown()
            // EncryptedDatabaseWrapper.sealDatabase(this@LianYuApplication)
            AppDatabase.shutdown()
            ChatBackgroundCache.clear()
            com.lianyu.ai.database.cache.HomeListCache.clear()
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
            SecureLog.init(com.lianyu.ai.BuildConfig.DEBUG)
            applyStoredLanguage(app)
            AiService.initialize(app)
            NtpTimeProvider.initialize(app)
            clearUpdateIgnore(app)

            // 注入应用级后台作用域，供跨越 ViewModel 生命周期的任务使用
            com.lianyu.ai.common.ApplicationScopeProvider.init(bgScope)

            bgScope.launch {
                registerServiceProviders(app)
                runCatching { AppDatabase.verifyAndRecover(app) }
                    .onFailure { SecureLog.e("LianYuApplication", "Database verification failed", it) }
                seedDefaultCompanion(app)
                // 必须在 markInitialized 之前完成：MainActivity 以 ServiceRegistry 就绪为进入主界面门槛
                runCatching {
                    com.lianyu.ai.database.cache.HomeListCache.warm(AppDatabase.getDatabase(app))
                }.onFailure {
                    SecureLog.e("LianYuApplication", "HomeListCache warm failed", it)
                }
                // 预热上次打开的聊天消息缓存，必须在 markInitialized 之前完成
                // 确保用户进入聊天页时 MessageCache L1 直接命中，消除首帧空白闪烁
                runCatching {
                    val lastOpenedId = LastOpenedCompanionStore.get(app)
                    if (lastOpenedId > 0L) {
                        ServiceRegistry.getOrThrow(ChatRepository::class.java)
                            .hydrateRecent(lastOpenedId, com.lianyu.ai.common.ChatConstants.CHAT_PAGE_SIZE)
                    }
                }.onFailure {
                    SecureLog.e("LianYuApplication", "Pre-warm last-opened chat cache failed", it)
                }
                ServiceRegistry.markInitialized()
                // 主界面就绪后再后台预热最近会话消息，避免阻塞首屏
                bgScope.launch { warmRecentChatCaches(app) }
                initYandereMode(app)
            }

            bgScope.launch { ContentFilter.initialize(app) }
            bgScope.launch { preloadBackground(app) }
            bgScope.launch { initWeChat(app) }
            bgScope.launch { initSecurityData(app) }
            bgScope.launch { autoBackupDatabase(app) }
            bgScope.launch { initVectorLibrary(app) }
            ContentFilter.setSafetyClassifier(LazyLocalSafetyClassifier(app))
            bgScope.launch { initSafetyVerifier(app) }
            // 三级存储架构：注册定期数据清理任务
            com.lianyu.ai.database.cleanup.DataCleanupManager.schedulePeriodicCleanup(app)
            bgScope.launch { com.lianyu.ai.database.cleanup.DataCleanupManager.cleanupIfNeeded(app) }
        }

        private suspend fun initYandereMode(app: Application) {
            try {
                if (AppSettingsStore(app).getYandereModeEnabled()) {
                    ServiceRegistry.getOrThrow(YandereModeManager::class.java).start()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.e("LianYuApplication", "initYandereMode failed", e)
            }
        }

        private fun preloadBackground(app: Application) {
            ChatBackgroundCache.preload(app, getChatBackgroundKey(app))
        }

        private suspend fun seedDefaultCompanion(app: Application) {
            DefaultCompanionSeeder.seedIfNeeded(app)
        }

        /**
         * 后台预热最近会话的消息 L1 缓存。
         * 不阻塞 markInitialized / 首屏；进入聊天页时可直接命中 MessageCache。
         */
        private suspend fun warmRecentChatCaches(app: Application) {
            runCatching {
                val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)
                val groupRepository = ServiceRegistry.getOrThrow(GroupMessageRepository::class.java)
                val lastOpenedId = LastOpenedCompanionStore.get(app)
                val summaries = com.lianyu.ai.database.cache.HomeListCache.snapshotChatSummaries()
                    .sortedByDescending { it.lastMessageTimestamp }
                val companionIds = buildList {
                    if (lastOpenedId > 0L) add(lastOpenedId)
                    summaries.asSequence()
                        .map { it.sessionId }
                        .filter { it > 0L && it != lastOpenedId }
                        .forEach { add(it) }
                }.distinct().take(2)
                companionIds.forEach { companionId ->
                    runCatching {
                        chatRepository.hydrateRecent(companionId, com.lianyu.ai.common.ChatConstants.CHAT_PAGE_SIZE)
                    }.onFailure {
                        SecureLog.e("LianYuApplication", "hydrate chat $companionId failed", it)
                    }
                }
                // 群聊：预热最近 1 个有摘要的群（若有）
                val groupId = AppDatabase.getDatabase(app)
                    .conversationSummaryDao()
                    .getSummariesByTypeSync("group")
                    .maxByOrNull { it.lastMessageTimestamp }
                    ?.sessionId
                if (groupId != null && groupId > 0L) {
                    runCatching {
                        groupRepository.hydrateRecent(
                            groupId,
                            com.lianyu.ai.common.ChatConstants.GROUP_CHAT_MESSAGE_LIMIT
                        )
                    }.onFailure {
                        SecureLog.e("LianYuApplication", "hydrate group $groupId failed", it)
                    }
                }
            }.onFailure {
                SecureLog.e("LianYuApplication", "warmRecentChatCaches failed", it)
            }
        }

        private suspend fun initWeChat(app: Application) {
            // 提前创建通知渠道，避免 OPPO/vivo 首次通知被系统折叠或延迟。
            NotificationHelper.createNotificationChannel(app)
            WeChatNotificationHelper.createChannel(app)
            SecureLog.d("LianYuApplication", "ROM: ${RomUtils.getRomDisplayName()} ${RomUtils.romVersion}")
            // 初始化厂商 Push SDK，提升 OPPO / vivo / 小米 / 华为 设备的消息到达率
            runCatching { PushManager.init(app) }
            // 登录态下统一拉起 FGS 主轮询 + WM 周期/立即兜底
            runCatching { WeChatChannelKeeper.ensureRunning(app) }
        }

        private suspend fun initSecurityData(app: Application) {
            SecurityDataSeeder.seedIfNeeded(app)
        }

        private fun autoBackupDatabase(app: Application) {
            // [M6 FIX] 备份涉及文件 IO，已从 AppDatabase.buildDatabase 主路径移除，
            // 在此异步执行，不阻塞首屏渲染。同时顺带清理过期备份。
            runCatching { AppDatabase.autoBackupIfNeeded(app.applicationContext) }
            runCatching { AppDatabase.clearOldBackups(app.applicationContext) }
        }

        private fun initVectorLibrary(app: Application) {
            runCatching {
                app.assets.open("safety/violation_vectors.bin").use { it.readBytes() }
                    .let { VectorLibrary.Loader().load(it) }
                    ?.let { ContentFilter.setVectorLibrary(it) }
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
            ServiceRegistry.registerSingleton(BuiltinCloudAccessPolicy::class.java) {
                object : BuiltinCloudAccessPolicy {
                    override fun isBuiltinCloudAccessAllowed(): Boolean {
                        // Refresh the client risk tier from the native threshold
                        // model before deciding, so the risk gate is current.
                        SecurityState.updateRiskLevel()
                        val snap = SecurityState.snapshot()
                        val locked = NativeBridge.zeroTrustIsLocked()
                        return snap.isTrustedForSensitiveOps && locked == 0
                    }

                    override fun denialReason(): String? {
                        return SecurityState.snapshot().reason ?: "zero trust verification incomplete"
                    }
                }
            }
            // ── Repository 单例注册 ──
            // 统一 Repository 获取方式，供 QQ Bot 等跨模块消费者通过 ServiceRegistry 获取。
            val database = AppDatabase.getDatabase(app)
            ServiceRegistry.registerSingleton(CompanionRepository::class.java) {
                CompanionRepository(database.companionDao())
            }
            ServiceRegistry.registerSingleton(ApiConfigRepository::class.java) {
                ApiConfigRepository(database.apiConfigDao(), database.apiProviderPresetDao())
            }
            ServiceRegistry.registerSingleton(ChatRepository::class.java) {
                ChatRepository(database.messageDao(), database.conversationSummaryDao(), database)
            }
            ServiceRegistry.registerSingleton(GroupMessageRepository::class.java) {
                GroupMessageRepository(database.messageDao(), database.conversationSummaryDao(), database)
            }
            ServiceRegistry.registerSingleton(MessageWriteCoordinator::class.java) {
                MessageWriteCoordinator(
                    ServiceRegistry.getOrThrow(ChatRepository::class.java),
                    ServiceRegistry.getOrThrow(GroupMessageRepository::class.java),
                    bgScope
                )
            }
            // 助手时间线：REASONING 等终态事件落库（与 ChatRepository 并存）
            TimelinePayloadCodecRegistry.registerBuiltins()
            ServiceRegistry.registerSingleton(TimelineStore::class.java) {
                RoomTimelineStore(database.messageDao(), database)
            }
            ServiceRegistry.registerSingleton(MemoryRepository::class.java) {
                MemoryRepository(database.memoryDao(), DeviceIdProvider.getDeviceId(app))
            }
            // 语义嵌入服务（Phase 3: 本地语义检索）
            // 在 UnifiedMemoryRepository 之前注册，因为后者需要 EmbeddingProvider
            ServiceRegistry.registerSingleton(EmbeddingProvider::class.java) {
                com.lianyu.ai.network.EmbeddingService(app)
            }
            // 对话摘要服务（Phase 4: 对话摘要压缩）
            // 在 UnifiedMemoryRepository 之前注册，因为后者需要 SummaryProvider
            ServiceRegistry.registerSingleton(SummaryProvider::class.java) {
                com.lianyu.ai.network.SummaryService(app)
            }
            // 日记生成服务（AI 根据对话历史生成真人风格日记）
            ServiceRegistry.registerSingleton(DiaryProvider::class.java) {
                com.lianyu.ai.network.DiaryService(app)
            }
            // 统一记忆仓库（现代化记忆系统，基于 Room unified_memories 表）
            ServiceRegistry.registerSingleton(UnifiedMemoryRepository::class.java) {
                UnifiedMemoryRepository(
                    AppDatabase.getDatabase(app).unifiedMemoryDao(),
                    DeviceIdProvider.getDeviceId(app),
                    ServiceRegistry.getOrThrow(EmbeddingProvider::class.java),
                    ServiceRegistry.getOrThrow(SummaryProvider::class.java)
                )
            }
            ServiceRegistry.registerSingleton(UserRepository::class.java) {
                UserRepository(app)
            }

            // ── 跨 feature 服务接口注册 ──
            ServiceRegistry.registerSingleton(LocalModelProvider::class.java) {
                com.lianyu.ai.feature.localmodel.LocalModelProviderImpl(app)
            }
            ServiceRegistry.registerSingleton(UserProfileProvider::class.java) {
                // 必须复用同一 UserRepository 单例，保证聊天页能实时收到资料变更
                com.lianyu.ai.feature.profile.UserProfileProviderImpl(
                    app,
                    ServiceRegistry.getOrThrow(UserRepository::class.java)
                )
            }
            ServiceRegistry.registerSingleton(CompanionProvider::class.java) {
                com.lianyu.ai.feature.companion.CompanionProviderImpl(app)
            }
            // MemoryProvider：跨会话记忆上下文与提取（feature:memory 实现，core:network/feature:groupchat 消费）
            // 必须在 AiService 之前注册，因为 AiService.init 会通过 ServiceRegistry 获取 MemoryProvider
            // 使用统一记忆提供者（基于 Room unified_memories 表），替代旧的 JSON 文件版 MemoryManager
            ServiceRegistry.registerSingleton(MemoryProvider::class.java) {
                com.lianyu.ai.feature.memory.engine.UnifiedMemoryProvider(
                    app,
                    DeviceIdProvider.getDeviceId(app),
                    ServiceRegistry.getOrThrow(EmbeddingProvider::class.java),
                    ServiceRegistry.getOrThrow(SummaryProvider::class.java)
                )
            }
            ServiceRegistry.registerSingleton(AiServiceProvider::class.java) {
                AiService(app)
            }
            // S3：微信入站对话端口（app 适配现有 AI，禁止 feature:wechat 内嵌 AI 管线）
            ServiceRegistry.registerSingleton(WeChatDialoguePort::class.java) {
                WeChatDialoguePortImpl(app)
            }
            // S5：微信用户 ↔ 伴侣映射端口（设置页 / 通道共用）
            ServiceRegistry.registerSingleton(WeChatIdentityMapPort::class.java) {
                WeChatIdentityMapPortImpl(app)
            }
            // S6：App → 微信出站端口（替代 Broadcast 主路径）
            ServiceRegistry.registerSingleton(WeChatOutboundPort::class.java) {
                WeChatOutboundPortImpl(app)
            }
            ServiceRegistry.registerSingleton(YandereModeManager::class.java) {
                YandereModeManager(app)
            }

            // ── 瑞幸咖啡 MCP 工具（AI 对话可调用） ──
            // CoffeeOrderProvider 暴露给 feature:chat 的 AiTool 实现
            ServiceRegistry.registerSingleton(CoffeeOrderProvider::class.java) {
                com.lianyu.ai.feature.coffee.CoffeeOrderProviderImpl(app)
            }
            // 注册瑞幸工具到 ToolRegistry，供 AiService 随请求一并发给 AI
            com.lianyu.ai.feature.coffee.LuckinCoffeeTools.registerAll(
                ServiceRegistry.getOrThrow(CoffeeOrderProvider::class.java)
            )
            // 注册记忆召回工具，供 AI 在上下文不足时主动查询统一记忆系统
            com.lianyu.ai.feature.memory.MemoryRecallTools.registerAll(
                ServiceRegistry.getOrThrow(MemoryProvider::class.java)
            )
            // ── 自动化工具（AI 对话可创建/取消定时自动化） ──
            ServiceRegistry.registerSingleton(AutomationStore::class.java) {
                com.lianyu.ai.feature.automation.data.AutomationStore(app)
            }
            com.lianyu.ai.feature.automation.AutomationTools.registerAll(
                ServiceRegistry.getOrThrow(AutomationStore::class.java),
                app
            )
            // 启动对账：重建全部启用自动化的 WorkManager 调度
            runCatching {
                kotlinx.coroutines.runBlocking {
                    val automations = ServiceRegistry.getOrThrow(AutomationStore::class.java).list()
                    com.lianyu.ai.feature.automation.AutomationScheduler.rescheduleAll(app, automations)
                }
            }.onFailure {
                SecureLog.e("LianYuApplication", "Automation rescheduleAll failed", it)
            }
            // markInitialized 延后到 seed + HomeListCache.warm 之后，
            // 保证主界面首帧即可拿到联系人/会话快照。
        }

        private fun clearUpdateIgnore(app: Application) {
            app.getSharedPreferences("update_config", android.content.Context.MODE_PRIVATE)
                .edit().remove("ignored_version").apply()
        }

        private class LazyLocalSafetyClassifier(
            app: Application
        ) : SafetyClassifier {
            private val appContext = app.applicationContext
            private var delegate: SafetyClassifier? = null

            override suspend fun classify(text: String): ContentFilter.ViolationLevel {
                val classifier = delegate ?: buildClassifier().also { delegate = it }
                    ?: return ContentFilter.ViolationLevel.NONE
                return classifier.classify(text)
            }

            private fun buildClassifier(): SafetyClassifier? {
                return runCatching {
                    if (!com.lianyu.ai.feature.localmodel.LocalAiService.isNativeLibrarySupported) return null
                    val svc = com.lianyu.ai.feature.localmodel.LocalAiService.getInstance(appContext)
                    val model = com.lianyu.ai.feature.localmodel.LocalModelCatalog.all
                        .firstOrNull { it.modelFile(appContext).exists() }
                        ?: return null
                    svc.setActiveModel(model)
                    com.lianyu.ai.feature.localmodel.LocalSafetyClassifier(svc)
                }.getOrElse {
                    SecureLog.e("LianYuApplication", "Lazy safety classifier init failed", it)
                    null
                }
            }
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
