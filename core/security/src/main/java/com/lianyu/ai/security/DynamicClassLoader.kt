package com.lianyu.ai.security

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap

/**
 * Dynamic Class Recovery ClassLoader — intercepts ClassNotFoundException and
 * recovers classes from encrypted DEX fragments using a class-to-fragment
 * mapping index.
 *
 * Architecture:
 *   1. Normal class lookups delegate to the parent ClassLoader (InMemoryDexClassLoader).
 *   2. On ClassNotFoundException, the mapping index (class_map.bin) is consulted.
 *   3. If the class is found in the index, its encrypted DEX bytes are read from
 *      the appropriate fragment, decrypted per-class, and defined via defineClass().
 *   4. Defined classes are cached in an LRU cache (max 128 entries).
 *
 * Mapping index format (class_map.bin):
 *   [metadata:16][classCount:4][for each class:
 *     nameLen:2 (LE), name (UTF-8),
 *     fragIndex:1, offset:4 (LE), length:4 (LE), iv:16]
 *
 * The mapping index itself is encrypted with KmsProvider.decryptWithMetadata.
 */
class DynamicClassLoader(
    private val context: Context,
    parent: ClassLoader
) : ClassLoader(parent) {

    /** A single entry in the class-to-fragment mapping index. */
    data class ClassEntry(
        val fragmentIndex: Int,
        val offsetInFragment: Int,
        val length: Int,
        val iv: ByteArray
    )

    // ── Mapping index (loaded lazily from encrypted class_map.bin) ──
    private val mappingIndex = LinkedHashMap<String, ClassEntry>()

    // ── LRU class cache (access-ordered, evict eldest when > 128) ──
    private val classCache = object : LinkedHashMap<String, Class<*>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Class<*>>?): Boolean {
            return size > 128
        }
    }

    @Volatile
    private var indexLoaded = false

    // ── Public API ──

    override fun findClass(name: String): Class<*> {
        // 1. Check LRU cache (synchronized on the LinkedHashMap)
        synchronized(classCache) {
            classCache[name]?.let { return it }
        }

        // 2. Try parent ClassLoader first (normal path)
        try {
            return super.findClass(name)
        } catch (_: ClassNotFoundException) {
            // Fall through to dynamic recovery
        }

        // 3. Ensure mapping index is loaded
        ensureIndexLoaded()

        // 4. Look up class in mapping index
        val entry = mappingIndex[name]
            ?: throw ClassNotFoundException(name)

        // 5. Decrypt the class from the appropriate fragment
        val dexBytes = DexFragmentLoader.loadSingleDex(
            context,
            entry.fragmentIndex,
            entry.offsetInFragment,
            entry.length,
            entry.iv
        ) ?: throw ClassNotFoundException("$name (decryption failed)")

        // 6. Define the class
        val clazz = defineClass(name, dexBytes, 0, dexBytes.size)

        // 7. Cache it
        synchronized(classCache) {
            classCache[name] = clazz
        }

        return clazz
    }

    // ── Internal ──

    /**
     * Load the encrypted class_map.bin from assets and parse the mapping index.
     * Called once, lazily, on the first ClassNotFoundException interception.
     * Thread-safe via @Synchronized.
     */
    @Synchronized
    private fun ensureIndexLoaded() {
        if (indexLoaded) return
        try {
            val raw = context.assets.open("lianyu_shell/class_map.bin").use { it.readBytes() }

            if (raw.size < 20) {
                android.util.Log.w("DynamicClassLoader", "class_map.bin too small (${raw.size} bytes), skipping")
                indexLoaded = true
                return
            }

            // Split metadata (first 16 bytes) from encrypted body
            val metadata = raw.copyOfRange(0, 16)
            val encryptedBody = raw.copyOfRange(16, raw.size)

            val decrypted = KmsProvider.decryptWithMetadata(encryptedBody, metadata)
                ?: throw SecurityException("Failed to decrypt class_map.bin")

            parseMappingIndex(decrypted)
            indexLoaded = true

            android.util.Log.i("DynamicClassLoader",
                "Mapping index loaded: ${mappingIndex.size} classes")
        } catch (e: Exception) {
            android.util.Log.e("DynamicClassLoader", "Failed to load class map", e)
            indexLoaded = true // Don't retry on failure
        }
    }

    /**
     * Parse the binary mapping index.
     * Format: [classCount:4 (LE)] [for each: nameLen:2, name, fragIndex:1, offset:4, length:4, iv:16]
     */
    private fun parseMappingIndex(data: ByteArray) {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        if (buf.remaining() < 4) return
        val classCount = buf.int

        for (i in 0 until classCount) {
            if (buf.remaining() < 2) break
            val nameLen = buf.short.toInt() and 0xFFFF

            if (buf.remaining() < nameLen + 1 + 4 + 4 + 16) break
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            val className = String(nameBytes, Charsets.UTF_8)

            val fragIndex = buf.get().toInt() and 0xFF
            val offset = buf.int
            val length = buf.int
            val iv = ByteArray(16)
            buf.get(iv)

            mappingIndex[className] = ClassEntry(fragIndex, offset, length, iv)
        }
    }
}
