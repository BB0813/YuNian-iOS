package com.lianyu.ai.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lianyu.ai.feature.notification.BootReceiver

/** Shell-facing broadcast receiver for boot restore of notification scheduling. */
class SReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        OnePieceShellGate.verifyBeforePayload(context)
        BootReceiver().onReceive(context, intent)
    }
}
