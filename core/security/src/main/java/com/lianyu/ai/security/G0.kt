package com.lianyu.ai.security

import android.content.Context

/** Neutral facade for the app-facing security gate. */
object G0 {
    fun a(context: Context) = SecurityGuard.init(context)
    fun b(context: Context) = SecurityGuard.productionPreflight(context)
    fun c(context: Context): Boolean = SecurityGuard.isSafe(context)
    fun d(context: Context): SecurityGuard.CryptoLevel = SecurityGuard.getCryptoLevel(context)
}
