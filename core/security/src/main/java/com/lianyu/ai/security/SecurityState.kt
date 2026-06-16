package com.lianyu.ai.security

/**
 * Process-local security state shared by production gates and sensitive paths.
 *
 * This avoids each caller guessing whether white-box AES, APK trust, payload
 * integrity, and KMS are ready. Release-sensitive code should fail closed when
 * [isTrustedForSensitiveOps] is false.
 */
object SecurityState {
    data class Snapshot(
        val preflightPassed: Boolean = false,
        val wbAesReady: Boolean = false,
        val signatureTrusted: Boolean = false,
        val dexTrusted: Boolean = false,
        val soTrusted: Boolean = false,
        val resourcesTrusted: Boolean = false,
        val payloadVerified: Boolean = false,
        val kmsReady: Boolean = false,
        val tampered: Boolean = false,
        val reason: String? = null
    ) {
        val isTrustedForSensitiveOps: Boolean
            get() = preflightPassed &&
                wbAesReady &&
                signatureTrusted &&
                dexTrusted &&
                soTrusted &&
                resourcesTrusted &&
                payloadVerified &&
                kmsReady &&
                !tampered
    }

    @Volatile
    private var current = Snapshot()

    fun snapshot(): Snapshot = current

    fun markPreflightPassed(
        wbAesReady: Boolean,
        signatureTrusted: Boolean,
        dexTrusted: Boolean,
        soTrusted: Boolean,
        resourcesTrusted: Boolean,
        payloadVerified: Boolean,
        kmsReady: Boolean
    ) {
        current = Snapshot(
            preflightPassed = true,
            wbAesReady = wbAesReady,
            signatureTrusted = signatureTrusted,
            dexTrusted = dexTrusted,
            soTrusted = soTrusted,
            resourcesTrusted = resourcesTrusted,
            payloadVerified = payloadVerified,
            kmsReady = kmsReady,
            tampered = false,
            reason = null
        )
    }

    fun markRuntimeReady(wbAesReady: Boolean, kmsReady: Boolean) {
        val previous = current
        current = previous.copy(
            wbAesReady = previous.wbAesReady || wbAesReady,
            kmsReady = previous.kmsReady || kmsReady
        )
    }

    fun markTampered(reason: String) {
        current = current.copy(tampered = true, reason = reason)
    }

    fun resetForTest() {
        current = Snapshot()
    }
}
