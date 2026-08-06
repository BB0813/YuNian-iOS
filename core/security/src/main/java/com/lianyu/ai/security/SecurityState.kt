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

    /**
     * Client risk tiers mirroring the native zero-trust risk model
     * (zt_risk_level_t in zero-trust.h).  The tier is derived from the
     * raw threat score using configurable thresholds:
     *   0 = SAFE (score 0)          → clean device
     *   1 = LOW  (score 1-2)        → minor flags, features degrade
     *   2 = MEDIUM (score 3-5)      → suspicious, sensitive ops restricted
     *   3 = HIGH (score 6-9)        → likely compromised, sensitive ops denied
     *   4 = CRITICAL (score >= 10)  → definite breach, full lock
     */
    enum class RiskLevel(val tier: Int) {
        SAFE(0), LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4);

        companion object {
            fun fromTier(tier: Int): RiskLevel = entries.firstOrNull { it.tier == tier } ?: CRITICAL
        }
    }

    /**
     * Risk threshold for sensitive operations — the highest client risk
     * tier that may still use sensitive paths (cloud access, secret
     * decrypt, SuFlow session use).
     *
     * Tuning:
     *   SENSITIVE_OPS_MAX_RISK = RiskLevel.SAFE    → score must be 0
     *   SENSITIVE_OPS_MAX_RISK = RiskLevel.LOW     → score 0-2 allowed
     *   SENSITIVE_OPS_MAX_RISK = RiskLevel.MEDIUM  → score 0-5 allowed (permissive)
     */
    const val SENSITIVE_OPS_MAX_RISK: Int = 1 // RiskLevel.LOW — score 0-2 allowed

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
        /** Client risk tier (0-4) from the native threshold model. */
        val riskLevel: Int = RiskLevel.SAFE.tier,
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
                riskLevel <= SENSITIVE_OPS_MAX_RISK &&
                !tampered &&
                !hardAuthFailed

        val admission: Admission
            get() = when {
                hardAuthFailed -> Admission.BLOCK
                isTrustedForSensitiveOps -> Admission.ALLOW_FULL
                // MEDIUM+ risk: sensitive paths denied, local stays available.
                riskLevel > SENSITIVE_OPS_MAX_RISK -> Admission.ALLOW_LOCAL
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
     * Refresh the client risk tier from the native zero-trust threshold
     * model.  Called after zero-trust evaluation completes so sensitive
     * gates can decide against the current risk level.
     */
    fun updateRiskLevel() {
        val tier = try {
            NativeBridge.zeroTrustGetRiskLevel()
        } catch (t: Throwable) {
            RiskLevel.CRITICAL.tier // fail closed if native call throws
        }
        val newLevel = RiskLevel.fromTier(tier)
        val previous = current
        if (previous.riskLevel != newLevel.tier) {
            current = previous.copy(riskLevel = newLevel.tier)
        }
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
