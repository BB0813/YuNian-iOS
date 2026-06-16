package com.lianyu.ai.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lianyu.ai.feature.wechat.service.WeChatBootReceiver

/** Shell-facing boot receiver for WeChat. */
class SWechatBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        OnePieceShellGate.verifyBeforePayload(context)
        WeChatBootReceiver().onReceive(context, intent)
    }
}
