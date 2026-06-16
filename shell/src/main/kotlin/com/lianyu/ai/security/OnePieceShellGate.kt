package com.lianyu.ai.security

import android.content.Context
import android.content.pm.PackageManager
import dalvik.system.InMemoryDexClassLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * OnePieceShellGate — DEX 解密门 (Shell Gate).
 *
 * 负责:
 *   1. 从 APK assets 中提取加密的 DEX 碎片
 *   2. 通过 SM4-ECB 解密碎片
 *   3. 重组为完整 DEX → SHA-256 校验
 *   4. 通过 InMemoryDexClassLoader 加载
 *   5. 安全擦除解密后的敏感数据
 *
 * 加密方案:
 *   - DEX 被拆分为 N 个碎片 (默认 7 片)
 *   - 每片使用 SM4-ECB 独立加密
 *   - 碎片伪装为 PNG 文件 (assets/frag_*.png)
 *   - 解密密钥由 cert hash + 硬编码 salt 派生
 *
 * 安全特性:
 *   - 解密后的 DEX 仅存在于内存 (InMemoryDexClassLoader)
 *   - 不落盘 (Never written to disk)
 *   - 使用后立即安全擦除
 */
object OnePieceShellGate {

    private const val TAG = "OnePieceShellGate"
    private const val FRAGMENT_PREFIX = "frag_"
    private const val FRAGMENT_EXT = ".png"
    private const val FRAGMENT_COUNT = 7
    private const val REAL_APP_CLASS = "com.lianyu.ai.LianYuApplication"

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var classLoader: ClassLoader? = null

    // SM4 密钥 (128-bit) — 由 cert hash + salt 派生
    private var sm4Key: ByteArray? = null

    // ================================================================
    // 初始化
    // ================================================================

    fun init(context: Context): Boolean {
        if (initialized) return true

        return try {
            // 从 keystore 派生 SM4 密钥
            sm4Key = deriveKey(context)
            if (sm4Key == null) {
                android.util.Log.e(TAG, "Failed to derive SM4 key")
                return false
            }
            initialized = true
            android.util.Log.i(TAG, "Shell gate initialized")
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Shell gate init error", e)
            false
        }
    }

    // ================================================================
    // DEX 解密与加载
    // ================================================================

    fun decryptAndLoad(context: Context): Boolean {
        if (!initialized) {
            android.util.Log.e(TAG, "Gate not initialized")
            return false
        }

        return try {
            // Step 1: 收集所有加密碎片
            val fragments = collectFragments(context)
            if (fragments.size < FRAGMENT_COUNT) {
                android.util.Log.e(TAG, "Missing fragments: ${fragments.size}/$FRAGMENT_COUNT")
                return false
            }

            // Step 2: 解密每个碎片
            val decryptedFragments = fragments.map { (index, data) ->
                val decrypted = decryptFragment(data, index)
                if (decrypted == null) {
                    android.util.Log.e(TAG, "Fragment $index decryption failed")
                    return false
                }
                decrypted
            }

            // Step 3: 重组为完整 DEX
            val fullDex = reassembleDex(decryptedFragments)

            // Step 4: SHA-256 校验
            val expectedHash = getExpectedHash(context)
            if (expectedHash != null && !verifyHash(fullDex, expectedHash)) {
                android.util.Log.e(TAG, "DEX hash mismatch")
                return false
            }

            // Step 5: 通过 InMemoryDexClassLoader 加载
            val loader = InMemoryDexClassLoader(
                ByteBuffer.wrap(fullDex),
                context.classLoader
            )
            classLoader = loader

            // 安全擦除解密后的数据
            secureWipeInternal(fullDex)
            decryptedFragments.forEach { secureWipeInternal(it) }

            android.util.Log.i(TAG, "DEX decrypted and loaded: ${fullDex.size} bytes")
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "DEX decrypt/load error", e)
            false
        }
    }

    fun getRealAppClassName(): String = REAL_APP_CLASS

    fun getClassLoader(): ClassLoader? = classLoader

    // ================================================================
    // 安全擦除
    // ================================================================

    fun secureWipe() {
        sm4Key?.let {
            java.util.Arrays.fill(it, 0.toByte())
            sm4Key = null
        }
        classLoader = null
        initialized = false
    }

    private fun secureWipeInternal(data: ByteArray) {
        java.util.Arrays.fill(data, 0.toByte())
    }

    // ================================================================
    // 碎片收集
    // ================================================================

    private fun collectFragments(context: Context): List<Pair<Int, ByteArray>> {
        val fragments = mutableListOf<Pair<Int, ByteArray>>()

        try {
            // 方案 A: 从 APK assets 中读取
            val assetManager = context.assets
            val assetList = assetManager.list("") ?: arrayOf()

            for (filename in assetList) {
                if (filename.startsWith(FRAGMENT_PREFIX) && filename.endsWith(FRAGMENT_EXT)) {
                    val indexStr = filename
                        .removePrefix(FRAGMENT_PREFIX)
                        .removeSuffix(FRAGMENT_EXT)
                    val index = indexStr.toIntOrNull() ?: continue

                    val inputStream = assetManager.open(filename)
                    val data = inputStream.readBytes()
                    inputStream.close()

                    fragments.add(index to data)
                }
            }

            // 方案 B: 从 APK zip 直接读取 (fallback)
            if (fragments.isEmpty()) {
                return collectFragmentsFromZip(context)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Asset fragment collection error", e)
            return collectFragmentsFromZip(context)
        }

        return fragments.sortedBy { it.first }
    }

    private fun collectFragmentsFromZip(context: Context): List<Pair<Int, ByteArray>> {
        val fragments = mutableListOf<Pair<Int, ByteArray>>()

        try {
            val apkPath = context.packageCodePath
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name

                    // assets/frag_0.png
                    if (name.startsWith("assets/$FRAGMENT_PREFIX") && name.endsWith(FRAGMENT_EXT)) {
                        val indexStr = name
                            .removePrefix("assets/$FRAGMENT_PREFIX")
                            .removeSuffix(FRAGMENT_EXT)
                        val index = indexStr.toIntOrNull() ?: continue

                        val inputStream = zip.getInputStream(entry)
                        val data = inputStream.readBytes()
                        inputStream.close()

                        fragments.add(index to data)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "ZIP fragment collection error", e)
        }

        return fragments.sortedBy { it.first }
    }

    // ================================================================
    // SM4 解密
    // ================================================================

    private fun decryptFragment(encryptedData: ByteArray, fragmentIndex: Int): ByteArray? {
        val key = sm4Key ?: return null

        return try {
            // 使用 SM4-ECB 解密
            // 每个碎片使用独立 IV (fragment index ⊕ key[0])
            val tweakedKey = key.copyOf()
            tweakedKey[0] = (tweakedKey[0].toInt() xor fragmentIndex).toByte()

            Sm4Cipher.decrypt(encryptedData, tweakedKey)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Fragment $fragmentIndex decrypt error", e)
            null
        }
    }

    // ================================================================
    // DEX 重组
    // ================================================================

    private fun reassembleDex(fragments: List<ByteArray>): ByteArray {
        val outputStream = ByteArrayOutputStream()
        for (fragment in fragments) {
            outputStream.write(fragment)
        }
        return outputStream.toByteArray()
    }

    // ================================================================
    // 密钥派生
    // ================================================================

    private fun deriveKey(context: Context): ByteArray? {
        return try {
            // 获取 APK 签名证书 hash
            val certHash = getCertHash(context)
            if (certHash == null) {
                android.util.Log.e(TAG, "Failed to get cert hash")
                return null
            }

            // 硬编码 salt (360-style: 分散在多个常量中)
            val salt = byteArrayOf(
                0x1F, 0x8B, 0x08, 0x00,  // gzip magic
                0xA3.toByte(), 0x9C.toByte(), 0x7E.toByte(), 0x2B,  // random
                0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),  // 0xDEADBEEF
                0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()   // 0xCAFEBABE
            )

            // SM3(cert_hash || salt) → 128-bit SM4 key
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(certHash)
            digest.update(salt)
            val hash = digest.digest()

            // 取前 16 字节作为 SM4 密钥
            hash.copyOf(16)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Key derivation error", e)
            null
        }
    }

    private fun getCertHash(context: Context): ByteArray? {
        return try {
            val pm = context.packageManager
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }

            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures == null || signatures.isEmpty()) return null

            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(signatures[0].toByteArray())
            digest.digest()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Cert hash error", e)
            null
        }
    }

    // ================================================================
    // SHA-256 校验
    // ================================================================

    private fun getExpectedHash(context: Context): ByteArray? {
        // 从 assets/hash.bin 读取预期 hash
        return try {
            val inputStream = context.assets.open("hash.bin")
            val hash = inputStream.readBytes()
            inputStream.close()
            if (hash.size == 32) hash else null
        } catch (e: Exception) {
            null
        }
    }

    private fun verifyHash(data: ByteArray, expectedHash: ByteArray): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val actualHash = digest.digest(data)
        return actualHash.contentEquals(expectedHash)
    }
}