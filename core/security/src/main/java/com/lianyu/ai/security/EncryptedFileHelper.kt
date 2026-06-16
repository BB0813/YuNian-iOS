package com.lianyu.ai.security

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Helper class for encrypting media files at rest using AndroidX EncryptedFile.
 *
 * Uses a [MasterKey] (AES-256-GCM, backed by AndroidKeyStore) to protect
 * individual files stored in the app's cache or files directory.
 *
 * Usage:
 * ```
 * val helper = EncryptedFileHelper(context)
 * // Write encrypted
 * val out = helper.openEncryptedOutput(existingFile)
 * // Read encrypted
 * val input = helper.openEncryptedInput(existingFile)
 * ```
 *
 * Integration points:
 * - ChatScreen: image file reads from message.linkString (line ~1105)
 * - ChatViewModel: image path saved into ChatMessage.linkString (line ~665)
 * - Sticker imports: files saved to cacheDir (ChatScreen line ~1317)
 * - GroupChatScreen: similar media file access patterns
 */
class EncryptedFileHelper(private val context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Wrap an existing plaintext file with encrypted access.
     * Subsequent reads/writes through [openEncryptedInput] and [openEncryptedOutput]
     * will transparently encrypt/decrypt.
     */
    fun getEncryptedFile(file: File): EncryptedFile {
        return EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    /**
     * Open an encrypted input stream for reading a file.
     * If the file was written with [openEncryptedOutput], it will be
     * transparently decrypted on read.
     */
    fun openEncryptedInput(file: File): InputStream {
        return getEncryptedFile(file).openFileInput()
    }

    /**
     * Open an encrypted output stream for writing to a file.
     * Data written through this stream will be encrypted at rest.
     */
    fun openEncryptedOutput(file: File): OutputStream {
        return getEncryptedFile(file).openFileOutput()
    }

    companion object {
        private const val TAG = "EncryptedFileHelper"

        fun create(context: Context): EncryptedFileHelper {
            return EncryptedFileHelper(context.applicationContext)
        }
    }
}
