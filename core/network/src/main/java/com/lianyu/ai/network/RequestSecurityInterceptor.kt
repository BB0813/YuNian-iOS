package com.lianyu.ai.network

import com.lianyu.ai.security.NativeBridge
import com.lianyu.ai.security.SecurityState
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest

/**
 * RequestSecurityInterceptor — adds integrity verification
 * to all outgoing API requests.
 *
 * Functions:
 *   1. Native white-box-AES-backed request signing
 *   2. Anti-replay: timestamp + nonce per request
 *   3. TLS/SSL configuration enforcement
 *
 * Production rule: signing is fail-closed. If the native white-box path cannot
 * produce a signature, the request must not be sent.
 */
class RequestSecurityInterceptor(
    private val appId: String = "lianyu-1.5.1",
    private val signer: Signer = WhiteBoxAesSigner,
    private val shouldSignRequest: (okhttp3.Request) -> Boolean = { true }
) : Interceptor {

    companion object {
        /**
         * Enforce TLS security on an OkHttpClient builder.
         * Limits to TLS 1.2+ and strong cipher suites.
         */
        fun enforceTls(builder: OkHttpClient.Builder) {
            try {
                val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
                    .tlsVersions(okhttp3.TlsVersion.TLS_1_2, okhttp3.TlsVersion.TLS_1_3)
                    .cipherSuites(
                        // TLS 1.3
                        okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
                        okhttp3.CipherSuite.TLS_AES_256_GCM_SHA384,
                        okhttp3.CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                        // TLS 1.2
                        okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                        okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                        okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                        okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                        okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                        okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
                    )
                    .build()
                builder.connectionSpecs(listOf(tlsSpec))
            } catch (_: Exception) {
                // Keep platform defaults if the restricted TLS spec is unavailable.
            }
        }

        private fun padToBlock(data: ByteArray): ByteArray {
            val blockSize = 16
            val paddedSize = ((data.size + blockSize - 1) / blockSize) * blockSize
            return data.copyOf(paddedSize)
        }
    }

    fun interface Signer {
        fun sign(payload: ByteArray): String?
    }

    private object WhiteBoxAesSigner : Signer {
        override fun sign(payload: ByteArray): String? {
            val encrypted = NativeBridge.wbAesEncrypt(padToBlock(payload)) ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(encrypted)
            // Use full SHA-256 hex (64 chars) instead of truncated 16-char value
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    // Prefer AndroidKeyStore HMAC signing when available
    private object AndroidKeystoreHmacSigner : Signer {
        private const val KEY_ALIAS = "lianyu_request_hmac_key"

        private fun getOrCreateKey(): javax.crypto.SecretKey? {
            try {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val existing = ks.getEntry(KEY_ALIAS, null) as? java.security.KeyStore.SecretKeyEntry
                if (existing != null) return existing.secretKey

                val kf = javax.crypto.KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                    "AndroidKeyStore"
                )
                val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN or android.security.keystore.KeyProperties.PURPOSE_VERIFY
                )
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .setKeySize(256)
                    .build()
                kf.init(spec)
                return kf.generateKey()
            } catch (e: Exception) {
                return null
            }
        }

        override fun sign(payload: ByteArray): String? {
            val key = getOrCreateKey() ?: return null
            return try {
                val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                mac.init(key)
                val digest = mac.doFinal(payload)
                digest.joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (!shouldSignRequest(originalRequest)) {
            return chain.proceed(originalRequest)
        }

        val state = SecurityState.snapshot()
        if (state.tampered) {
            throw IOException("security state is tampered")
        }
        val requestBuilder = originalRequest.newBuilder()

        val timestamp = System.currentTimeMillis() / 1000
        val nonce = generateNonce()
        requestBuilder.header("X-LianYu-Ts", timestamp.toString())
        requestBuilder.header("X-LianYu-Nonce", nonce)

        val path = originalRequest.url.encodedPath +
            originalRequest.url.encodedQuery?.let { "?$it" }.orEmpty()

        val signature = computeRequestSignature(
            method = originalRequest.method,
            path = path,
            timestamp = timestamp,
            nonce = nonce
        ) ?: throw IOException("request signing unavailable")
        requestBuilder.header("X-LianYu-Sig", signature)
        requestBuilder.header("X-LianYu-Client", appId)

        return chain.proceed(requestBuilder.build())
    }

    private fun computeRequestSignature(
        method: String,
        path: String,
        timestamp: Long,
        nonce: String
    ): String? {
        val payload = "$method\n$path\n$timestamp\n$nonce"
        // Try Android Keystore HMAC signer first (stronger key protection), fallback to configured signer
        val payloadBytes = payload.toByteArray()
        return AndroidKeystoreHmacSigner.sign(payloadBytes) ?: signer.sign(payloadBytes)
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(12)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
