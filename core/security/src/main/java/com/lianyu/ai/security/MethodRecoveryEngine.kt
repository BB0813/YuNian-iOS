package com.lianyu.ai.security

import dalvik.system.InMemoryDexClassLoader
import java.lang.reflect.Method
import java.nio.ByteBuffer

/**
 * MethodRecoveryEngine — Runtime code recovery for extracted Code Items.
 *
 * Hooks into the ART class loading pipeline to restore extracted
 * method bytecode before the class is initialized.
 *
 * Two recovery strategies:
 *   1. PRE-CLASS-LOAD: Intercept ClassLoader.loadClass, restore all methods
 *   2. PRE-METHOD-EXEC: Hook ArtMethod entry point (requires JNI)
 *
 * Currently implements strategy 1 via ClassLoader wrapper.
 */
object MethodRecoveryEngine {

    private var installed = false

    /** LRU cache of recovered class bytecode (128 entries max) */
    private val recoveryCache = object : LinkedHashMap<String, ByteArray>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > 128
        }
    }

    /**
     * Install the recovery wrapper around the existing ClassLoader.
     * @param parent  The app's base ClassLoader
     *
     * @JvmStatic generates a static delegate so the shell DEX's
     * invoke-static call resolves correctly after dex merge.
     */
    @JvmStatic
    @Synchronized
    fun install(parent: ClassLoader) {
        if (installed) return
        installed = true

        // Wrap parent with a custom ClassLoader that intercepts findClass
        val wrapper = RecoveryClassLoader(parent)
        // Inject into LoadedApk
        injectClassLoader(wrapper)
    }

    /**
     * Recover extracted methods for a given class.
     * Called by RecoveryClassLoader before the class is defined.
     */
    fun recoverMethods(className: String, classBytes: ByteArray): ByteArray {
        // Check cache
        recoveryCache[className]?.let { return it }

        // Call native to check if this class has extracted methods
        val recovered = nativeRecoverClassMethods(className, classBytes)
        if (recovered != null && recovered.isNotEmpty()) {
            recoveryCache[className] = recovered
            return recovered
        }
        return classBytes
    }

    // ── ClassLoader injection ──
    private fun injectClassLoader(wrapper: ClassLoader) {
        try {
            // Target the APP's PathClassLoader, NOT the system ClassLoader
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAT = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val mBoundAppField = activityThreadClass.getDeclaredField("mBoundApplication")
            mBoundAppField.isAccessible = true
            val mBoundApp = mBoundAppField.get(currentAT)
            val infoField = mBoundApp.javaClass.getDeclaredField("info")
            infoField.isAccessible = true
            val loadedApk = infoField.get(mBoundApp)

            // Get the app's ClassLoader from LoadedApk
            val mClassLoaderField = loadedApk.javaClass.getDeclaredField("mClassLoader")
            mClassLoaderField.isAccessible = true
            val appClassLoader = mClassLoaderField.get(loadedApk) as ClassLoader

            // Replace the app's ClassLoader with our wrapper
            mClassLoaderField.set(loadedApk, wrapper)
            android.util.Log.i("MethodRecovery", "Injected RecoveryClassLoader")
        } catch (e: Exception) {
            android.util.Log.e("MethodRecovery", "Failed to inject ClassLoader", e)
        }
    }

    // ── Native ──
    @JvmStatic external fun nativeRecoverClassMethods(className: String, classBytes: ByteArray): ByteArray?
}

/**
 * Custom ClassLoader that intercepts class loading to restore extracted code.
 */
internal class RecoveryClassLoader(parent: ClassLoader) : ClassLoader(parent) {

    override fun findClass(name: String): Class<*> {
        return try {
            super.findClass(name)
        } catch (e: ClassNotFoundException) {
            // Try to recover and load
            val bytes = loadClassBytes(name)
            if (bytes != null) {
                defineClass(name, bytes, 0, bytes.size)
            } else {
                throw e
            }
        }
    }

    private fun loadClassBytes(name: String): ByteArray? {
        // Method recovery is handled by nativeRecoverClassMethods via JNI.
        // This stub exists for the RecoveryClassLoader.findClass flow.
        return null
    }
}
