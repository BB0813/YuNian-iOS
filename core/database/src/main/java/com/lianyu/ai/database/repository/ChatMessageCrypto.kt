package com.lianyu.ai.database.repository

import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.GroupMessage
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Application-layer encryption for chat message payload fields.
 *
 * Query-critical metadata stays queryable in SQLite:
 * - timestamp for millisecond cursor reads
 * - searchContent for fuzzy search
 * - fileFormat for file category filtering
 *
 * Sensitive payload fields are encrypted at rest:
 * - content
 * - linkString (supports one or more resources encoded as a single link string)
 */
object ChatMessageCrypto {
    private const val KEYSTORE_ALIAS = "lianyu_chat_message_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    private const val PREFIX = "enc:v1:"

    fun interface KeyProvider {
        fun getKey(): SecretKey?
    }

    private object AndroidKeyStoreKeyProvider : KeyProvider {
        private val cachedKey: SecretKey? by lazy {
            runCatching { getOrCreateAndroidKeyStoreKey() }.getOrNull()
        }

        override fun getKey(): SecretKey? = cachedKey
    }

    private val defaultKeyProvider: KeyProvider = AndroidKeyStoreKeyProvider

    // --- Public API (mirrors C0 interface for drop-in replacement) ---

    fun encryptForStorage(message: ChatMessage): ChatMessage {
        return encryptForStorage(message, defaultKeyProvider)
    }

    internal fun encryptForStorage(message: ChatMessage, keyProvider: KeyProvider): ChatMessage {
        return message.copy(
            content = encrypt(message.content, keyProvider),
            searchContent = message.searchContent.ifBlank { message.content },
            linkString = encrypt(message.linkString, keyProvider)
        )
    }

    fun decryptFromStorage(message: ChatMessage): ChatMessage {
        return decryptFromStorage(message, defaultKeyProvider)
    }

    internal fun decryptFromStorage(message: ChatMessage, keyProvider: KeyProvider): ChatMessage {
        return try {
            message.copy(
                content = decrypt(message.content, keyProvider),
                linkString = decrypt(message.linkString, keyProvider)
            )
        } catch (e: Exception) {
            message.copy(
                content = DECRYPT_FAILED_PLACEHOLDER,
                linkString = ""
            )
        }
    }

    fun encryptForStorage(message: GroupMessage): GroupMessage {
        return message.copy(
            content = encrypt(message.content, defaultKeyProvider),
            searchContent = message.searchContent.ifBlank { message.content },
            linkString = encrypt(message.linkString, defaultKeyProvider)
        )
    }

    fun decryptFromStorage(message: GroupMessage): GroupMessage {
        return message.copy(
            content = decrypt(message.content, defaultKeyProvider),
            linkString = decrypt(message.linkString, defaultKeyProvider)
        )
    }

    fun encrypt(plaintext: String): String = encrypt(plaintext, defaultKeyProvider)

    private fun encrypt(plaintext: String, keyProvider: KeyProvider): String {
        if (plaintext.isEmpty()) return plaintext
        if (plaintext.startsWith(PREFIX)) return plaintext
        val encryptionKey = keyProvider.getKey()
            ?: error("AndroidKeyStore chat message key unavailable")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
        return PREFIX + Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(value: String): String = decrypt(value, defaultKeyProvider)

    private fun decrypt(value: String, keyProvider: KeyProvider): String {
        if (value.isEmpty() || !value.startsWith(PREFIX)) return value
        val combined = Base64.getDecoder().decode(value.removePrefix(PREFIX))
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val decryptionKey = keyProvider.getKey()
            ?: error("AndroidKeyStore chat message key unavailable")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, decryptionKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    // --- KeyStore helpers ---

    private fun getOrCreateAndroidKeyStoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        generator.generateKey()
        val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    const val DECRYPT_FAILED_PLACEHOLDER = "[消息解密失败]"
}

@kotlin.jvm.JvmName("filterDecryptedChat")
fun List<com.lianyu.ai.database.model.ChatMessage>.filterDecrypted(): List<com.lianyu.ai.database.model.ChatMessage> =
    filter { it.content != ChatMessageCrypto.DECRYPT_FAILED_PLACEHOLDER }

@kotlin.jvm.JvmName("filterDecryptedGroup")
fun List<com.lianyu.ai.database.model.GroupMessage>.filterDecrypted(): List<com.lianyu.ai.database.model.GroupMessage> =
    filter { it.content != ChatMessageCrypto.DECRYPT_FAILED_PLACEHOLDER }
