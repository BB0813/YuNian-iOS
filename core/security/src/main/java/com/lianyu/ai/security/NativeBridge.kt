package com.lianyu.ai.security

import android.content.Context

object NativeBridge {
    @Volatile var tampered: Boolean = false
        private set

    init {
        try { System.loadLibrary("lianyu_security") }
        catch (e: UnsatisfiedLinkError) {
            tampered = true
            android.util.Log.e("NativeBridge", "liblianyu_security.so not found — security features disabled", e)
        }
    }

    // === VMP DEX Packer — static fields set by nativeLoadPayload ===
    @JvmStatic
    var sDexClassLoader: ClassLoader? = null
    @JvmStatic
    var sRealAppClass: Class<*>? = null

    // VMP DEX Packer: decrypt + load the business DEX from native payload
    @JvmStatic
    external fun nativeLoadPayload(context: Context, appClassName: String): Int

    // Original methods
    @JvmStatic
    external fun verifySignature(context: Context): Boolean
    @JvmStatic
    external fun injectAuthHeader(builder: Any)
    @JvmStatic
    external fun getRepoOwner(): String
    @JvmStatic
    external fun getRepoName(): String
    @JvmStatic
    external fun getGitHubApiUrl(): String
    @JvmStatic
    external fun isSafe(): Boolean
    @JvmStatic
    external fun isMitmDetected(): Boolean
    @JvmStatic
    external fun verifyRequestIntegrity(url: String): Boolean

    // Phase 1: Enhanced Environment Detection
    @JvmStatic
    external fun isDeviceRooted(): Boolean
    @JvmStatic
    external fun isHookDetected(): Boolean
    @JvmStatic
    external fun enterDeadLoop()
    @JvmStatic
    external fun nativeGetVmpFingerprint(): Int
    @JvmStatic
    external fun isEmulator(): Boolean
    @JvmStatic
    external fun isDebugged(): Boolean
    @JvmStatic
    external fun getThreatScore(): Int
    @JvmStatic
    external fun resetGuard()

    // Task 2.1: Expanded environment detection
    @JvmStatic
    external fun checkFridaFiles(): Boolean
    @JvmStatic
    external fun checkSelinuxPermissive(): Boolean
    @JvmStatic
    external fun checkBootloader(): Boolean
    @JvmStatic
    external fun checkZygiskModules(): Boolean
    @JvmStatic
    external fun checkLibraryInjection(): Boolean
    @JvmStatic
    external fun checkVirtualEnv(): Boolean
    @JvmStatic
    external fun checkFridaThreads(): Boolean
    @JvmStatic
    external fun getSecureString(id: Int): String
    @JvmStatic
    external fun getFullThreatScore(): Int

    // Phase 3b: VM Engine (bytecode-protected security functions)
    @JvmStatic
    external fun vmRunCheckTracer(): Int
    @JvmStatic
    external fun vmSelftest(): Int

    // VMP v2.0 — Core Security Bytecode Programs (trust-anchor protection)
    @JvmStatic
    external fun vmpTrustAnchorsVerify(): Int  // → 1 if trust anchors valid
    @JvmStatic
    external fun vmpWbAesKeycheck(): Int       // → 1 if WB-AES T-Box integrity OK
    @JvmStatic
    external fun vmpKmsDeriveSk(ctxPtr: Long, ctxLen: Int): Long  // → sk_ptr or 0
    @JvmStatic
    external fun vmpTeeAttest(): Int           // → 1 if TEE available
    @JvmStatic
    external fun vmpApkSigVerify(): Int        // → 1 if APK signature valid

    // VMP v3.0 — Extended Security Bytecode Programs
    @JvmStatic
    external fun vmpRootDetect(): Int           // → 1 if rooted or traced
    @JvmStatic
    external fun vmpCodeIntegrity(expectedCrc: Int): Int  // → 1 if .text CRC32 ok
    @JvmStatic
    external fun vmpSm3Hash(dataPtr: Long, dataLen: Int): Long  // → hash_ptr or 0
    @JvmStatic
    external fun vmpFridaHeartbeat(): Int       // → 0xFFFFFFFF if Frida, else CRC32

    // Anti-debug: ptrace self-attach + inotify /proc/self/maps
    @JvmStatic
    external fun ptraceSelfAttach(): Boolean
    @JvmStatic
    external fun antiDebugInit(): Boolean

    // Zero-Trust Framework JNI
    @JvmStatic
    external fun zeroTrustInit()
    @JvmStatic
    external fun zeroTrustEvaluate(): Int
    @JvmStatic
    external fun zeroTrustGetState(): Int
    @JvmStatic
    external fun zeroTrustGetScore(): Int
    @JvmStatic
    external fun zeroTrustGetScoreBreakdown(): String
    @JvmStatic
    external fun zeroTrustGetRiskLevel(): Int
    @JvmStatic
    external fun zeroTrustIsDegraded(): Int
    @JvmStatic
    external fun zeroTrustIsLocked(): Int
    @JvmStatic
    external fun zeroTrustIsContinuousEvaluationRunning(): Int

    // Phase 2: White-Box AES
    @JvmStatic
    external fun wbAesInit()
    @JvmStatic
    external fun wbAesEncrypt(data: ByteArray): ByteArray?
    @JvmStatic
    external fun wbAesDecrypt(data: ByteArray): ByteArray?
    @JvmStatic
    external fun wbAesSelftest(): Int

    // Phase 3a: L2 obfuscation + side-channel defense
    @JvmStatic
    external fun wbAesObfuscateTables(seed: ByteArray)
    @JvmStatic
    external fun wbAesSideChannelDefense()

    // APK Integrity
    @JvmStatic
    external fun checkDexIntegrity(): Boolean
    @JvmStatic
    external fun checkSoIntegrity(): Boolean
    @JvmStatic
    external fun checkResourcesIntegrity(): Boolean
    @JvmStatic
    external fun computeIntegrityDigest(): ByteArray?

    // Runtime CRC32 heartbeat
    @JvmStatic
    external fun startHeartbeat()
    @JvmStatic
    external fun isHeartbeatOk(): Boolean
    @JvmStatic
    external fun isFridaDetected(): Boolean
    @JvmStatic
    external fun isTracerDetected(): Boolean

    // Certificate pinning
    @JvmStatic
    external fun getPinnedCert(index: Int): String?
    @JvmStatic
    external fun getPinnedCertCount(): Int

    /** @SecurityLevel TOP_SECRET — Returns expected APK signing certificate SHA-256 hash.
     *  Stored encrypted in native .rodata section. Returns null in release if
     *  integrity check fails. Caller must zero the returned array after use. */
    @JvmStatic
    external fun getExpectedCertSha256(): ByteArray?

    // Body encryption (WB-AES-256-GCM, random 12-byte nonce + 16-byte tag)
    @JvmStatic
    external fun encryptBody(plaintext: ByteArray): ByteArray?
    @JvmStatic
    external fun decryptBody(ciphertext: ByteArray): ByteArray?

    // Credential envelope (SM4-GCM); AAD binds ciphertext to its logical record.
    @JvmStatic
    external fun sealCredential(plaintext: ByteArray, aad: ByteArray): ByteArray?
    @JvmStatic
    external fun unsealCredential(ciphertext: ByteArray, aad: ByteArray): ByteArray?

    // === Shell hardening: preflight proof token ===
    // Returns a dynamic SM3 token derived from SO integrity state.
    // Without valid SO → returns empty string. Must be sent as X-Lianyu-Proof header.
    @JvmStatic
    external fun getPreflightToken(): String

    /** Initialize security subsystem at app start */
    fun initialize(context: Context): Boolean {
        wbAesInit()
        val sigOk = verifySignature(context)
        // Initialize KMS (derives DK from MK via whitebox AES, loads into NEON)
        KmsProvider.initialize()
        // L2: Apply boot-time table obfuscation (random XOR mask)
        // Must be AFTER KMS init (KMS needs clean tables for DK derivation)
        val seed = java.security.SecureRandom().generateSeed(32)
        wbAesObfuscateTables(seed)
        // L2b: Enable constant-time side-channel defenses
        wbAesSideChannelDefense()
        return sigOk
    }

    /** Full safety check combining all detectors */
    fun runFullCheck(context: Context): Boolean {
        val sigOk = verifySignature(context)
        if (!sigOk) return false
        val rootOk = !isDeviceRooted()
        val hookOk = !isHookDetected()
        val emuOk = !isEmulator()
        val debugOk = !isDebugged()
        val mitmOk = !isMitmDetected()
        val score = getThreatScore()
        return sigOk && rootOk && hookOk && emuOk && debugOk && mitmOk && score < 3
    }

    /** Encrypt sensitive local data. Uses WB-AES-256-CBC with random IV prepended. */
    fun encryptData(plaintext: ByteArray): ByteArray? {
        return wbAesEncrypt(plaintext)
    }

    /** Decrypt data encrypted by encryptData(). Returns null on failure. */
    fun decryptData(ciphertext: ByteArray): ByteArray? {
        return wbAesDecrypt(ciphertext)
    }
}
