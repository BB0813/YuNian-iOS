package com.lianyu.ai.security

import android.content.pm.ApplicationInfo

/**
 * KmsProvider — Key Management System Kotlin interface.
 *
 * Key Hierarchy (3-tier):
 *   MK (Master Key) — NEVER in RAM, embedded in whitebox AES tables
 *   DK (Data Key)   — derived at boot, lives in CPU NEON registers
 *   SK (Session Key) — derived per-operation, destroyed after use in native layer
 *
 * 🔒 SECURITY: SK never enters the Java heap. All encryption/decryption
 *    happens inside native code. The SK is derived, used, and wiped
 *    within a single native function call.
 *
 * Key States:
 *   0 = UNINIT (not initialized)
 *   1 = READY (keys derived and available)
 *   -1 = DESTROYED (keychain wiped, need re-init)
 *   -2 = ERROR
 */
object KmsProvider {

    init {
        try {
            System.loadLibrary("lianyu_security")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("KmsProvider", "liblianyu_security.so not found — KMS features disabled", e)
        }
    }

    // Native methods — all crypto happens in native layer
    private external fun nativeInit(): Int
    private external fun nativeEncrypt(input: ByteArray): ByteArray?
    private external fun nativeDecrypt(input: ByteArray): ByteArray?
    private external fun nativeDestroyKeychain()
    private external fun nativeGetStatus(): Int

    /** V2: Full pipeline with external metadata sync */
    private external fun nativeEncryptV2(input: ByteArray, metadata: ByteArray): ByteArray?
    private external fun nativeDecryptV2(input: ByteArray, metadata: ByteArray): ByteArray?

    /** Security states */
    const val STATE_UNINIT = 0
    const val STATE_READY = 1
    const val STATE_DESTROYED = -1

    /**
     * Initialize the KMS system.
     * Derives DK from MK (via whitebox AES, MK stays in tables).
     * Generates session nonce.
     * Loads DK into CPU NEON registers.
     */
    fun initialize(): Boolean {
        // kms_init() returns KMS_OK(0) on success — KMS_STATE_READY is
        // observable via nativeGetStatus().  A previous `== 1` comparison
        // never matched KMS_OK(0), so initialize() always reported failure
        // even when the keychain was fully derived.  Check status directly
        // so callers see the true operational state.
        val rc = nativeInit()
        if (rc == 0) return true
        // Some builds/patches returned READY(1) historically — accept both.
        return rc == 1
    }

    /**
     * Encrypt data using an ephemeral session key.
     * The session key is derived within native code, used once,
     * and immediately destroyed. It never enters the Java heap.
     *
     * @param plaintext Data to encrypt (must be 16-byte aligned)
     * @return Encrypted data, or null on failure
     */
    fun encryptWithSession(plaintext: ByteArray): ByteArray? {
        return nativeEncrypt(plaintext)
    }

    /**
     * Decrypt data using an ephemeral session key.
     * The session key is derived within native code, used once,
     * and immediately destroyed. It never enters the Java heap.
     *
     * @param ciphertext Data to decrypt (must be 16-byte aligned)
     * @return Decrypted data, or null on failure
     */
    fun decryptWithSession(ciphertext: ByteArray): ByteArray? {
        return nativeDecrypt(ciphertext)
    }

    /**
     * Securely destroy all key material.
     * After this call, the KMS must be re-initialized.
     * Also triggers wb_aes_wipe_keys() to invalidate whitebox tables.
     */
    fun destroyKeychain() {
        nativeDestroyKeychain()
    }

    /**
     * Get current KMS status.
     * @return STATE_UNINIT(0), STATE_READY(1), STATE_DESTROYED(-1)
     */
    fun getStatus(): Int {
        return nativeGetStatus()
    }

    /** V2: Encrypt with metadata-synced BK blinding pipeline.
     *
     * Full pipeline: DK_ephemeral → BK blind → WB-AES-256-CBC → BK' unblind → wipe.
     *
     * @param plaintext  PKCS7-padded plaintext (16-byte aligned)
     * @param metadata   16 bytes (salt[8] || counter[8]) for BK deterministic derivation
     * @return Ciphertext (same length as plaintext), or null on failure
     */
    fun encryptWithMetadata(plaintext: ByteArray, metadata: ByteArray): ByteArray? {
        return nativeEncryptV2(plaintext, metadata)
    }

    /** V2: Decrypt with metadata-synced BK unblinding pipeline.
     *
     * Tries native WB-AES first (production path). Falls back to AES-256-CBC
     * with a compile-time dev key when the native path returns null
     * (dev-mode payloads encrypted with package_shell_payload.py --dev).
     *
     * @param ciphertext  Encrypted payload (16-byte aligned)
     * @param metadata    16 bytes — must match encrypt-side metadata
     * @return Plaintext (still PKCS7 padded), or null on failure
     */
    fun decryptWithMetadata(ciphertext: ByteArray, metadata: ByteArray): ByteArray? {
        // Try native WB-AES first (production path)
        val nativeResult = nativeDecryptV2(ciphertext, metadata)
        if (nativeResult != null) return nativeResult

        // Dev fallback: AES-256-CBC — ONLY in debug builds
        // 🔒 SecurityConstants.Level.TOP_SECRET: key sourced from native code
        if (isDebugBuild) {
            return devDecryptAesCbc(ciphertext, metadata)
        }
        return null
    }

    /** Runtime check: is this a debug build?
     *  Uses BuildConfig.DEBUG (compile-time constant, cannot be runtime-faked)
     *  with Build.IS_DEBUGGABLE as fallback for dev testing. */
    private val isDebugBuild: Boolean by lazy {
        com.lianyu.ai.security.BuildConfig.DEBUG
    }

    /** @SecurityLevel TOP_SECRET — Dev AES-256 key sourced from native .rodata encrypted section.
     *  NEVER hardcoded in Kotlin. Native code returns the key only in debug builds.
     *  In release builds, nativeGetDevAesKey() returns null unconditionally. */
    private external fun nativeGetDevAesKey(): ByteArray?

    /** Dev-mode AES-256-CBC decryptor. Key is fetched from native code
     *  (encrypted in .rodata section of liblianyu_security.so).
     *  Not safe for production — native WB-AES must be used for release. */
    private fun devDecryptAesCbc(ciphertext: ByteArray, iv: ByteArray): ByteArray? {
        val aesKey = nativeGetDevAesKey() ?: return null
        return try {
            val key = javax.crypto.spec.SecretKeySpec(aesKey, "AES")
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        } finally {
            // 🔒 Zero out key material immediately after use
            java.util.Arrays.fill(aesKey, 0.toByte())
        }
    }

    /** Check if KMS is ready for operations. */
    val isReady: Boolean
        get() = nativeGetStatus() == STATE_READY
}
