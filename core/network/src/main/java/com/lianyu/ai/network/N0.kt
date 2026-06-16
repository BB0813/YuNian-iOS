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
 * N0 — adds integrity verification
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
@Deprecated("Use RequestSecurityInterceptor")
class N0(
    private val appId: String = "lianyu-1.5.1",
    private val signer: Signer = WhiteBoxAesSigner
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
                        okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
                        okhttp3.CipherSuite.TLS_AES_256_GCM_SHA384,
                        okhttp3.CipherSuite.TLS_CHACHA20_POLY1305_SHA256
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
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (!isLianYuBackend(originalRequest.url.host)) {
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

        val signature = computeRequestSignature(
            method = originalRequest.method,
            path = originalRequest.url.encodedPath,
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
        return signer.sign(payload.toByteArray())
    }

    private fun isLianYuBackend(host: String): Boolean {
        val normalized = host.lowercase()
        return normalized == "api.lianyu.app" || normalized.endsWith(".lianyu.app")
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
