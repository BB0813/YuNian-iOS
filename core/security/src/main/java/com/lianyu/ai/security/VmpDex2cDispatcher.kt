package com.lianyu.ai.security

import android.content.Context

/**
 * VmpDex2cDispatcher — Unified method resolution layer (Phase 3).
 *
 * For each security-critical method call, this dispatcher determines the
 * optimal execution path:
 *
 *   DispatchTarget.NATIVE_DEX2C  → JNI native (liblianyu_dex2c.so)
 *   DispatchTarget.VMP_BYTECODE  → CompositeVmpRuntime.execute()
 *   DispatchTarget.ART_FALLBACK  → Standard DEX/ART execution
 *
 * Resolution Order (per method):
 *   1. Check if Dex2C native implementation is available and loaded
 *   2. Fall back to VMP bytecode if the method is registered in VMP
 *   3. Fall back to ART (standard JVM) as last resort
 *
 * Method Resolution Cache:
 *   Uses a HashMap<String, DispatchTarget> to avoid repeated JNI/VMP
 *   lookups on every call. Cache is populated on first call per method.
 *
 * Lifecycle:
 *   - init(): Load liblianyu_dex2c.so, register JNI methods
 *   - resolve(methodKey): Determine dispatch target for a method
 *   - dispatch(target, args): Execute via the resolved target
 */
object VmpDex2cDispatcher {

    /** Dispatch targets in priority order */
    enum class DispatchTarget {
        /** Native C++ (Dex2C-transpiled, fastest/most secure) */
        NATIVE_DEX2C,
        /** Virtual Machine bytecode (obfuscated, medium security) */
        VMP_BYTECODE,
        /** Standard ART/DEX execution (fallback, least secure) */
        ART_FALLBACK
    }

    /** Whether liblianyu_dex2c was successfully loaded */
    @Volatile
    private var dex2cLoaded: Boolean = false

    /** Method resolution cache: methodKey → DispatchTarget */
    private val resolutionCache = HashMap<String, DispatchTarget>()

    /** Set of whitelisted methods from dex2c_whitelist.txt */
    private val dex2cWhitelist: Set<String> = setOf(
        // Trust Anchor: Signature & Integrity
        "NativeBridge.verifySignature",
        "NativeBridge.isSafe",
        // VMP v3.0 Core Security Programs
        "NativeBridge.vmpRootDetect",
        "NativeBridge.vmpCodeIntegrity",
        "NativeBridge.vmpFridaHeartbeat",
        // Environment Detection (Critical)
        "NativeBridge.isDeviceRooted",
        "NativeBridge.isHookDetected",
        "NativeBridge.isEmulator",
        "NativeBridge.isDebugged",
        // KMS Key Management
        "KmsProvider.decryptWithMetadata",
        // Security Orchestration
        "SecurityOrchestrator.encrypt",
        "SecurityOrchestrator.decrypt"
    )

    /** VMP-registered opcodes (methods executable via CompositeVmpRuntime) */
    private val vmpMethods: Set<String> = setOf(
        "NativeBridge.verifySignature",
        "NativeBridge.isSafe",
        "KmsProvider.decryptWithMetadata",
        "NativeBridge.vmpRootDetect",
        "NativeBridge.vmpCodeIntegrity",
        "NativeBridge.vmpSm3Hash",
        "NativeBridge.vmpFridaHeartbeat",
        "NativeBridge.vmpWbAesKeycheck",
        "NativeBridge.vmpKmsDeriveSk",
        "NativeBridge.vmpTeeAttest",
        "NativeBridge.vmpApkSigVerify",
        "NativeBridge.vmpSecureWipe"
    )

    /* ================================================================
     * Initialization
     * ================================================================ */

    /**
     * Initialize the Dex2C dispatcher.
     * Loads liblianyu_dex2c.so if available.
     * Must be called after NativeBridge initialization.
     *
     * @return true if Dex2C native library loaded successfully
     */
    fun init(): Boolean {
        return try {
            System.loadLibrary("lianyu_dex2c")
            dex2cLoaded = true
            if (com.lianyu.ai.security.BuildConfig.DEBUG) {
                android.util.Log.i("VmpDex2c", "Dex2C native library loaded — using native dispatch")
            }
            true
        } catch (e: UnsatisfiedLinkError) {
            dex2cLoaded = false
            if (com.lianyu.ai.security.BuildConfig.DEBUG) {
                android.util.Log.w("VmpDex2c", "Dex2C native library not available — falling back to VMP/ART")
            }
            false
        }
    }

    /** Check if Dex2C native path is available */
    val isDex2cAvailable: Boolean
        get() = dex2cLoaded

    /* ================================================================
     * Method Resolution
     * ================================================================ */

    /**
     * Resolve the dispatch target for a given method key.
     * Method key format: "ClassName.methodName"
     *
     * Priority:
     *   1. Dex2C native (if loaded AND method is whitelisted)
     *   2. VMP bytecode (if method is VMP-registered)
     *   3. ART fallback
     */
    fun resolve(methodKey: String): DispatchTarget {
        // Check cache first
        resolutionCache[methodKey]?.let { return it }

        val target = when {
            dex2cLoaded && methodKey in dex2cWhitelist -> DispatchTarget.NATIVE_DEX2C
            methodKey in vmpMethods -> DispatchTarget.VMP_BYTECODE
            else -> DispatchTarget.ART_FALLBACK
        }

        resolutionCache[methodKey] = target
        return target
    }

    /**
     * Force a specific dispatch target for a method (testing/debug only).
     * Clears cache entry for that method.
     */
    fun overrideTarget(methodKey: String, target: DispatchTarget) {
        resolutionCache[methodKey] = target
    }

    /** Clear the resolution cache (e.g., after library reload) */
    fun clearCache() {
        resolutionCache.clear()
    }

    /* ================================================================
     * Dispatch Execution
     * ================================================================ */

    /**
     * Execute a security method via the resolved dispatch target.
     *
     * @param methodKey Method identifier (e.g., "NativeBridge.verifySignature")
     * @param context Android context (nullable, passed to native and VMP)
     * @param args Additional arguments (varies by method)
     * @return Result object, or null on failure
     */
    fun dispatch(methodKey: String, context: Context? = null, vararg args: Any?): Any? {
        val target = resolve(methodKey)

        return when (target) {
            DispatchTarget.NATIVE_DEX2C -> dispatchNative(methodKey, context, *args)
            DispatchTarget.VMP_BYTECODE -> dispatchVmp(methodKey, context, *args)
            DispatchTarget.ART_FALLBACK -> dispatchArt(methodKey, context, *args)
        }
    }

    /* ── Native (Dex2C) Dispatch ── */

    private fun dispatchNative(methodKey: String, context: Context?, vararg args: Any?): Any? {
        return try {
            when (methodKey) {
                "NativeBridge.verifySignature" ->
                    nativeVerifySignature(context!!)
                "NativeBridge.isSafe" ->
                    nativeIsSafe()
                "KmsProvider.decryptWithMetadata" -> {
                    val ciphertext = args.getOrNull(0) as? ByteArray ?: return null
                    val metadata = args.getOrNull(1) as? ByteArray ?: return null
                    nativeDecryptWithMetadata(ciphertext, metadata)
                }
                else -> {
                    if (com.lianyu.ai.security.BuildConfig.DEBUG) {
                        android.util.Log.w("VmpDex2c", "Unregistered native method: $methodKey")
                    }
                    null
                }
            }
        } catch (e: Exception) {
            if (com.lianyu.ai.security.BuildConfig.DEBUG) {
                android.util.Log.e("VmpDex2c", "Native dispatch failed for $methodKey: ${e.message}")
            }
            // Fall through to VMP on native crash
            dispatchVmp(methodKey, context, *args)
        }
    }

    /* ── VMP Bytecode Dispatch ── */

    private fun dispatchVmp(methodKey: String, context: Context?, vararg args: Any?): Any? {
        return when (methodKey) {
            "NativeBridge.verifySignature" -> {
                // VMP opcode mapping — runtime verification
                CompositeVmpRuntime.verifyApkSignature()
            }
            "NativeBridge.isSafe" -> {
                // VMP opcode mapping — safety check
                NativeBridge.isSafe()
            }
            "KmsProvider.decryptWithMetadata" -> {
                val ciphertext = args.getOrNull(0) as? ByteArray ?: return null
                val metadata = args.getOrNull(1) as? ByteArray ?: return null
                KmsProvider.decryptWithMetadata(ciphertext, metadata)
            }
            else -> {
                if (com.lianyu.ai.security.BuildConfig.DEBUG) {
                    android.util.Log.w("VmpDex2c", "Unregistered VMP method: $methodKey")
                }
                dispatchArt(methodKey, context, *args)
            }
        }
    }

    /* ── ART Fallback Dispatch ── */

    private fun dispatchArt(methodKey: String, context: Context?, vararg args: Any?): Any? {
        if (com.lianyu.ai.security.BuildConfig.DEBUG) {
            android.util.Log.w("VmpDex2c", "ART fallback for: $methodKey")
        }
        return when (methodKey) {
            "NativeBridge.verifySignature" ->
                context?.let { NativeBridge.verifySignature(it) }
            "NativeBridge.isSafe" ->
                NativeBridge.isSafe()
            "KmsProvider.decryptWithMetadata" -> {
                val ciphertext = args.getOrNull(0) as? ByteArray ?: return null
                val metadata = args.getOrNull(1) as? ByteArray ?: return null
                KmsProvider.decryptWithMetadata(ciphertext, metadata)
            }
            else -> null
        }
    }

    /* ================================================================
     * JNI Native Method Declarations (implemented in dex2c_methods.cpp)
     * ================================================================
     * These are registered by liblianyu_dex2c.so JNI_OnLoad.
     * Method names match gDex2cMethods[] in dex2c_methods.cpp.
     */

    /** Native Dex2C implementation of NativeBridge.verifySignature */
    @JvmStatic
    private external fun nativeVerifySignature(context: Context): Boolean

    /** Native Dex2C implementation of NativeBridge.isSafe */
    @JvmStatic
    private external fun nativeIsSafe(): Boolean

    /** Native Dex2C implementation of KmsProvider.decryptWithMetadata */
    @JvmStatic
    private external fun nativeDecryptWithMetadata(ciphertext: ByteArray, metadata: ByteArray): ByteArray?

    /* ================================================================
     * Integration Hooks
     * ================================================================ */

    /**
     * Integrate with CompositeVmpRuntime.execute().
     * Called before VMP execution to check if Dex2C native is preferred.
     *
     * @param methodKey Method to check
     * @return true if Dex2C should handle this method
     */
    fun shouldUseDex2C(methodKey: String): Boolean {
        return resolve(methodKey) == DispatchTarget.NATIVE_DEX2C
    }

    /**
     * Get dispatch statistics for monitoring.
     * Returns map of target → count (from cache analysis).
     */
    fun getStats(): Map<DispatchTarget, Int> {
        val stats = mutableMapOf<DispatchTarget, Int>()
        stats[DispatchTarget.NATIVE_DEX2C] = 0
        stats[DispatchTarget.VMP_BYTECODE] = 0
        stats[DispatchTarget.ART_FALLBACK] = 0
        for ((_, target) in resolutionCache) {
            stats[target] = (stats[target] ?: 0) + 1
        }
        return stats
    }
}
