package com.lianyu.ai.security

import android.app.Application
import android.content.Context
import com.lianyu.ai.LianYuApplication

/**
 * Shell Application — VMP first, dev fallback.
 *
 * Production (build_shell_apk.py):
 *   Shell DEX has only this class + NativeBridge + OnePieceShellGate.
 *   NativeBridge.nativeLoadPayload() decrypts business DEX from SO payload,
 *   injects InMemoryDexClassLoader, bootstraps LianYuApplication.
 *
 * Dev (Gradle assembleRelease):
 *   Native SO absent → falls back to direct LianYuApplication loading.
 *   NativeBridge is never referenced unless VMP payload exists.
 */
class LianYuShellApplication : Application(), androidx.work.Configuration.Provider {

    private var realApp: LianYuApplication? = null

    override val workManagerConfiguration: androidx.work.Configuration
        get() = realApp?.workManagerConfiguration ?: androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN).build()

    // ── VMP 路径：仅在 payload SO 存在时激活 ──
    // 提前检查 SO 文件，避免 Class.forName 触发 System.loadLibrary → JNI_OnLoad → ptrace SIGABRT
    private val vmpAvailable: Boolean by lazy {
        try {
            val libPath = baseContext?.applicationInfo?.nativeLibraryDir + "/liblianyu_security.so"
            java.io.File(libPath).exists()
        } catch (_: Exception) { false }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        if (vmpAvailable && attachViaVmp(base)) return

        attachViaReflection(base)
    }

    override fun onCreate() {
        super.onCreate()
        realApp?.onCreate()
    }

    override fun onTerminate() { super.onTerminate(); realApp?.onTerminate() }
    override fun onLowMemory() { super.onLowMemory(); realApp?.onLowMemory() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); realApp?.onTrimMemory(level) }

    // ── 私用 ──

    private fun attachViaVmp(base: Context): Boolean {
        return runCatching {
            val rc = NativeBridge.nativeLoadPayload(base, "com.lianyu.ai.LianYuApplication")
            if (rc != 0) return false

            val loader = NativeBridge.sDexClassLoader ?: return false
            injectClassLoader(base, loader)

            val appClass = NativeBridge.sRealAppClass ?: return false
            realApp = appClass.getDeclaredConstructor().newInstance() as LianYuApplication
            invokeAttach(realApp!!, base)
            true
        }.getOrDefault(false)
    }

    private fun attachViaReflection(base: Context) {
        realApp = LianYuApplication()
        invokeAttach(realApp!!, base)
    }

    private fun invokeAttach(app: Application, base: Context) {
        Application::class.java
            .getDeclaredMethod("attach", Context::class.java)
            .apply { isAccessible = true }
            .invoke(app, base)
    }

    private fun injectClassLoader(base: Context, loader: ClassLoader) {
        val loadedApk = runCatching {
            base.javaClass.getDeclaredField("mLoadedApk").apply { isAccessible = true }.get(base)
        }.getOrElse {
            base.javaClass.getDeclaredField("mPackageInfo").apply { isAccessible = true }.get(base)
        }
        loadedApk.javaClass
            .getDeclaredField("mClassLoader")
            .apply { isAccessible = true }
            .set(loadedApk, loader)
    }
}


/**
 * Shell gate — lightweight security preflight.
 * In production VMP builds, integrity is verified during nativeLoadPayload.
 */
object OnePieceShellGate {
    fun recordStartupPreflight(context: android.content.Context) {}
    fun verifyBeforePayload(context: android.content.Context) {}
}
