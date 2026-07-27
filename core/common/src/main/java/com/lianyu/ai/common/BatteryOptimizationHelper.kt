package com.lianyu.ai.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 电池优化与自启动设置辅助类。
 *
 * 已针对 OPPO / vivo / 小米 / 华为 / 三星 / 一加等厂商做设置页精确跳转，
 * 优先尝试 ROM 新版本路径，失败自动回退旧版路径或应用详情页。
 */
object BatteryOptimizationHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return NativePermissionRequester.isIgnoringBatteryOptimizations(context)
    }

    /**
     * 请求加入电池优化白名单。
     *
     * - 已在白名单：打开设置页，便于用户核对/修改（避免 REQUEST 静默 no-op）
     * - 标准 Android：弹系统确认框（需 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）
     * - OriginOS / IQOO / 华为：系统常拦截弹窗，走多路径设置页兜底
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) {
            openBatteryOptimizationSettings(context)
            return
        }
        if (RomUtils.isVivo || RomUtils.isHuawei) {
            // OriginOS 等可能静默拦截 REQUEST 弹窗，优先走专用多路径
            if (!OriginOSBatteryOptimizer.openBatteryOptimizationSettings(context)) {
                NativePermissionRequester.requestIgnoreBatteryOptimizations(context)
            }
            return
        }
        NativePermissionRequester.requestIgnoreBatteryOptimizations(context)
    }

    /**
     * 打开电池优化相关设置页（不弹 REQUEST 确认框）。
     * 用于已授权后再次点击、或需要用户手动核对白名单状态的场景。
     */
    fun openBatteryOptimizationSettings(context: Context) {
        if (RomUtils.isVivo || RomUtils.isHuawei) {
            OriginOSBatteryOptimizer.openBatteryOptimizationSettings(context)
            return
        }
        if (RomUtils.isOppo) {
            OppoVivoAdaptationHelper.openBatteryOptimizationSettings(context)
            return
        }
        NativePermissionRequester.openBatteryOptimizationSettings(context)
    }

    /**
     * 打开自启动 / 应用启动管理设置页。
     */
    fun openAutoStartSettings(context: Context) {
        if (RomUtils.isOppoOrVivo()) {
            OppoVivoAdaptationHelper.openAutoStartSettings(context)
            return
        }

        val intent = Intent().apply {
            when {
                RomUtils.isXiaomi -> {
                    component = android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                }
                RomUtils.isHuawei -> {
                    component = android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                }
                Build.MANUFACTURER.equals("samsung", ignoreCase = true) -> {
                    component = android.content.ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"
                    )
                }
                Build.MANUFACTURER.equals("oneplus", ignoreCase = true) -> {
                    component = android.content.ComponentName(
                        "com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                    )
                }
                else -> {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.parse("package:${context.packageName}")
                }
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        safeStartActivity(context, intent)
    }

    /**
     * 打开后台高耗电 / 耗电保护 / 神隐模式等设置页。
     * OPPO / vivo 使用专门适配路径。
     */
    fun openBackgroundPowerSettings(context: Context) {
        if (RomUtils.isOppoOrVivo()) {
            OppoVivoAdaptationHelper.openBackgroundPowerSettings(context)
            return
        }
        openAppDetailsSettings(context)
    }

    /**
     * 打开应用详情页（通用兜底）。
     */
    fun openAppDetailsSettings(context: Context) {
        OppoVivoAdaptationHelper.openAppDetailsSettings(context)
    }

    /**
     * 打开通知设置页。
     */
    fun openNotificationSettings(context: Context) {
        OppoVivoAdaptationHelper.openNotificationSettings(context)
    }

    private fun safeStartActivity(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings(context)
        }
    }
}
