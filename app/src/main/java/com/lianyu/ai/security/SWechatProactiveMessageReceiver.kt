package com.lianyu.ai.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lianyu.ai.feature.wechat.service.WeChatProactiveMessageReceiver

/** Shell-facing receiver for proactive WeChat message sync. */
class SWechatProactiveMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        OnePieceShellGate.verifyBeforePayload(context)
        val pendingResult = goAsync()
        WeChatProactiveMessageReceiver.handleReceive(context, intent, pendingResult)
    }
}
