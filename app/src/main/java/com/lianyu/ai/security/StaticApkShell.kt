package com.lianyu.ai.security

import android.app.Application
import android.content.Context

class StaticApkShell : Application() {

    init {
        try { System.loadLibrary("lianyu_shell") }
        catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("StaticApkShell", "liblianyu_shell.so not found", e)
        }
    }

    override fun attachBaseContext(base: Context) {
        nativeAntiHookInit()

        try {
            val blobBytes = base.assets.open("lianyu_shell/code_items.bin").use { it.readBytes() }
            nativeShellInitWithBlob(blobBytes)
        } catch (e: Exception) { }

        MethodRecoveryEngine.install(base.classLoader)
        OatDisabler.disable(base)
        nativeEnableMemoryGuard()

        try {
            val g0Class = Class.forName("com.lianyu.ai.security.G0")
            g0Class.getMethod("b", Context::class.java).invoke(null, base)
        } catch (e: Exception) { }

        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()

        // ── G0 security init ──
        try {
            val g0Class = Class.forName("com.lianyu.ai.security.G0")
            g0Class.getMethod("a", Application::class.java).invoke(null, this)
        } catch (e: Exception) { }

        // ── Business init (delegated to LianYuApplication) ──
        com.lianyu.ai.LianYuApplication.initBusiness(this)
    }

    // ── JNI ──
    external fun nativeShellInitWithBlob(blob: ByteArray): Int
    external fun nativeEnableMemoryGuard()
    external fun nativeAntiHookInit()
}
