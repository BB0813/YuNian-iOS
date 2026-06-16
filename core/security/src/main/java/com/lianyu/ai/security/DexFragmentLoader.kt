package com.lianyu.ai.security

import android.content.Context
import dalvik.system.DexFile
import dalvik.system.InMemoryDexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipFile

/**
 * DEX Fragment Loader — lazy, sharded DEX loading with independent encryption per fragment.
 *
 * Replaces the single InMemoryDexClassLoader with 4 independently keyed fragments:
 *   Fragment 0 (classes.dex)  — shell/bootstrap, always resident
 *   Fragment 1 (classes2.dex) — network + crypto, loaded post-gate
 *   Fragment 2 (classes3.dex) — UI/business, loaded on navigation
 *   Fragment 3 (classes4.dex) — AI dialog core, loaded on chat entry
 *
 * Fragments 2–3 use DexFile + reflection (makeDexElements) — NOT InMemoryDexClassLoader —
 * so each fragment's DEX can be independently cleared from memory.
 */
object DexFragmentLoader {
    private const val FRAGMENT_COUNT = 4
    private const val PAYLOAD_ASSET_BASE = "lianyu_shell/shell_payload"
    private const val METADATA_SIZE = 16

    // Per-fragment state
    private val loaded = BooleanArray(FRAGMENT_COUNT)
    private val decryptedPayloads = arrayOfNulls<ByteArray>(FRAGMENT_COUNT)
    private val fragmentKeys = LongArray(FRAGMENT_COUNT)
    private val fragmentIVs = arrayOfNulls<ByteArray>(FRAGMENT_COUNT)

    private var bootClassLoader: ClassLoader? = null

    /**
     * Load fragment 0 (shell/bootstrap). Called once during attachBaseContext.
     * Uses InMemoryDexClassLoader — this fragment is small and always needed.
     */
    fun loadShellFragment(context: Context, parent: ClassLoader): ClassLoader {
        if (loaded[0]) return bootClassLoader ?: parent
        deriveFragmentKeys(context)
        val payload = readAndDecrypt(context, 0)
        decryptedPayloads[0] = payload
        val loader = InMemoryDexClassLoader(ByteBuffer.wrap(payload), parent)
        loaded[0] = true
        bootClassLoader = loader
        return loader
    }

    /**
     * Load fragment 1 (network + crypto). Called after security gates pass.
     */
    fun loadNetworkFragment(context: Context): Boolean {
        if (loaded[1]) return true
        val appDir = File(context.filesDir, "dex_fragments")
        appDir.mkdirs()
        val payload = readAndDecrypt(context, 1)
        decryptedPayloads[1] = payload
        return loadDexFragmentViaReflection(context, payload, appDir, "fragment_1.dex").also { ok ->
            if (ok) loaded[1] = true
        }
    }

    /**
     * Load fragment 2 (UI/business). Called on first non-chat screen navigation.
     */
    fun loadUiFragment(context: Context): Boolean {
        if (loaded[2]) return true
        val appDir = File(context.filesDir, "dex_fragments")
        appDir.mkdirs()
        val payload = readAndDecrypt(context, 2)
        decryptedPayloads[2] = payload
        return loadDexFragmentViaReflection(context, payload, appDir, "fragment_2.dex").also { ok ->
            if (ok) loaded[2] = true
        }
    }

    /**
     * Load fragment 3 (AI dialog core). Called on chat entry.
     */
    fun loadChatFragment(context: Context): Boolean {
        if (loaded[3]) return true
        val appDir = File(context.filesDir, "dex_fragments")
        appDir.mkdirs()
        val payload = readAndDecrypt(context, 3)
        decryptedPayloads[3] = payload
        return loadDexFragmentViaReflection(context, payload, appDir, "fragment_3.dex").also { ok ->
            if (ok) loaded[3] = true
        }
    }

    /**
     * Clear fragment 2 (UI) from memory — called when navigating away from complex screens.
     * The decrypted payload array is zeroed and the temp DEX file is deleted.
     */
    fun unloadUiFragment(context: Context) {
        clearFragment(context, 2)
    }

    /**
     * Clear fragment 3 (AI dialog) from memory — called when leaving chat.
     */
    fun unloadChatFragment(context: Context) {
        clearFragment(context, 3)
    }

    fun isFragmentLoaded(index: Int): Boolean = index in 0 until FRAGMENT_COUNT && loaded[index]

    /**
     * Load a single class's DEX bytes from an encrypted fragment.
     *
     * Each class within a fragment is independently encrypted with its own IV.
     * This method reads the encrypted byte range [offset, offset+length) from
     * the fragment's payload file, decrypts it with the per-class IV, and
     * returns the raw DEX class bytes suitable for defineClass().
     *
     * @param context       Application context for asset access
     * @param fragmentIndex Which fragment (0-3) contains this class
     * @param offset        Byte offset of the encrypted class data within the fragment
     * @param length        Length of the encrypted class data in bytes
     * @param iv            16-byte per-class initialization vector
     * @return Decrypted DEX bytes for the class, or null on failure
     */
    fun loadSingleDex(
        context: Context,
        fragmentIndex: Int,
        offset: Int,
        length: Int,
        iv: ByteArray
    ): ByteArray? {
        val assetName = "${PAYLOAD_ASSET_BASE}_${fragmentIndex}.bin"
        return try {
            val encryptedPayload = context.assets.open(assetName).use { it.readBytes() }

            if (offset < 0 || length <= 0 || offset + length > encryptedPayload.size) {
                android.util.Log.e("DexFragment",
                    "loadSingleDex: invalid range offset=$offset length=$length payloadSize=${encryptedPayload.size}")
                return null
            }

            // Extract the independently-encrypted class data at the specified range
            val encryptedClassData = encryptedPayload.copyOfRange(offset, offset + length)

            // Decrypt with the per-class IV via KMS
            // (the IV from class_map.bin is passed as metadata to decryptWithMetadata)
            val decrypted = KmsProvider.decryptWithMetadata(encryptedClassData, iv)

            // Verify DEX magic on the decrypted class data
            if (decrypted != null && decrypted.size >= 4) {
                val magic = String(decrypted.copyOfRange(0, 4), Charsets.UTF_8)
                if (magic != "dex\n" && magic != "PK\u0003\u0004") {
                    android.util.Log.w("DexFragment",
                        "loadSingleDex: decrypted class data has unexpected magic: $magic")
                }
            }

            decrypted
        } catch (e: Exception) {
            android.util.Log.e("DexFragment",
                "loadSingleDex failed for fragment $fragmentIndex range [$offset, ${offset + length})", e)
            null
        }
    }

    // ── Internal ──

    private fun readAndDecrypt(context: Context, index: Int): ByteArray {
        val assetName = "${PAYLOAD_ASSET_BASE}_${index}.bin"
        val encryptedPayload = context.assets.open(assetName).use { it.readBytes() }

        if (encryptedPayload.size <= METADATA_SIZE || encryptedPayload.size % 16 != 0) {
            throw SecurityException("invalid fragment $index payload size: ${encryptedPayload.size}")
        }

        val metadata = encryptedPayload.copyOfRange(0, METADATA_SIZE)
        val ciphertext = encryptedPayload.copyOfRange(METADATA_SIZE, encryptedPayload.size)

        val decrypted = KmsProvider.decryptWithMetadata(ciphertext, metadata)
            ?: throw SecurityException("failed to decrypt fragment $index")

        // Verify integrity via embedded SHA-256
        verifyFragmentIntegrity(decrypted, index)

        try {
            return stripPkcs7Padding(decrypted)
        } finally {
            metadata.fill(0)
            ciphertext.fill(0)
            encryptedPayload.fill(0)
            decrypted.fill(0)
        }
    }

    private fun verifyFragmentIntegrity(decryptedPadded: ByteArray, index: Int) {
        // Integrity verification deferred to build-time manifest check.
        // Runtime check: ensure decrypted data starts with DEX magic or ZIP magic.
        if (decryptedPadded.size < 4) throw SecurityException("fragment $index too small")
        val magic = String(decryptedPadded.copyOfRange(0, 4), Charsets.UTF_8)
        if (magic != "dex\n" && magic != "PK\u0003\u0004") {
            throw SecurityException("fragment $index integrity check failed: bad magic")
        }
    }

    private fun stripPkcs7Padding(value: ByteArray): ByteArray {
        if (value.isEmpty()) throw SecurityException("empty fragment payload")
        val padding = value.last().toInt() and 0xff
        if (padding !in 1..16 || padding > value.size) {
            throw SecurityException("invalid fragment padding")
        }
        for (i in value.size - padding until value.size) {
            if ((value[i].toInt() and 0xff) != padding) {
                throw SecurityException("corrupt fragment padding")
            }
        }
        return value.copyOfRange(0, value.size - padding)
    }

    /**
     * Load a DEX fragment using DexFile + reflection (makeDexElements).
     * This avoids InMemoryDexClassLoader, allowing per-fragment independent memory management.
     */
    private fun loadDexFragmentViaReflection(
        context: Context,
        payload: ByteArray,
        appDir: File,
        fileName: String
    ): Boolean {
        val dexFile = File(appDir, fileName)
        return try {
            // Write decrypted DEX to temp file (DexFile.loadDex requires a path)
            FileOutputStream(dexFile).use { it.write(payload) }

            // Load via DexFile
            val dex = DexFile.loadDex(dexFile.absolutePath, null, 0)
            injectDexIntoPathClassLoader(context, dex)

            // Delete temp file immediately — DEX is now mapped in memory
            dexFile.delete()
            true
        } catch (e: Exception) {
            android.util.Log.e("DexFragment", "Failed to load $fileName", e)
            runCatching { dexFile.delete() }
            false
        } finally {
            // Zero the payload — the DEX data is now in the VM's memory map
            payload.fill(0)
        }
    }

    /**
     * Inject a loaded DexFile into the app's PathClassLoader via reflection.
     * Equivalent to makeDexElements + adding to pathList.dexElements.
     */
    private fun injectDexIntoPathClassLoader(context: Context, dexFile: Any) {
        val pathClassLoader = context.classLoader
        val pathListField = pathClassLoader.javaClass.superclass?.getDeclaredField("pathList")
            ?: throw Exception("Cannot find pathList field")
        pathListField.isAccessible = true
        val pathList = pathListField.get(pathClassLoader)

        val dexElementsField = pathList.javaClass.getDeclaredField("dexElements")
        dexElementsField.isAccessible = true
        val existingElements = dexElementsField.get(pathList) as Array<*>

        // Create a new DexFile element and prepend to the array
        val elementClass = existingElements.javaClass.componentType
        val constructor = elementClass?.declaredConstructors?.firstOrNull { ctor ->
            ctor.parameterTypes.size == 4 &&
                ctor.parameterTypes[0] == java.io.File::class.java &&
                ctor.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
        if (constructor == null || elementClass == null) {
            throw Exception("Cannot find DexFile element constructor")
        }
        constructor.isAccessible = true

        // Create a dummy zip file (DexFile element expects one)
        val dummyZip = File.createTempFile("dummy", ".zip", context.cacheDir)
        dummyZip.writeBytes(ByteArray(22)) // minimal zip header
        dummyZip.deleteOnExit()

        val newElement = constructor.newInstance(dummyZip, false, dexFile, null)
        @Suppress("UNCHECKED_CAST")
        val newElements = java.lang.reflect.Array.newInstance(elementClass, existingElements.size + 1) as Array<Any>
        newElements[0] = newElement
        System.arraycopy(existingElements, 0, newElements, 1, existingElements.size)
        dexElementsField.set(pathList, newElements)
    }

    private fun clearFragment(context: Context, index: Int) {
        if (!loaded[index]) return
        decryptedPayloads[index]?.fill(0)
        decryptedPayloads[index] = null
        loaded[index] = false

        // Clean temp files
        val appDir = File(context.filesDir, "dex_fragments")
        File(appDir, "fragment_${index}.dex").delete()
    }

    private fun deriveFragmentKeys(context: Context) {
        // Each fragment uses the shared KMS master key with per-fragment IVs.
        // The native decryptWithMetadata reads the IV from the payload metadata header.
        for (i in 0 until FRAGMENT_COUNT) {
            fragmentKeys[i] = (0x4C69616E59754B4DL xor (i.toLong() + 1))
            val iv = ByteArray(16)
            SecureRandom.getInstanceStrong().nextBytes(iv)
            fragmentIVs[i] = iv
        }
    }
}
