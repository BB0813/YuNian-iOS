package com.lianyu.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.lianyu.ai.common.PerformanceTrace
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.edit
import com.lianyu.ai.common.BatteryOptimizationHelper
import com.lianyu.ai.common.CompanionRole
import com.lianyu.ai.common.FrameRateManager
import com.lianyu.ai.common.RomUtils
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.feature.notification.CompanionKeepAliveService
import com.lianyu.ai.feature.notification.CompanionMessageWorker
import com.lianyu.ai.feature.profile.AgreementScreen
import com.lianyu.ai.feature.profile.ProfileViewModel
import com.lianyu.ai.feature.profile.RoleSelectionScreen
import com.lianyu.ai.feature.update.AppUpdateManager
import com.lianyu.ai.uicommon.component.LianYuToastHost
import com.lianyu.ai.uicommon.component.WindowMainBackground
import com.lianyu.ai.uicommon.theme.LianYuTheme
import com.lianyu.ai.uicommon.theme.ThemeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 应用主入口 Activity — v2.0 精简版。
 *
 * 职责:
 *   生命周期管理 (onCreate / onResume / onPause / onDestroy)
 *   权限请求 (通知)
 *   系统栏初始化 (委托 SystemBarController)
 *   帧率策略
 *   保活/后台服务启动
 */
class MainActivity : ComponentActivity() {

    val updateManager by lazy { AppUpdateManager(this) }
    private val appScope = CoroutineScope(Dispatchers.Main)
    private var memoryAlertActive = false // 滞环: 触发后需降到阈值以下才解除

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            val prefs = getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            prefs.edit { putBoolean("notification_permission_denied", true) }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(SystemBarController.applyBaseContextLocale(newBase))
    }

    
    override fun onCreate(savedInstanceState: Bundle?) {
        lastOnCreateStartedNanos = SystemClock.elapsedRealtimeNanos()
        PerformanceTrace.startStartup(lastOnCreateStartedNanos)
        super.onCreate(savedInstanceState)
        // WorkManager must be initialized before any schedule() call
        try { androidx.work.WorkManager.initialize(this, androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()) } catch (_: Exception) {}
        enableEdgeToEdge()
        window.decorView.post { SystemBarController.applySystemBars(this) }

        // 高帧率优先
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.let { disp ->
                val preferredMode = disp.supportedModes
                    .filter { it.refreshRate >= 90f }
                    .maxByOrNull { it.refreshRate }
                    ?: disp.supportedModes.maxByOrNull { it.refreshRate }
                preferredMode?.let { mode ->
                    val lp = window.attributes
                    lp.preferredDisplayModeId = mode.modeId
                    window.attributes = lp
                }
            }
        }

        // 硬件加速
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // 异形屏适配
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // 窗口层主背景：主题 windowBackground 兜底 + 运行时用户选择
        WindowMainBackground.applyFromPrefs(this)

        // 用户协议检查
        val activity = this
        setContent {
            val agreementPrefs = getSharedPreferences("agreement_prefs", android.content.Context.MODE_PRIVATE)
            val agreementAccepted = agreementPrefs.getBoolean("agreement_accepted", false)
            val userPrefs = getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
            val roleSelected = userPrefs.contains("selected_role")
            val themeViewModel: ThemeViewModel = viewModel()
            val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
            val profileViewModel: ProfileViewModel = viewModel()
            var showRoleSelection by remember { mutableStateOf(agreementAccepted && !roleSelected) }

            val isServiceReady by ServiceRegistry.initialized.collectAsStateWithLifecycle()

            LianYuTheme(themeMode = themeMode) {
                // 根 Surface 透明：主背景由 window 层绘制，避免 Compose 根层盖住 windowBackground
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            !agreementAccepted -> {
                                AgreementScreen(
                                    onAgree = {
                                        agreementPrefs.edit()
                                            .putBoolean("agreement_accepted", true)
                                            .putLong("agreement_time", System.currentTimeMillis())
                                            .apply()
                                        activity.recreate()
                                    },
                                    onDisagree = { activity.finishAffinity() }
                                )
                            }
                            showRoleSelection -> {
                                RoleSelectionScreen(
                                    onRoleSelected = { role ->
                                        profileViewModel.switchRole(role) {
                                            showRoleSelection = false
                                        }
                                    },
                                    onSkip = {
                                        profileViewModel.switchRole(CompanionRole.GIRLFRIEND) {
                                            showRoleSelection = false
                                        }
                                    }
                                )
                            }
                            !isServiceReady -> {
                                // 等待跨模块依赖注册中心就绪，避免冷启动后快速进入
                                // 创建人设等页面时 ServiceRegistry.getOrThrow 抛异常导致闪退。
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            else -> {
                                MainScreen(activity)
                            }
                        }
                        // 全局产品 Toast：运营/配置/网络错误统一通道，不污染聊天消息库
                        LianYuToastHost(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 56.dp)
                                .zIndex(100f)
                        )
                    }
                }
            }
        }

        // 后台服务 + 权限
        requestNotificationPermission()
        CompanionKeepAliveService.start(this)
        CompanionMessageWorker.schedule(this)
        // JobScheduler 第三层兜底保活（全设备启用：进程被杀后由系统作业拉起恢复，
        // 覆盖 WorkManager 在 Doze 下延迟的窗口；原生 Android / 国产 ROM 通用）
        scheduleIqooKeepAliveJob()
        // AlarmManager 心跳：Doze 下唯一可靠的进程外唤醒通道（精确闹钟 + while-idle 降级）
        KeepAliveAlarmScheduler.scheduleNext(this)

        appScope.launch { updateManager.checkForUpdates() }
        startMemoryMonitor()
    }

    /**
     * JobScheduler 第三层保活调度（每 15 分钟）。
     *
     * 进程被杀后，WorkManager 在 Doze 下可能延迟数分钟；JobScheduler 由系统作业服务
     * 托管，作为独立恢复通道。作业运行期允许后台启动 FGS（Android 12+ 豁免），
     * 因此能真正拉起已死的保活/轮询服务。
     */
    private fun scheduleIqooKeepAliveJob() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val jobScheduler = getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE)
                        as android.app.job.JobScheduler
                jobScheduler.cancel(IQOO_KEEP_ALIVE_JOB_ID)
                val componentName = android.content.ComponentName(this, IqooKeepAliveJobService::class.java)
                val builder = android.app.job.JobInfo.Builder(IQOO_KEEP_ALIVE_JOB_ID, componentName)
                    .setPeriodic(15 * 60 * 1000L)
                    .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    builder.setPriority(android.app.job.JobInfo.PRIORITY_HIGH)
                }
                jobScheduler.schedule(builder.build())
                android.util.Log.i("MainActivity", "IQOO keep-alive JobScheduler scheduled")
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Failed to schedule IQOO keep-alive job: ${e.message}")
            }
        }
    }

    companion object {
        private const val IQOO_KEEP_ALIVE_JOB_ID = 10001

        @Volatile
        var lastOnCreateStartedNanos: Long = 0L
            private set
    }

    /**
     * 内存管理控制回路 — 每3秒检查一次内存使用率。
     * 滞环: 触发 85% 后需降到 60% 以下才解除。
     * CRITICAL(>90%) 时清理 L1 列表/消息缓存；HIGH 仅告警，避免聊天中途闪空。
     */
    private fun startMemoryMonitor() {
        appScope.launch {
            val runtime = Runtime.getRuntime()
            val maxMem = runtime.maxMemory()
            while (isActive) {
                delay(3000)
                val used = runtime.totalMemory() - runtime.freeMemory()
                val ratio = used.toFloat() / maxMem.toFloat()

                if (ratio > 0.90f) {
                    android.util.Log.w(
                        "MemoryMonitor",
                        "CRITICAL: ${(ratio * 100).toInt()}% — clearing MessageCache/HomeListCache"
                    )
                    runCatching {
                        com.lianyu.ai.database.cache.MessageCache.clearAll()
                        com.lianyu.ai.database.cache.HomeListCache.clear()
                    }
                    memoryAlertActive = true
                } else if (ratio > 0.85f && !memoryAlertActive) {
                    android.util.Log.w(
                        "MemoryMonitor",
                        "HIGH: ${(ratio * 100).toInt()}% — pressure elevated (no cache clear yet)"
                    )
                    memoryAlertActive = true
                } else if (ratio < 0.60f && memoryAlertActive) {
                    android.util.Log.i("MemoryMonitor", "RECOVERED: ${(ratio * 100).toInt()}%")
                    memoryAlertActive = false
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 前后台由 AppForegroundTracker + ProcessLifecycleOwner 统一维护
        // 回到前台时补拉微信通道（FGS 被系统掐断后的自愈）
        appScope.launch(Dispatchers.IO) {
            runCatching {
                com.lianyu.ai.feature.wechat.service.WeChatChannelKeeper.ensureRunning(applicationContext)
            }
        }
        window.decorView.post {
            SystemBarController.applySystemBars(this)
            val savedRate = FrameRateManager.getSavedFrameRate(this)
            FrameRateManager.applyFrameRate(window, savedRate)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        appScope.cancel()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {}
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    val prefs = getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                    val deniedBefore = prefs.getBoolean("notification_permission_denied", false)
                    if (!deniedBefore) {
                        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }

}
