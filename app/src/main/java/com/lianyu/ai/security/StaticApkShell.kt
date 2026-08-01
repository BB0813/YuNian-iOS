package com.lianyu.ai.security

import android.app.Application
import android.content.Context
import com.lianyu.ai.common.PerformanceTrace

/**
 * Kotlin shell Application used by normal Gradle assemble (debug / plain release).
 *
 * Production release packaging replaces the root DEX with the pure-Java twin at
 * app/src/shell/java/.../StaticApkShell.java via tools/package_thin_shell.py.
 * Keep both implementations behaviorally aligned on:
 * - native anti-hook / shell blob / memory guard
 * - G0 preflight + runtime init
 */
class StaticApkShell : Application(), androidx.work.Configuration.Provider {

    init {
        try { System.loadLibrary("lianyu_shell") }
        catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("StaticApkShell", "liblianyu_shell.so not found", e)
        }
    }

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    override fun attachBaseContext(base: Context) {
        PerformanceTrace.startShell()
        nativeAntiHookInit()
        PerformanceTrace.markShellAntiHookDone()

        // VMP blob (lianyu_shell/code_items.bin) is a legacy one-piece shell payload
        // that was removed from the repo: production thin-shell packaging no longer
        // ships it, and the release shell (StaticApkShell.java) skips VMP init to
        // avoid multi-second main-thread I/O. Keep nativeShellInitWithBlob for
        // backward-compatible APKs that still embed the blob, but treat absence as
        // non-fatal (no tampered marking) so debug builds boot cleanly.
        runCatching {
            val blobBytes = base.assets.open("lianyu_shell/code_items.bin").use { it.readBytes() }
            nativeShellInitWithBlob(blobBytes)
        }.onFailure {
            android.util.Log.w("StaticApkShell", "VMP blob not present; skipping native shell init", it)
        }
        PerformanceTrace.markShellNativeInitDone()

        MethodRecoveryEngine.install(base.classLoader)
        OatDisabler.disable(base)
        PerformanceTrace.markShellRecoveryDone()
        nativeEnableMemoryGuard()
        PerformanceTrace.markShellMemoryGuardDone()

        try {
            val g0Class = Class.forName("com.lianyu.ai.security.G0")
            g0Class.getMethod("b", Context::class.java).invoke(null, base)
        } catch (e: Exception) {
            // Preflight exceptions are soft; hard auth is decided inside SecurityGuard.
            SecurityState.markTampered("startup preflight threw: ${e.javaClass.simpleName}")
        }
        PerformanceTrace.markShellPreflightDone()

        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()

        // ── G0 security init ──
        PerformanceTrace.startSecurity()
        try {
            val g0Class = Class.forName("com.lianyu.ai.security.G0")
            g0Class.getMethod("a", Application::class.java).invoke(null, this)
        } catch (e: Exception) {
            // Runtime security init failure is soft for offline-first local business.
            SecurityState.markTampered("security runtime init failed: ${e.javaClass.simpleName}")
        }
        PerformanceTrace.markSecurityDone()
        PerformanceTrace.persistReleaseMetrics(this)
        logSecurityPerformance()

        com.lianyu.ai.LianYuApplication.initBusiness(this)
    }

    // ── JNI ──
    external fun nativeShellInitWithBlob(blob: ByteArray): Int
    external fun nativeEnableMemoryGuard()
    external fun nativeAntiHookInit()

    private fun logSecurityPerformance() {
        val metrics = PerformanceTrace.shellMetricsNanos() + PerformanceTrace.securityMetricsNanos()
        android.util.Log.i(
            "LianYuReleasePerformance",
            metrics.entries.joinToString { (name, nanos) -> "$name=${nanos / 1_000_000.0}ms" }
        )
    }
}
