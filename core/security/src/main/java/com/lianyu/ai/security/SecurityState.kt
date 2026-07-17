package com.lianyu.ai.security

/**
 * Process-local security state shared by production gates and sensitive paths.
 *
 * Trust model:
 * - Local offline business may start under [Admission.ALLOW_LOCAL] even when
 *   heuristic risk signals exist (root/hook/debug).
 * - Cryptographic hard-auth failures (signature / white-box / payload) use
 *   [Admission.BLOCK] and must not call business init.
 * - Sensitive ops (network signing, secret decrypt, SuFlow session use) require
 *   [Snapshot.isTrustedForSensitiveOps].
 */
object SecurityState {
    enum class Admission {
        /** Full trust: local + sensitive paths allowed. */
        ALLOW_FULL,
        /** Offline-first local business allowed; sensitive paths restricted. */
        ALLOW_LOCAL,
        /** Hard authentication failed — do not start business init. */
        BLOCK
    }

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
        /** True only for cryptographic / payload authentication failures. */
        val hardAuthFailed: Boolean = false,
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
                !tampered &&
                !hardAuthFailed

        val admission: Admission
            get() = when {
                hardAuthFailed -> Admission.BLOCK
                isTrustedForSensitiveOps -> Admission.ALLOW_FULL
                else -> Admission.ALLOW_LOCAL
            }
    }

    @Volatile
    private var current = Snapshot()

    fun snapshot(): Snapshot = current

    fun admission(): Admission = current.admission

    fun canStartLocalBusiness(): Boolean = current.admission != Admission.BLOCK

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
            hardAuthFailed = false,
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

    /**
     * Soft risk / heuristic signal. Local offline business may continue;
     * sensitive paths fail closed via [Snapshot.isTrustedForSensitiveOps].
     */
    fun markTampered(reason: String) {
        current = current.copy(tampered = true, reason = reason)
    }

    /**
     * Cryptographic authentication failure. Blocks business init.
     */
    fun markHardAuthFailure(reason: String) {
        current = current.copy(
            tampered = true,
            hardAuthFailed = true,
            reason = reason
        )
    }

    fun resetForTest() {
        current = Snapshot()
    }
}
