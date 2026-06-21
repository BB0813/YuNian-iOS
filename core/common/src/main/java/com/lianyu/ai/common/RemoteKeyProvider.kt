package com.lianyu.ai.common

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object RemoteKeyProvider {

    private const val PREFS_NAME = "remote_key_provider"
    private const val KEY_CACHE_FILE = "partner_keys.dat"
    private const val KEY_LAST_FETCH = "last_fetch_ms"
    private const val KEY_RANDOM_MODEL = "random_model"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_SESSION_KEY = "session_key"
    // Server URL set at app init — no hardcoded default
    @Volatile
    var serverUrl: String = ""

    private fun resolveServerUrl(): String = serverUrl
    private const val HANDSHAKE_PATH = "/api/auth/handshake"
    private const val KEYS_FETCH_PATH = "/api/keys/fetch"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val AES_GCM_ALGORITHM = "AES/GCM/NoPadding"
    private const val AES_CBC_ALGORITHM = "AES/CBC/PKCS5Padding"

    // Android Keystore 硬件级密钥隔离
    private const val KEYSTORE_KEY_ALIAS = "lianyu_partner_key_v3"

    @Volatile
    private var cachedKeys: List<String> = emptyList()

    @Volatile
    private var cachedRandomModel: String? = null

    @Volatile
    private var lastFetchMs: Long = 0

    private val random = SecureRandom()

    /**
     * 从 Android Keystore 获取或创建硬件级 AES-256 密钥。
     * 密钥由 TEE/StrongBox 保护，任何其他 App（即使 Root）无法提取密钥材料。
     * 首次调用自动生成并存于安全硬件中。
     */
    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        if (keyStore.containsAlias(KEYSTORE_KEY_ALIAS)) {
            return (keyStore.getEntry(KEYSTORE_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encryptData(data: String): String {
        val secretKey = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * 解密 partner_keys.dat。优先使用 Android Keystore (v3)，
     * 失败则尝试旧版设备派生密钥 (v2) 实现平滑迁移。
     */
    private fun decryptData(context: Context, encryptedBase64: String): String? {
        return try {
            val secretKey = getOrCreateKeystoreKey()
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 16)
            val encrypted = combined.copyOfRange(16, combined.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            SecureLog.w("RemoteKeyProvider", "Decrypt failed: ${e.message}")
            null
        }
    }

    fun getPartnerKeys(context: Context): List<String> {
        val appContext = context.applicationContext

        if (cachedKeys.isNotEmpty() && isCacheValid(appContext)) {
            return cachedKeys
        }

        val localKeys = loadLocalKeys(appContext)
        if (localKeys.isNotEmpty() && !isCacheExpired(appContext)) {
            cachedKeys = localKeys
            return localKeys
        }

        return emptyList()
    }

    suspend fun fetchKeysAsync(context: Context, forceRefresh: Boolean = false): List<String> {
        val appContext = context.applicationContext

        return withContext(Dispatchers.IO) {
            if (!forceRefresh && cachedKeys.isNotEmpty() && isCacheValid(appContext)) {
                SecureLog.d("RemoteKeyProvider", "Using cached keys (${cachedKeys.size})")
                return@withContext cachedKeys
            }

            SecureLog.d("RemoteKeyProvider", "=== FETCH KEYS START ===")
            SecureLog.d("RemoteKeyProvider", "forceRefresh=$forceRefresh, cachedKeys=${cachedKeys.size}")

            // 1. 加密端点（AES-256-GCM 端到端加密，绝不泄露密钥明文）
            try {
                val encryptedKeys = fetchEncryptedKeys(appContext)
                if (!encryptedKeys.isNullOrEmpty()) {
                    saveLocalKeys(appContext, encryptedKeys)
                    cachedKeys = encryptedKeys
                    SecureLog.api("RemoteKeyProvider", "Fetched ${encryptedKeys.size} keys via encrypted API")
                    updateFetchTime(appContext)
                    return@withContext encryptedKeys
                }
            } catch (e: Exception) {
                SecureLog.w("RemoteKeyProvider", "Encrypted API failed: ${e.message}")
            }

            // 2. 本地缓存（不上明文兜底，防止密钥抓包泄露）
            val localKeys = loadLocalKeys(appContext)
            if (localKeys.isNotEmpty()) {
                cachedKeys = localKeys
                SecureLog.w("RemoteKeyProvider", "Encrypted fetch failed, using cached keys (${localKeys.size})")
                return@withContext localKeys
            }

            SecureLog.w("RemoteKeyProvider", "No keys available - encrypted failed and no cache")
            emptyList()
        }
    }

    private fun fetchEncryptedKeys(ctx: Context): List<String>? {
        // Step 1: Handshake to get token and sessionKey
        val clientId = getClientId(ctx)
        val handshakeJson = JSONObject().apply { put("clientId", clientId) }
        val handshakeUrl = URL("${resolveServerUrl()}$HANDSHAKE_PATH")

        SecureLog.d("RemoteKeyProvider", "Handshake POST $HANDSHAKE_PATH")
        val handshakeResp = httpPost(handshakeUrl, handshakeJson.toString())
        if (handshakeResp == null) {
            SecureLog.w("RemoteKeyProvider", "Handshake returned null")
            return null
        }

        val token = handshakeResp.optString("token")
        val sessionKey = handshakeResp.optString("sessionKey")
        if (token.isEmpty() || sessionKey.isEmpty()) {
            SecureLog.w("RemoteKeyProvider", "Handshake missing token or sessionKey")
            return null
        }

        // Step 2: Fetch encrypted keys
        val fetchJson = JSONObject().apply { put("token", token) }
        val fetchUrl = URL("${resolveServerUrl()}$KEYS_FETCH_PATH")

        SecureLog.d("RemoteKeyProvider", "Fetch keys POST $KEYS_FETCH_PATH")
        val fetchResp = httpPost(fetchUrl, fetchJson.toString())
        if (fetchResp == null || !fetchResp.optBoolean("encrypted")) {
            SecureLog.w("RemoteKeyProvider", "Keys fetch failed or not encrypted")
            return null
        }

        // Step 3: Decrypt the response
        val dataObj = fetchResp.optJSONObject("data")
        if (dataObj == null) {
            SecureLog.w("RemoteKeyProvider", "No encrypted data in response")
            return null
        }
        val iv = dataObj.optString("iv")
        val tag = dataObj.optString("tag")
        val encryptedData = dataObj.optString("data")
        if (iv.isEmpty() || tag.isEmpty() || encryptedData.isEmpty()) {
            SecureLog.w("RemoteKeyProvider", "Missing iv/tag/data in encrypted response")
            return null
        }

        val decrypted = decryptAesGcm(encryptedData, sessionKey, iv, tag)
        if (decrypted == null) {
            SecureLog.w("RemoteKeyProvider", "Decryption failed")
            return null
        }

        SecureLog.d("RemoteKeyProvider", "Decrypted: ${decrypted.take(200)}...")

        val keysJson = JSONObject(decrypted)
        val keysArray = keysJson.optJSONArray("keys")
        if (keysArray == null || keysArray.length() == 0) {
            SecureLog.w("RemoteKeyProvider", "No keys in decrypted response")
            return null
        }

        val keys = mutableListOf<String>()
        for (i in 0 until keysArray.length()) {
            keys.add(keysArray.getString(i))
        }

        // 🎲 解析服务器推荐的随机模型
        if (keysJson.has("randomModel") && !keysJson.isNull("randomModel")) {
            val randomModel = keysJson.getString("randomModel")
            cachedRandomModel = randomModel
            SecureLog.d("RemoteKeyProvider", "Server recommended random model: $randomModel")

            // 保存到本地缓存
            try {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_RANDOM_MODEL, randomModel)
                    .apply()
            } catch (_: Exception) {}
        }

        // 如果没有推荐模型但有模型列表，本地随机选一个
        if (cachedRandomModel == null && keysJson.has("models")) {
            val modelsArray = keysJson.getJSONArray("models")
            if (modelsArray.length() > 0) {
                val randomIndex = random.nextInt(modelsArray.length())
                cachedRandomModel = modelsArray.getString(randomIndex)
                SecureLog.d("RemoteKeyProvider", "Locally selected random model: $cachedRandomModel")
            }
        }

        return keys
    }

    private fun decryptAesGcm(data: String, sessionKeyHex: String, ivHex: String, tagHex: String): String? {
        return try {
            // PBKDF2-SHA256 key derivation (not raw sessionKey)
            val rawKey = sessionKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val salt = "lianyu_suflow_v3".toByteArray()
            val spec = javax.crypto.spec.PBEKeySpec(
                String(rawKey).toCharArray(), salt, 100000, 256
            )
            val keyBytes = factory.generateSecret(spec).encoded.copyOf(32)

            val ivBytes = ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val tagBytes = tagHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cipherText = android.util.Base64.decode(data, android.util.Base64.DEFAULT)

            val cipher = javax.crypto.Cipher.getInstance(AES_GCM_ALGORITHM)
            val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val decrypted = cipher.doFinal(cipherText + tagBytes)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            SecureLog.w("RemoteKeyProvider", "AES-GCM decrypt error: ${e.message}")
            null
        }
    }

    private fun getClientId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var clientId = prefs.getString("client_id", null)
        if (clientId == null) {
            val androidId = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            clientId = androidId + "_" + android.os.Build.MODEL.replace(" ", "_")
            prefs.edit().putString("client_id", clientId).apply()
        }
        return clientId
    }

    private fun httpPost(url: URL, body: String): JSONObject? {
        var connection: HttpURLConnection? = null
        try {
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = 2_000
                readTimeout = 2_000
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                doInput = true
            }

            connection.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) return null

            val responseBody = connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            return JSONObject(responseBody)
        } catch (e: Exception) {
            SecureLog.w("RemoteKeyProvider", "HTTP POST failed: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun fetchFromServer(urlString: String, ctx: Context): List<String>? {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                SecureLog.d("RemoteKeyProvider", "Opening connection to: $urlString")
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Client-Version", getAppVersion(ctx))
                    doInput = true
                    doOutput = false
                }

                SecureLog.d("RemoteKeyProvider", "Connecting to server...")
                val responseCode = connection.responseCode
                SecureLog.d("RemoteKeyProvider", "Server response code: $responseCode")
                if (responseCode != HttpURLConnection.HTTP_OK) return@withContext null

                val body = connection.inputStream?.bufferedReader()?.use { it.readText() }
                    ?: return@withContext null

                val json = JSONObject(body)
                if (!json.has("keys")) return@withContext null

                // 解析随机分配的密钥（服务器已随机化）
                val keysArray = json.getJSONArray("keys")
                val keys = mutableListOf<String>()
                for (i in 0 until keysArray.length()) {
                    keys.add(keysArray.getString(i))
                }

                // 🎲 解析服务器推荐的随机模型
                if (json.has("randomModel") && !json.isNull("randomModel")) {
                    val randomModel = json.getString("randomModel")
                    cachedRandomModel = randomModel
                    SecureLog.d("RemoteKeyProvider", "Server recommended random model: $randomModel")

                    // 保存到本地缓存
                    try {
                        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_RANDOM_MODEL, randomModel)
                            .apply()
                    } catch (_: Exception) {}
                }

                // 如果没有推荐模型但有模型列表，本地随机选一个
                if (cachedRandomModel == null && json.has("models")) {
                    val modelsArray = json.getJSONArray("models")
                    if (modelsArray.length() > 0) {
                        val randomIndex = random.nextInt(modelsArray.length())
                        cachedRandomModel = modelsArray.getString(randomIndex)
                        SecureLog.d("RemoteKeyProvider", "Locally selected random model: $cachedRandomModel")
                    }
                }

                SecureLog.api("RemoteKeyProvider",
                    "Fetched ${keys.size}/${json.optInt("totalAvailable", keys.size)} random keys, " +
                    "model: ${cachedRandomModel ?: "default"}"
                )

                keys
            } catch (e: Exception) {
                SecureLog.w("RemoteKeyProvider", "Fetch from $urlString failed: ${e.message}")
                null
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun saveLocalKeys(context: Context, keys: List<String>) {
        try {
            val json = JSONObject()
            json.put("keys", JSONArray(keys))
            json.put("version", 2)  // v3 Keystore (v1=旧版设备派生)
            val encrypted = encryptData(json.toString())
            val file = File(context.filesDir, KEY_CACHE_FILE)
            file.writeText(encrypted)
        } catch (e: Exception) {
            SecureLog.w("RemoteKeyProvider", "Save local keys failed: ${e.message}")
        }
    }

    private fun loadLocalKeys(context: Context): List<String> {
        return try {
            val file = File(context.filesDir, KEY_CACHE_FILE)
            if (!file.exists()) return emptyList()
            val encrypted = file.readText()
            val decrypted = decryptData(context, encrypted) ?: return emptyList()
            val json = JSONObject(decrypted)
            if (!json.has("keys")) return emptyList()

            // 恢复缓存的随机模型
            if (cachedRandomModel == null) {
                cachedRandomModel = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_RANDOM_MODEL, null)
                if (cachedRandomModel != null) {
                    SecureLog.d("RemoteKeyProvider", "Restored random model from cache: $cachedRandomModel")
                }
            }

            val keysArray = json.getJSONArray("keys")
            val keys = mutableListOf<String>()
            for (i in 0 until keysArray.length()) {
                keys.add(keysArray.getString(i))
            }
            keys
        } catch (e: Exception) {
            SecureLog.w("RemoteKeyProvider", "Load local keys failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取服务器推荐的随机模型（每次获取密钥时会更新）
     */
    fun getRandomModel(context: Context? = null): String? {
        if (cachedRandomModel != null) return cachedRandomModel

        // 尝试从本地缓存恢复
        if (context != null) {
            cachedRandomModel = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_RANDOM_MODEL, null)
        }
        return cachedRandomModel
    }

    private fun isCacheValid(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFetch = prefs.getLong(KEY_LAST_FETCH, 0)
        return System.currentTimeMillis() - lastFetch < CACHE_TTL_MS
    }

    private fun isCacheExpired(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFetch = prefs.getLong(KEY_LAST_FETCH, 0)
        return System.currentTimeMillis() - lastFetch >= CACHE_TTL_MS * 24
    }

    private fun updateFetchTime(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_FETCH, System.currentTimeMillis()).apply()
    }

    fun clearCache(context: Context) {
        cachedKeys = emptyList()
        cachedRandomModel = null
        val file = File(context.filesDir, KEY_CACHE_FILE)
        file.delete()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pkgInfo.versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
}
