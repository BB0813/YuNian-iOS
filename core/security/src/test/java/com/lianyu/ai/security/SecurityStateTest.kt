package com.lianyu.ai.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityStateTest {
    @Test
    fun trustedSensitiveStateRequiresKmsReady() {
        SecurityState.resetForTest()
        SecurityState.markPreflightPassed(
            wbAesReady = true,
            signatureTrusted = true,
            dexTrusted = true,
            soTrusted = true,
            resourcesTrusted = true,
            payloadVerified = true,
            kmsReady = false
        )

        assertFalse(
            "Sensitive operations must not be trusted until KMS is ready.",
            SecurityState.snapshot().isTrustedForSensitiveOps
        )

        SecurityState.resetForTest()
    }

    @Test
    fun trustedSensitiveStateRequiresRuntimeReadinessAndPayloadVerification() {
        SecurityState.resetForTest()

        SecurityState.markPreflightPassed(
            wbAesReady = true,
            signatureTrusted = true,
            dexTrusted = true,
            soTrusted = true,
            resourcesTrusted = true,
            payloadVerified = false,
            kmsReady = true
        )
        assertFalse(
            "Sensitive operations must not be trusted before payload verification.",
            SecurityState.snapshot().isTrustedForSensitiveOps
        )

        SecurityState.markPreflightPassed(
            wbAesReady = true,
            signatureTrusted = true,
            dexTrusted = true,
            soTrusted = true,
            resourcesTrusted = true,
            payloadVerified = true,
            kmsReady = true
        )
        assertTrue(
            "Sensitive operations should be trusted only when every gate is ready.",
            SecurityState.snapshot().isTrustedForSensitiveOps
        )

        SecurityState.markTampered("unit-test")
        assertFalse(
            "Tamper state must revoke sensitive-operation trust immediately.",
            SecurityState.snapshot().isTrustedForSensitiveOps
        )

        SecurityState.resetForTest()
    }
}
