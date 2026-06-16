package com.lianyu.ai.security

import android.content.Context
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SecurityOrchestrator — 统一安全编排入口
 *
 * 单入口 `encrypt()` / `decrypt()`，自动串联完整管线：
 *   detect → KMS derive → BK blind → WB-AES encrypt → BK unblind → audit → wipe
 *
 * 安全保证：
 *   - SK 永不在 Java heap 中出现（全在 native 层派生/使用/销毁）
 *   - BREACH 状态下返回零长度 + 审计记录
 *   - 线程安全：@Synchronized + 原子自旋锁
 *
 * 使用方式：
 *   val result = SecurityOrchestrator.encrypt(context, plaintext)
 *   if (result is EncryptionResult.Success) { ... }
 */
object SecurityOrchestrator {

    private const val METADATA_SIZE = 16    // salt(8B) || counter(8B)

    /** Spinlock: prevent concurrent crypto operations from corrupting NEON DK */
    private val cryptoLock = AtomicBoolean(false)
    private val lockSpins = AtomicInteger(0)
    private const val MAX_SPINS = 1000       // ~1ms on A76, then fail

    // Monotonic counter for BK metadata (thread-safe)
    private val bkCounter = AtomicInteger(0)

    /** Boot-time salt — generated once, fixed per process lifetime */
    private val bootSalt: ByteArray by lazy {
        val salt = ByteArray(8)
        SecureRandom().nextBytes(salt)
        salt
    }

    /* ================================================================
     * Public API
     * ================================================================ */

    /**
     * 加密统一入口。
     *
     * 管线：ZT检查 → 获取锁 → KMS派生 → BK盲化 → WB-AES加密 → 审计 → 释放锁
     *
     * @param context    Android Context (用于 SecurityGuard / AuditLogger)
     * @param plaintext  明文数据（自动 PKCS7 填充到 16 字节对齐）
     * @return EncryptionResult.Success 或 Error / Breach
     */
    @Synchronized
    fun encrypt(context: Context, plaintext: ByteArray): EncryptionResult {
        // 1. Zero-trust gate
        val ztState = NativeBridge.zeroTrustGetState()
        if (ztState == 2) { // ZT_BREACH
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED,
                "encrypt blocked: zero-trust BREACH")
            return EncryptionResult.Breach
        }

        if (!G0.c(context)) {
            AuditLogger.log(context, AuditLogger.Level.ERROR,
                AuditLogger.Event.TAMPER_DETECTED,
                "encrypt blocked: SecurityGuard tampered")
            return EncryptionResult.Error("SecurityGuard reported tampered state")
        }

        // 2. Acquire spinlock
        if (!acquireLock()) {
            return EncryptionResult.Error("crypto lock timeout: another operation in progress")
        }

        try {
            // 3. Generate metadata for BK sync
            val counter = bkCounter.incrementAndGet()
            val metadata = buildMetadata(counter)

            // 4. PKCS7 pad plaintext to block boundary
            val padded = pkcs7Pad(plaintext)

            // 5. Encrypt via KMS native V2 pipeline
            //    (native layer: derive DK → BK blind → WB-AES-CBC → BK unblind → wipe)
            val encrypted = KmsProvider.encryptWithMetadata(padded, metadata)
                ?: run {
                    AuditLogger.log(context, AuditLogger.Level.ERROR,
                        AuditLogger.Event.ENCRYPT_FAIL,
                        "native encrypt returned null")
                    return EncryptionResult.Error("native encryption failed")
                }

            // 6. Prepend metadata for decrypt-side BK sync
            //    Format: metadata(16B) || ciphertext
            //    (No IV needed — BK blinding provides randomization)
            val result = ByteArray(METADATA_SIZE + encrypted.size)
            System.arraycopy(metadata, 0, result, 0, METADATA_SIZE)
            System.arraycopy(encrypted, 0, result, METADATA_SIZE, encrypted.size)

            // 7. Audit
            AuditLogger.log(context, AuditLogger.Level.DEBUG,
                AuditLogger.Event.DB_ENCRYPTED,
                "encrypt OK: ${plaintext.size}B → ${result.size}B, counter=$counter")

            return EncryptionResult.Success(result)

        } catch (e: Exception) {
            AuditLogger.log(context, AuditLogger.Level.ERROR,
                AuditLogger.Event.ENCRYPT_FAIL,
                "encrypt exception: ${e.message}")
            return EncryptionResult.Error(e.message ?: "unknown")
        } finally {
            releaseLock()
        }
    }

    /**
     * 解密统一入口。
     *
     * @param context    Android Context
     * @param ciphertext encrypt() 的输出（metadata || iv || ciphertext）
     * @return EncryptionResult
     */
    @Synchronized
    fun decrypt(context: Context, ciphertext: ByteArray): EncryptionResult {
        // 1. Zero-trust gate
        val ztState = NativeBridge.zeroTrustGetState()
        if (ztState == 2) {
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED,
                "decrypt blocked: zero-trust BREACH")
            return EncryptionResult.Breach
        }

        if (!G0.c(context)) {
            return EncryptionResult.Error("SecurityGuard reported tampered state")
        }

        // 2. Validate minimum size
        if (ciphertext.size < METADATA_SIZE + 16) {
            return EncryptionResult.Error("ciphertext too short: ${ciphertext.size}B")
        }

        // 3. Extract metadata and payload
        //    Format: metadata(16B) || ciphertext
        val metadata = ciphertext.copyOfRange(0, METADATA_SIZE)
        val payload = ciphertext.copyOfRange(METADATA_SIZE, ciphertext.size)

        // 4. Acquire spinlock
        if (!acquireLock()) {
            return EncryptionResult.Error("crypto lock timeout")
        }

        try {
            // 5. Decrypt via KMS native V2 pipeline
            val decrypted = KmsProvider.decryptWithMetadata(payload, metadata)
                ?: run {
                    AuditLogger.log(context, AuditLogger.Level.ERROR,
                        AuditLogger.Event.DECRYPT_FAIL,
                        "native decrypt returned null")
                    return EncryptionResult.Error("native decryption failed")
                }

            // 6. Remove PKCS7 padding
            val plaintext = pkcs7Unpad(decrypted)
                ?: return EncryptionResult.Error("PKCS7 unpad failed: malformed padding")

            // 7. Audit
            AuditLogger.log(context, AuditLogger.Level.DEBUG,
                AuditLogger.Event.DB_ENCRYPTED,
                "decrypt OK: ${ciphertext.size}B → ${plaintext.size}B")

            return EncryptionResult.Success(plaintext)

        } catch (e: Exception) {
            AuditLogger.log(context, AuditLogger.Level.ERROR,
                AuditLogger.Event.DECRYPT_FAIL,
                "decrypt exception: ${e.message}")
            return EncryptionResult.Error(e.message ?: "unknown")
        } finally {
            releaseLock()
        }
    }

    /* ================================================================
     * Lock
     * ================================================================ */

    private fun acquireLock(): Boolean {
        var spins = 0
        while (!cryptoLock.compareAndSet(false, true)) {
            if (++spins > MAX_SPINS) {
                lockSpins.incrementAndGet()
                return false
            }
            Thread.onSpinWait()
        }
        return true
    }

    private fun releaseLock() {
        cryptoLock.set(false)
    }

    /* ================================================================
     * Metadata / BK helpers
     * ================================================================ */

    /**
     * Build BK metadata: salt(8B) || counter(8B, big-endian).
     *
     * BK = SM4_enc(DK[0:16], metadata)  — deterministic, same DK+metadata → same BK.
     * This is how encrypt/decrypt sides stay in sync.
     */
    private fun buildMetadata(counter: Int): ByteArray {
        val meta = ByteArray(METADATA_SIZE)
        System.arraycopy(bootSalt, 0, meta, 0, 8)
        // counter as big-endian 64-bit
        meta[8] = ((counter ushr 56) and 0xFF).toByte()
        meta[9] = ((counter ushr 48) and 0xFF).toByte()
        meta[10] = ((counter ushr 40) and 0xFF).toByte()
        meta[11] = ((counter ushr 32) and 0xFF).toByte()
        meta[12] = ((counter ushr 24) and 0xFF).toByte()
        meta[13] = ((counter ushr 16) and 0xFF).toByte()
        meta[14] = ((counter ushr 8) and 0xFF).toByte()
        meta[15] = (counter and 0xFF).toByte()
        return meta
    }

    /* ================================================================
     * PKCS7 padding (RFC 5652)
     * ================================================================ */

    private fun pkcs7Pad(data: ByteArray): ByteArray {
        val blockSize = 16
        val padLen = blockSize - (data.size % blockSize)
        val padded = ByteArray(data.size + padLen)
        System.arraycopy(data, 0, padded, 0, data.size)
        for (i in data.size until padded.size) {
            padded[i] = padLen.toByte()
        }
        return padded
    }

    private fun pkcs7Unpad(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val padLen = data[data.size - 1].toInt() and 0xFF
        if (padLen == 0 || padLen > 16 || padLen > data.size) return null
        // Verify all pad bytes
        for (i in data.size - padLen until data.size) {
            if ((data[i].toInt() and 0xFF) != padLen) return null
        }
        return data.copyOf(data.size - padLen)
    }
}

/* ================================================================
 * Result types
 * ================================================================ */

sealed class EncryptionResult {
    /** 操作成功，data 为密文（encrypt）或明文（decrypt） */
    data class Success(val data: ByteArray) : EncryptionResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return data.contentEquals(other.data)
        }
        override fun hashCode(): Int = data.contentHashCode()
    }

    /** 操作失败，包含原因 */
    data class Error(val reason: String) : EncryptionResult()

    /** 零信任 BREACH 状态，操作被拦截 */
    object Breach : EncryptionResult()
}
