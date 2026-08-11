package com.lianyu.ai

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import com.lianyu.ai.common.RomUtils
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.feature.notification.CompanionKeepAliveService
import com.lianyu.ai.feature.notification.CompanionMessageWorker

/**
 * JobScheduler 第三层兜底保活（全设备）。
 *
 * 进程被杀后由系统作业服务拉起，恢复保活服务 + 微信/QQ 通道。
 * 作业运行期允许后台启动 FGS（Android 12+ 豁免），覆盖 WorkManager
 * 在 Doze 下延迟的空窗。类名保留历史命名（曾为 IQOO/OriginOS 专用）。
 *
 * 职责：
 * 1. 检查前台服务是否存活，若已死则尝试重启
 * 2. 确保 WorkManager 中有待处理的 CompanionMessageWorker
 * 3. 恢复微信主轮询 FGS / QQ Bot FGS（按登录态）
 * 4. 记录设备级诊断日志
 */
class IqooKeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        SecureLog.i("IqooKeepAliveJobService", "Job started on ${RomUtils.getRomDisplayName()}")

        // JobService 标准异步模式：返回 true 表示工作异步执行，完成后必须调 jobFinished。
        // 裸 Thread 在此场景符合契约（JobScheduler 管理进程生命周期，无 CoroutineJobService 基类）。
        Thread({
            try {
                performKeepAliveCheck()
            } catch (e: Exception) {
                SecureLog.e("IqooKeepAliveJobService", "Keep-alive check failed", e)
            } finally {
                jobFinished(params, false)
            }
        }, "iqoo-keepalive").start()

        return true // 表示工作正在异步执行
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        SecureLog.w("IqooKeepAliveJobService", "Job stopped prematurely by system")
        // 返回 true 表示希望系统重新调度此任务
        return true
    }

    private fun performKeepAliveCheck() {
        val context = applicationContext

        // 1. 检查并尝试重启前台服务
        try {
            CompanionKeepAliveService.safeStart(context)
            SecureLog.d("IqooKeepAliveJobService", "Keep-alive service check passed")
        } catch (e: Exception) {
            SecureLog.w("IqooKeepAliveJobService", "Failed to restart keep-alive service: ${e.message}")
        }

        // 2. 确保 WorkManager 中有待处理任务
        try {
            CompanionMessageWorker.schedule(context)
            SecureLog.d("IqooKeepAliveJobService", "WorkManager check passed")
        } catch (e: Exception) {
            SecureLog.w("IqooKeepAliveJobService", "Failed to schedule WorkManager: ${e.message}")
        }

        // 3. 微信通道恢复：登录态下幂等拉起 FGS 主轮询 + Worker 兜底
        //    （作业运行期允许后台启动 FGS，进程被杀后由本作业复活通道）
        runCatching {
            kotlinx.coroutines.runBlocking {
                com.lianyu.ai.feature.wechat.service.WeChatChannelKeeper.ensureRunning(context)
            }
        }.onFailure {
            SecureLog.w("IqooKeepAliveJobService", "WeChat ensureRunning failed: ${it.message}")
        }

        // 4. QQ 通道恢复：已配置账号才拉起 FGS（避免幽灵通知）
        runCatching {
            val loggedIn = kotlinx.coroutines.runBlocking {
                com.lianyu.ai.feature.qqbot.service.QQBotServiceLocator.tokenStore(context).isLoggedIn()
            }
            if (loggedIn) {
                com.lianyu.ai.feature.qqbot.service.QQBotForegroundService.start(context)
            }
        }.onFailure {
            SecureLog.w("IqooKeepAliveJobService", "QQ FGS start failed: ${it.message}")
        }

        // 5. 记录设备级诊断信息
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            SecureLog.i("IqooKeepAliveJobService", "Battery optimization ignored: $isIgnoring")
        }
    }
}
