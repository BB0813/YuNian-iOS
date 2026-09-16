package com.yunian.ai

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.yunian.ai.common.AppForegroundTracker
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.common.ContentFilter
import com.yunian.ai.common.DeviceIdProvider
import com.yunian.ai.common.HardwareInfo
import com.yunian.ai.common.PerformanceTrace
import com.yunian.ai.common.RomUtils
import com.yunian.ai.common.perf.PerfBoost
import com.yunian.ai.common.SaltStore
import com.yunian.ai.common.SafetyClassifier
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.embedding.VectorLibrary
import com.yunian.ai.common.safety.ContentSafetyVerifier
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.DefaultCompanionSeeder
import com.yunian.ai.database.SecurityDataSeeder
import com.yunian.ai.database.repository.ApiConfigRepository
import com.yunian.ai.database.repository.AppMetaStore
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.database.repository.GroupMessageRepository
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.database.repository.EmbeddingProvider
import com.yunian.ai.database.repository.SummaryProvider
import com.yunian.ai.database.repository.DiaryProvider
import com.yunian.ai.database.repository.UnifiedMemoryRepository
import com.yunian.ai.database.repository.UserRepository
import com.yunian.ai.database.timeline.RoomTimelineStore
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.YandereModeManager
import com.yunian.ai.domain.CompanionProvider
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.ImageGenerationProvider
import com.yunian.ai.domain.CoffeeOrderProvider
import com.yunian.ai.domain.BuiltinCloudAccessPolicy
import com.yunian.ai.domain.imagegen.ImageGenService
import com.yunian.ai.domain.LocalModelProvider
import com.yunian.ai.domain.LorebookProvider
import com.yunian.ai.domain.McpManager
import com.yunian.ai.domain.MemoryProvider
import com.yunian.ai.domain.PlaceholderProvider
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.SkillManager
import com.yunian.ai.domain.UserProfileProvider
import com.yunian.ai.domain.timeline.TimelinePayloadCodecRegistry
import com.yunian.ai.domain.timeline.TimelineStore
import com.yunian.ai.domain.wechat.WeChatDialoguePort
import com.yunian.ai.domain.wechat.WeChatIdentityMapPort
import com.yunian.ai.domain.wechat.WeChatOutboundPort
import com.yunian.ai.wechat.WeChatDialoguePortImpl
import com.yunian.ai.wechat.WeChatIdentityMapPortImpl
import com.yunian.ai.wechat.WeChatOutboundPortImpl

import com.yunian.ai.feature.automation.data.AutomationStore
import com.yunian.ai.feature.chat.tools.SearchTools
import com.yunian.ai.feature.notification.NotificationHelper
import com.yunian.ai.push.PushManager
import com.yunian.ai.feature.wechat.service.WeChatChannelKeeper
import com.yunian.ai.feature.wechat.service.WeChatNotificationHelper
import com.yunian.ai.network.AiService
import com.yunian.ai.network.NtpTimeProvider
import com.yunian.ai.security.G0
import com.yunian.ai.security.NativeBridge
import com.yunian.ai.security.SecurityState
import android.content.ComponentCallbacks2
import com.yunian.ai.uicommon.component.ChatBackgroundCache
import com.yunian.ai.uicommon.component.getChatBackgroundKey
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

class YuNianApplication : Application(), ImageLoaderFactory, androidx.work.Configuration.Provider {

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
        // 冷启动时基：进程内最早可达点（加固壳 preflight 之后、业务代码最早处）。
        PerformanceTrace.startLaunch()
        PerformanceTrace.markStartupStage("app_attach_begin")
        super.attachBaseContext(base)
    }

        override fun onCreate() {
        PerformanceTrace.markStartupStage("app_oncreate_begin")

        java.security.Security.setProperty("networkaddress.cache.ttl", "0")
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0")
        System.setProperty("sun.net.spi.nameservice.nameservers", "8.8.8.8")
        System.setProperty("sun.net.spi.nameservice.domain", ".")
        PerformanceTrace.markStartupStage("oncreate_props_done")
        super.onCreate()
        PerformanceTrace.markStartupStage("oncreate_super_done")
        instance = this

        AppForegroundTracker.init()
        PerformanceTrace.markStartupStage("app_fgt_init_done")
        initBusiness(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStoredLanguage(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val imageLoader = Coil.imageLoader(this)
        when (level) {

            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                imageLoader.memoryCache?.clear()
                com.yunian.ai.database.cache.MessageCache.clearAll()
                ChatBackgroundCache.clear()
            }

            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                imageLoader.memoryCache?.clear()
                ChatBackgroundCache.clear()
            }

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

            AppDatabase.shutdown()
            ChatBackgroundCache.clear()
            com.yunian.ai.database.cache.HomeListCache.clear()
        }
        ServiceRegistry.clear()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: YuNianApplication
            private set

        private val bgScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)
        fun initBusiness(app: Application) {
            PerformanceTrace.markStartupStage("initbusiness_begin")
            SaltStore.init(app)
            PerformanceTrace.markStartupStage("ib_salt")
            SecureLog.init(com.yunian.ai.BuildConfig.DEBUG)
            PerformanceTrace.markStartupStage("ib_securelog")
            // ADPF（Performance Hint API）：注入应用上下文并捕获主线程 native tid。
            // 运行在主线程，幂等，内部全部 runCatching；API < 31 / 服务缺失时自动降级 no-op。
            runCatching { PerfBoost.init(app) }
            PerformanceTrace.markStartupStage("ib_perfboost")
            applyStoredLanguage(app)
            PerformanceTrace.markStartupStage("ib_language")
            AiService.initialize(app)
            PerformanceTrace.markStartupStage("ib_aiservice")
            NtpTimeProvider.initialize(app)
            PerformanceTrace.markStartupStage("ib_ntp")
            clearUpdateIgnore(app)
            PerformanceTrace.markStartupStage("ib_clear_update")

            com.yunian.ai.common.ApplicationScopeProvider.init(bgScope)
            PerformanceTrace.markStartupStage("ib_scopeprovider")
            PerformanceTrace.markStartupStage("initbusiness_sync_done")

            // T02 · 预热硬件档位：把 tier 冷探测（getprop ×N + sysfs 读，典型数十毫秒）移出主线程。
            // PageTransitions 在「转场起帧那一帧」读取 HardwareInfo.tier，若此时才首次探测会阻塞主线程；
            // 启动即后台预热后，首次导航只命中缓存。幂等，且不改变同设备档位。
            bgScope.launch { HardwareInfo.warmUp() }

            bgScope.launch {
                // ── 放行屏障（T01′）──────────────────────────────────────────────────────
                //   首页唯一的硬依赖是「provider 已注册 + DB 已打开」：
                //     · HomeViewModel 构造时 getOrThrow(CompanionRepository/ChatRepository)；
                //     · AppDatabase.getDatabase(app) 打开 DB。
                //   registerServiceProviders(app) 完成（或抛异常）后**立即**翻转 initialized 放行 UI，
                //   不再与后面那串重 IO（repairUserAvatar / verifyAndRecover / seed / warm / hydrateRecent）
                //   绑成同一个门控——原先这里白等 ~800ms 才让首页出现。
                //   异常处理与原有语义保持一致：无论注册是否成功都放行（finally），避免 UI 永久卡 loading。
                try {
                    registerServiceProviders(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SecureLog.e("YuNianApplication", "Service initialization failed", e)
                } finally {
                    PerformanceTrace.markStartupStage("service_registry_ready")
                    ServiceRegistry.markInitialized()
                }

                // ── 以下全部在「放行」之后，不再阻挡首帧 ──────────────────────────────────

                // T01′：Automation 重排（原 registerServiceProviders 内的
                //   runBlocking { AutomationStore.list() + AutomationScheduler.rescheduleAll }）
                //   移出放行屏障，放到独立协程执行 → 不再占用屏障耗时。
                //   保持原有执行语义（仍要执行 + runCatching + 失败上报）；
                //   此刻 AutomationStore 已在 registerServiceProviders 中注册完毕。
                bgScope.launch {
                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            val automations = ServiceRegistry.getOrThrow(AutomationStore::class.java).list()
                            com.yunian.ai.feature.automation.AutomationScheduler.rescheduleAll(app, automations)
                        }
                    }.onFailure {
                        SecureLog.e("YuNianApplication", "Automation rescheduleAll failed", it)
                    }
                }

                runCatching {
                    ServiceRegistry.getOrThrow(UserRepository::class.java).repairUserAvatar(app)
                }.onFailure {
                    SecureLog.e("YuNianApplication", "Repair user avatar failed", it)
                }
                runCatching { AppDatabase.verifyAndRecover(app) }
                    .onFailure { SecureLog.e("YuNianApplication", "Database verification failed", it) }
                // seedDefaultCompanion 内部有 Mutex 幂等保护；此处补异常隔离，
                // 保证后续项不会因它抛异常而被跳过（与拆分前由外层 catch 兜底的行为一致）。
                try {
                    seedDefaultCompanion(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SecureLog.e("YuNianApplication", "Seed default companion failed", e)
                }
                runCatching {
                    com.yunian.ai.database.cache.HomeListCache.warm(AppDatabase.getDatabase(app))
                }.onFailure {
                    SecureLog.e("YuNianApplication", "HomeListCache warm failed", it)
                }
                runCatching {
                    val lastOpenedId = LastOpenedCompanionStore.get(app)
                    if (lastOpenedId > 0L) {
                        ServiceRegistry.getOrThrow(ChatRepository::class.java)
                            .hydrateRecent(lastOpenedId, com.yunian.ai.common.ChatConstants.CHAT_PAGE_SIZE)
                    }
                }.onFailure {
                    SecureLog.e("YuNianApplication", "Pre-warm last-opened chat cache failed", it)
                }
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
            PerformanceTrace.markStartupStage("ib_safety_classifier")
            bgScope.launch { initSafetyVerifier(app) }

            com.yunian.ai.database.cleanup.DataCleanupManager.schedulePeriodicCleanup(app)
            PerformanceTrace.markStartupStage("ib_cleanup_schedule")
            bgScope.launch { com.yunian.ai.database.cleanup.DataCleanupManager.cleanupIfNeeded(app) }
            PerformanceTrace.markStartupStage("initbusiness_done")
        }

        private suspend fun initYandereMode(app: Application) {
            try {
                if (AppSettingsStore(app).getYandereModeEnabled()) {
                    ServiceRegistry.getOrThrow(YandereModeManager::class.java).start()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.e("YuNianApplication", "initYandereMode failed", e)
            }
        }

        private fun preloadBackground(app: Application) {
            ChatBackgroundCache.preload(app, getChatBackgroundKey(app))
        }

        private suspend fun seedDefaultCompanion(app: Application) {
            DefaultCompanionSeeder.seedIfNeeded(app)
        }

        private suspend fun warmRecentChatCaches(app: Application) {
            runCatching {
                val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)
                val groupRepository = ServiceRegistry.getOrThrow(GroupMessageRepository::class.java)
                val lastOpenedId = LastOpenedCompanionStore.get(app)
                val summaries = com.yunian.ai.database.cache.HomeListCache.snapshotChatSummaries()
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
                        chatRepository.hydrateRecent(companionId, com.yunian.ai.common.ChatConstants.CHAT_PAGE_SIZE)
                    }.onFailure {
                        SecureLog.e("YuNianApplication", "hydrate chat $companionId failed", it)
                    }
                }

                val groupId = AppDatabase.getDatabase(app)
                    .conversationSummaryDao()
                    .getSummariesByTypeSync("group")
                    .maxByOrNull { it.lastMessageTimestamp }
                    ?.sessionId
                if (groupId != null && groupId > 0L) {
                    runCatching {
                        groupRepository.hydrateRecent(
                            groupId,
                            com.yunian.ai.common.ChatConstants.GROUP_CHAT_MESSAGE_LIMIT
                        )
                    }.onFailure {
                        SecureLog.e("YuNianApplication", "hydrate group $groupId failed", it)
                    }
                }
            }.onFailure {
                SecureLog.e("YuNianApplication", "warmRecentChatCaches failed", it)
            }
        }

        private suspend fun initWeChat(app: Application) {

            NotificationHelper.createNotificationChannel(app)
            WeChatNotificationHelper.createChannel(app)
            SecureLog.d("YuNianApplication", "ROM: ${RomUtils.getRomDisplayName()} ${RomUtils.romVersion}")

            runCatching { PushManager.init(app) }

            runCatching { WeChatChannelKeeper.ensureRunning(app) }
        }

        private suspend fun initSecurityData(app: Application) {
            SecurityDataSeeder.seedIfNeeded(app)
        }

        private fun autoBackupDatabase(app: Application) {

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
            val appSettings = AppSettingsStore(app)
            ServiceRegistry.registerSingleton(BuiltinCloudAccessPolicy::class.java) {
                object : BuiltinCloudAccessPolicy {
                    override fun isBuiltinCloudAccessAllowed(): Boolean {

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

            TimelinePayloadCodecRegistry.registerBuiltins()
            ServiceRegistry.registerSingleton(TimelineStore::class.java) {
                RoomTimelineStore(database.messageDao(), database)
            }

            ServiceRegistry.registerSingleton(EmbeddingProvider::class.java) {
                com.yunian.ai.network.EmbeddingService(app)
            }

            ServiceRegistry.registerSingleton(SummaryProvider::class.java) {
                com.yunian.ai.network.SummaryService(app)
            }

            ServiceRegistry.registerSingleton(DiaryProvider::class.java) {
                com.yunian.ai.network.DiaryService(app)
            }

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

            ServiceRegistry.registerSingleton(LocalModelProvider::class.java) {
                com.yunian.ai.feature.localmodel.LocalModelProviderImpl(app)
            }
            ServiceRegistry.registerSingleton(UserProfileProvider::class.java) {

                com.yunian.ai.feature.profile.UserProfileProviderImpl(
                    app,
                    ServiceRegistry.getOrThrow(UserRepository::class.java)
                )
            }
            ServiceRegistry.registerSingleton(CompanionProvider::class.java) {
                com.yunian.ai.feature.companion.CompanionProviderImpl(app)
            }

            ServiceRegistry.registerSingleton(MemoryProvider::class.java) {
                com.yunian.ai.feature.memory.engine.UnifiedMemoryProvider(
                    app,
                    DeviceIdProvider.getDeviceId(app),
                    ServiceRegistry.getOrThrow(EmbeddingProvider::class.java),
                    ServiceRegistry.getOrThrow(SummaryProvider::class.java)
                )
            }

            ServiceRegistry.registerSingleton(PlaceholderProvider::class.java) {
                com.yunian.ai.common.PlaceholderResolver(app)
            }

            ServiceRegistry.registerSingleton(LorebookProvider::class.java) {
                com.yunian.ai.feature.worldbook.repository.WorldbookRepository(
                    AppDatabase.getDatabase(app)
                )
            }

            ServiceRegistry.registerSingleton(McpManager::class.java) {
                com.yunian.ai.feature.mcp.McpManagerImpl(appSettings)
            }

            ServiceRegistry.registerSingleton(SkillManager::class.java) {
                com.yunian.ai.feature.skills.repository.SkillManagerImpl(app)
            }
            ServiceRegistry.registerSingleton(AiServiceProvider::class.java) {
                AiService(app)
            }

            // AI 生图：独立 OkHttpClient，与聊天/流式链路隔离（生图耗时长，不共享超时）
            ServiceRegistry.registerSingleton(ImageGenerationProvider::class.java) {
                com.yunian.ai.network.ImageGenerationService(app)
            }

            // AI 生图编排：feature:chat 实现，微信/QQ 桥接链路通过它复用同一套判定逻辑
            ServiceRegistry.registerSingleton(ImageGenService::class.java) {
                com.yunian.ai.feature.chat.imagegen.ImageGenServiceImpl(app)
            }

            ServiceRegistry.registerSingleton(WeChatDialoguePort::class.java) {
                WeChatDialoguePortImpl(app)
            }

            ServiceRegistry.registerSingleton(WeChatIdentityMapPort::class.java) {
                WeChatIdentityMapPortImpl(app)
            }

            ServiceRegistry.registerSingleton(WeChatOutboundPort::class.java) {
                WeChatOutboundPortImpl(app)
            }
            ServiceRegistry.registerSingleton(YandereModeManager::class.java) {
                YandereModeManager(app)
            }

            ServiceRegistry.registerSingleton(AppMetaStore::class.java) {
                AppMetaStore(AppDatabase.getDatabase(app).appMetaDao())
            }

            ServiceRegistry.registerSingleton(CoffeeOrderProvider::class.java) {
                com.yunian.ai.feature.coffee.CoffeeOrderProviderImpl(app)
            }

            com.yunian.ai.feature.coffee.LuckinCoffeeTools.registerAll(
                ServiceRegistry.getOrThrow(CoffeeOrderProvider::class.java)
            )

            com.yunian.ai.feature.memory.MemoryRecallTools.registerAll(
                ServiceRegistry.getOrThrow(MemoryProvider::class.java)
            )

            com.yunian.ai.feature.chat.tools.SearchTools.registerAll(appSettings)

            bgScope.launch {
                com.yunian.ai.feature.mcp.McpToolRegistrar(
                    ServiceRegistry.getOrThrow(McpManager::class.java)
                ).syncTools()
            }

            com.yunian.ai.feature.skills.tools.registerSkillTools(
                ServiceRegistry.getOrThrow(SkillManager::class.java)
            )
            // 技能市场：AI 自主搜技能并安装（SkillHub 国内直连优先，GitHub/jsDelivr 兜底）
            com.yunian.ai.feature.skills.tools.registerSkillMarketTools(
                app,
                ServiceRegistry.getOrThrow(SkillManager::class.java)
            )
            // 设备接管工具（打开应用/网页、剪贴板、闹钟、通知、电量、时间）
            com.yunian.ai.feature.skills.tools.registerDeviceTools(app)
            // AI 控制手机：无障碍读屏/点击/滑动（操作类需用户确认）
            com.yunian.ai.feature.skills.tools.registerAccessibilityTools()
            // Shizuku 特权通道状态检测
            com.yunian.ai.feature.skills.tools.registerShizukuTools(app)
            // 刷新技能索引：把可用技能清单注入系统提示词，AI 才会自主调用 use_skill
            bgScope.launch {
                runCatching {
                    com.yunian.ai.feature.skills.tools.refreshSkillIndex(
                        ServiceRegistry.getOrThrow(SkillManager::class.java)
                    )
                }.onFailure { SecureLog.e("YuNianApplication", "Skill index refresh failed", it) }
            }

            com.yunian.ai.feature.chat.tools.ConversationTools.registerAll(
                AppDatabase.getDatabase(app)
            )

            ServiceRegistry.registerSingleton(AutomationStore::class.java) {
                com.yunian.ai.feature.automation.data.AutomationStore(app)
            }
            com.yunian.ai.feature.automation.AutomationTools.registerAll(
                ServiceRegistry.getOrThrow(AutomationStore::class.java),
                app
            )

            ServiceRegistry.registerSingleton(com.yunian.ai.domain.AutomationTickProvider::class.java) {
                com.yunian.ai.feature.automation.AutomationTickProviderImpl(app)
            }

            // T01′：原位于此处的
            //   `runBlocking { AutomationStore.list() + AutomationScheduler.rescheduleAll(app, ...) }`
            // 已移出「放行屏障」——改到 initBusiness 的 bgScope 中、ServiceRegistry.markInitialized()
            // 之后的独立协程执行（保持「仍执行 + runCatching + 失败上报」语义，仅不再占用首帧前的屏障耗时）。
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
                    if (!com.yunian.ai.feature.localmodel.LocalAiService.isNativeLibrarySupported) return null
                    val svc = com.yunian.ai.feature.localmodel.LocalAiService.getInstance(appContext)
                    val model = com.yunian.ai.feature.localmodel.LocalModelCatalog.all
                        .firstOrNull { it.modelFile(appContext).exists() }
                        ?: return null
                    svc.setActiveModel(model)
                    com.yunian.ai.feature.localmodel.LocalSafetyClassifier(svc)
                }.getOrElse {
                    SecureLog.e("YuNianApplication", "Lazy safety classifier init failed", it)
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
