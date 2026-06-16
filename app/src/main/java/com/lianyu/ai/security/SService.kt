package com.lianyu.ai.security

import android.content.Intent
import com.lianyu.ai.feature.notification.CompanionKeepAliveService

/** Shell-facing service for the companion keep-alive entry point. */
class SService : CompanionKeepAliveService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        OnePieceShellGate.verifyBeforePayload(this)
        return super.onStartCommand(intent, flags, startId)
    }
}
