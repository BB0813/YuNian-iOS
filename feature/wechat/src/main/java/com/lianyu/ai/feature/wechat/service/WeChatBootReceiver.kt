package com.lianyu.ai.feature.wechat.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lianyu.ai.common.SecureLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WeChatBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        // goAsync 延长 BroadcastReceiver 生命周期，避免协程挂起后 pendingResult 失效
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val started = WeChatChannelKeeper.ensureRunning(context.applicationContext)
                SecureLog.i(TAG, "boot/replace action=$action ensureRunning=$started")
            } catch (error: Exception) {
                SecureLog.w(TAG, "boot ensureRunning failed: ${error.message}")
            } finally {
                try {
                    pendingResult.finish()
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        private const val TAG = "WeChatBootReceiver"
    }
}

