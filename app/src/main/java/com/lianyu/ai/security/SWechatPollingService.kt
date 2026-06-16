package com.lianyu.ai.security

import android.content.Intent
import com.lianyu.ai.feature.wechat.service.WeChatPollingService

/** Shell-facing service for the WeChat polling entry point. */
class SWechatPollingService : WeChatPollingService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        OnePieceShellGate.verifyBeforePayload(this)
        return super.onStartCommand(intent, flags, startId)
    }
}
