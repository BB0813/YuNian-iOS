package com.lianyu.ai.security

import com.lianyu.ai.common.SecureLog

/**
 * SM4 全量加密封装 — 复用 KmsProvider native 加密，整块处理。
 *
 * 控制论: SM4 时滞 ≤ 50ms (TimeoutBudgets.SM4_DECRYPT_MS)
 * 全量处理避免流式分块引入的状态不一致风险。
 *
 * 依赖: KmsProvider (core/security native SO), SecureLog (core/common)
 */
object Sm4Cipher {
    private const val TAG = "Sm4Cipher"

    /**
     * 加密 — PKCS7 填充，整块处理。
     * @param plaintext 明文 (任意长度)
     * @return 密文 (含 PKCS7 填充)，失败返回 null
     */
    fun encrypt(plaintext: ByteArray): ByteArray? {
        if (plaintext.isEmpty()) return null
        return KmsProvider.encryptWithSession(plaintext) ?: run {
            SecureLog.w(TAG, "encrypt failed: native returned null")
            null
        }
    }

    /**
     * 解密 — 自动去 PKCS7 填充，整块处理。
     * @param ciphertext 密文 (含 PKCS7 填充)
     * @return 明文，失败返回 null
     */
    fun decrypt(ciphertext: ByteArray): ByteArray? {
        if (ciphertext.isEmpty()) return null

        // 生产路径: native WB-AES
        return KmsProvider.decryptWithMetadata(ciphertext, ByteArray(0))
            ?: KmsProvider.decryptWithSession(ciphertext)
            ?: run {
                SecureLog.w(TAG, "decrypt failed: both native paths returned null")
                null
            }
    }

    /** 带元数据的加密 V2 */
    fun encryptWithMetadata(plaintext: ByteArray, metadata: ByteArray): ByteArray? {
        return KmsProvider.encryptWithMetadata(plaintext, metadata) ?: run {
            SecureLog.w(TAG, "encryptWithMetadata failed")
            null
        }
    }

    /** 带元数据的解密 V2 */
    fun decryptWithMetadata(ciphertext: ByteArray, metadata: ByteArray): ByteArray? {
        return KmsProvider.decryptWithMetadata(ciphertext, metadata) ?: run {
            SecureLog.w(TAG, "decryptWithMetadata failed")
            null
        }
    }
}
