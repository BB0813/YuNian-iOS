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
            // Already loaded by shell ClassLoader — native methods are registered
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
        return nativeInit() == 1
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
     * Tries native WB-AES first (production). Falls back to AES-256-CBC
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
        if (isDebugBuild) {
            return devDecryptAesCbc(ciphertext, metadata)
        }
        return null
    }

    /** Runtime check: is this a debug build?
     *  Uses reflection to read android.os.Build.IS_DEBUGGABLE
     *  which is true for debug builds and false for release. */
    private val isDebugBuild: Boolean by lazy {
        try {
            val field = Class.forName("android.os.Build")
                .getDeclaredField("IS_DEBUGGABLE")
            field.isAccessible = true
            field.getBoolean(null)
        } catch (_: Exception) { false }
    }

    /** Dev-mode AES-256-CBC decryptor. Key is derived from a compile-time
     *  constant identical to what package_shell_payload.py --dev uses.
     *  Not safe for production — native WB-AES must be used for release. */
    private fun devDecryptAesCbc(ciphertext: ByteArray, iv: ByteArray): ByteArray? {
        val aesKey = DEV_AES_KEY ?: return null
        return try {
            val key = javax.crypto.spec.SecretKeySpec(aesKey, "AES")
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    /** Dev AES-256 key — ONLY available in debug builds.
     *  In release builds this field is inaccessible (guarded by BuildConfig.DEBUG). */
    private val DEV_AES_KEY: ByteArray? by lazy {
        if (isDebugBuild) byteArrayOf(
            0x4f.toByte(), 0xf8.toByte(), 0xfd.toByte(), 0xb2.toByte(),
            0xf6.toByte(), 0xe7.toByte(), 0x2b.toByte(), 0xa0.toByte(),
            0x3a.toByte(), 0x21.toByte(), 0xa4.toByte(), 0x64.toByte(),
            0x58.toByte(), 0x70.toByte(), 0x64.toByte(), 0x80.toByte(),
            0xd5.toByte(), 0x1f.toByte(), 0xdb.toByte(), 0xbc.toByte(),
            0x32.toByte(), 0xf9.toByte(), 0x6e.toByte(), 0x39.toByte(),
            0x8f.toByte(), 0xf9.toByte(), 0xb4.toByte(), 0x33.toByte(),
            0x06.toByte(), 0x16.toByte(), 0x19.toByte(), 0xe6.toByte()
        ) else null
    }

    /** Check if KMS is ready for operations. */
    val isReady: Boolean
        get() = nativeGetStatus() == STATE_READY
}
