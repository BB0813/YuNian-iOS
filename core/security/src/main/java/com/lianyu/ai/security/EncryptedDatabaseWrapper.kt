package com.lianyu.ai.security

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import com.lianyu.ai.common.SecureLog
import java.io.File

/**
 * Transparent file-level encryption for Room database files.
 *
 * Flow:
 *   App start: if .enc exists → decrypt to plaintext (Room reads this)
 *   App running: Room operates on plaintext
 *   App shutdown: encrypt plaintext → .enc, delete plaintext
 *
 * Uses AndroidX EncryptedFile (AES-256-GCM, KeyStore-backed).
 */
object EncryptedDatabaseWrapper {

    private const val DB_NAME = "lianyu.db"

    /**
     * Decrypt the encrypted DB to plaintext so Room can open it.
     * Must be called BEFORE Room.databaseBuilder().
     * On first run (no .enc file), leaves plaintext alone — Room will create/use it.
     */
    fun prepareDatabase(context: Context): Boolean {
        val dbPath = context.applicationContext.getDatabasePath(DB_NAME)
        val dbDir = dbPath.parentFile ?: context.applicationContext.filesDir
        dbDir.mkdirs()
        val encryptedFile = File(dbDir, "$DB_NAME.enc")
        val plaintextFile = File(dbDir, DB_NAME)

        if (!encryptedFile.exists()) {
            // First run or no encrypted copy exists — leave plaintext for Room
            if (plaintextFile.exists() && plaintextFile.length() > 0) {
                // Existing plaintext DB from pre-encryption era — encrypt on next shutdown
            }
            return true // Room can proceed with plaintext
        }

        // Encrypted copy exists — decrypt to plaintext for Room
        if (plaintextFile.exists()) {
            deleteRoomAuxFiles(plaintextFile)
        }

        return runCatching {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedFile.Builder(
                encryptedFile,
                context.applicationContext,
                masterKeyAlias,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build().openFileInput().use { input ->
                plaintextFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        }.getOrElse { e ->
            SecureLog.e("EncryptedDB", "Failed to decrypt database", e)
            encryptedFile.delete() // Corrupt .enc — start fresh
            plaintextFile.delete()
            deleteRoomAuxFiles(plaintextFile)
            false
        }
    }

    /**
     * Encrypt the plaintext DB and delete plaintext.
     * Must be called AFTER Room is closed (on app shutdown).
     * If no planitext exists, does nothing.
     */
    fun sealDatabase(context: Context) {
        val dbPath = context.applicationContext.getDatabasePath(DB_NAME)
        val dbDir = dbPath.parentFile ?: context.applicationContext.filesDir
        val plaintextFile = File(dbDir, DB_NAME)
        val encryptedFile = File(dbDir, "$DB_NAME.enc")

        if (!plaintextFile.exists() || plaintextFile.length() == 0L) return

        runCatching {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedFile.Builder(
                encryptedFile,
                context.applicationContext,
                masterKeyAlias,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build().openFileOutput().use { output ->
                plaintextFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            plaintextFile.delete()
            deleteRoomAuxFiles(plaintextFile)
        }.onFailure { e ->
            SecureLog.e("EncryptedDB", "Failed to seal database", e)
        }
    }

    private fun deleteRoomAuxFiles(dbFile: File) {
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-journal").delete()
    }
}
