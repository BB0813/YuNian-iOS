package com.lianyu.ai.network

import com.lianyu.ai.security.SecurityState
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class N0Test {

    @Test
    fun intercept_failsClosedWhenSecurityStateIsTampered() {
        SecurityState.resetForTest()
        SecurityState.markTampered("unit test tamper")
        val interceptor = N0(
            signer = N0.Signer { "abc123" }
        )
        val request = Request.Builder()
            .url("https://api.lianyu.app/v1/sync")
            .get()
            .build()
        val chain = RecordingChain(request)

        val result = runCatching { interceptor.intercept(chain) }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(false, chain.proceeded)
        SecurityState.resetForTest()
    }

    @Test
    fun intercept_failsClosedWhenSignerCannotProduceSignature() {
        val interceptor = N0(
            signer = N0.Signer { null }
        )
        val request = Request.Builder()
            .url("https://api.lianyu.app/v1/sync")
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        val chain = RecordingChain(request)

        val result = runCatching { interceptor.intercept(chain) }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(false, chain.proceeded)
    }

    @Test
    fun intercept_addsSignatureWhenSignerSucceeds() {
        val interceptor = N0(
            signer = N0.Signer { "abc123" }
        )
        val request = Request.Builder()
            .url("https://api.lianyu.app/v1/sync")
            .get()
            .build()
        val chain = RecordingChain(request)

        interceptor.intercept(chain)

        assertTrue(chain.proceeded)
        assertEquals("abc123", chain.proceededRequest?.header("X-LianYu-Sig"))
    }

    @Test
    fun intercept_allowsRecoveryAfterSecurityStateIsReset() {
        SecurityState.resetForTest()
        SecurityState.markTampered("unit test")
        val interceptor = N0(signer = N0.Signer { "abc" })
        val blocked = RecordingChain(
            Request.Builder().url("https://api.lianyu.app/v1/sync").get().build()
        )
        assertTrue(runCatching { interceptor.intercept(blocked) }.exceptionOrNull() is IOException)

        SecurityState.resetForTest()
        val allowed = RecordingChain(
            Request.Builder().url("https://api.lianyu.app/v1/sync").get().build()
        )
        interceptor.intercept(allowed)
        assertTrue("Request must proceed after security state is reset", allowed.proceeded)
        SecurityState.resetForTest()
    }

    @Test
    fun intercept_thirdPartyHostsBypassSignatureEntirely() {
        // Even with a working signer, a third-party host must NOT get LianYu headers
        val interceptor = N0(signer = N0.Signer { "abc" })
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        val chain = RecordingChain(request)

        interceptor.intercept(chain)

        assertTrue("Third-party request must proceed", chain.proceeded)
        assertNull(
            "Third-party API must not receive internal signature header",
            chain.proceededRequest?.header("X-LianYu-Sig")
        )
        assertNull(
            "Third-party API must not receive internal TS header",
            chain.proceededRequest?.header("X-LianYu-Ts")
        )
        assertNull(
            "Third-party API must not receive internal client header",
            chain.proceededRequest?.header("X-LianYu-Client")
        )
    }

    @Test
    fun intercept_thirdPartyBypassWorksEvenWhenStateIsTampered() {
        // Third-party requests should still go through even if internal state is tampered
        SecurityState.resetForTest()
        SecurityState.markTampered("unit test")
        val interceptor = N0(signer = N0.Signer { "abc" })
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        val chain = RecordingChain(request)

        // Must NOT throw — third-party hosts skip the tamper check
        interceptor.intercept(chain)

        assertTrue("Third-party request must proceed even when tampered", chain.proceeded)
        SecurityState.resetForTest()
    }

    private class RecordingChain(
        private val request: Request
    ) : Interceptor.Chain {
        var proceeded: Boolean = false
            private set
        var proceededRequest: Request? = null
            private set

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceeded = true
            proceededRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }

        override fun connection(): okhttp3.Connection? = null
        override fun call(): okhttp3.Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun readTimeoutMillis(): Int = 0
        override fun writeTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
    }
}
