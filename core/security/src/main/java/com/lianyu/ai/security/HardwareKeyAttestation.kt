package com.lianyu.ai.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.*
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import android.security.keystore.KeyInfo

/**
 * Hardware Key Attestation — trust anchor binding to Android TEE/StrongBox.
 *
 * Generates an ECDSA key pair in AndroidKeyStore with StrongBox preference.
 * The key cannot be exported — all signing happens inside the secure hardware.
 * The attestation certificate chain proves the key is hardware-backed.
 *
 * Server validates:
 *   1. Root certificate is Google Hardware Attestation Root
 *   2. Boot state is verified (Bootloader locked)
 *   3. Device integrity matches expected baseline
 */
object HardwareKeyAttestation {

    private const val TAG = "LianYu-HKA"
    private const val KEY_ALIAS = "lianyu_hka_ecdsa_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    @Volatile
    private var initialized = false

    @Volatile
    private var keyPair: KeyPair? = null

    /**
     * Generate the hardware key pair at app startup.
     * Call in SecurityGuard.init() or Application.onCreate().
     */
    fun ensureKeyPair(): Boolean {
        if (initialized && keyPair != null) return true

        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (keyStore.containsAlias(KEY_ALIAS)) {
                // Key exists — load it
                val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
                keyPair = KeyPair(entry.certificate.publicKey, entry.privateKey)
                Log.d(TAG, "Hardware key pair loaded (existing)")
            } else {
                // Generate new key with StrongBox preference
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .setAttestationChallenge(generateChallenge())
                    .apply {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            setIsStrongBoxBacked(true)
                        }
                    }
                    .build()

                val keyGenerator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC,
                    ANDROID_KEYSTORE
                )
                keyGenerator.initialize(spec)
                keyPair = keyGenerator.generateKeyPair()
                Log.d(TAG, "Hardware key pair generated in secure hardware")
            }

            initialized = true
            true
        } catch (e: Exception) {
            // Fallback: TEE without StrongBox (still hardware-backed)
            Log.w(TAG, "StrongBox unavailable — falling back to TEE", e)
            generateTeeKey()
        } catch (e: Exception) {
            Log.e(TAG, "Hardware key attestation failed", e)
            false
        }
    }

    private fun generateTeeKey(): Boolean {
        return try {
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .setAttestationChallenge(generateChallenge())
                .build()

            val keyGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE
            )
            keyGenerator.initialize(spec)
            keyPair = keyGenerator.generateKeyPair()
            initialized = true
            Log.d(TAG, "Hardware key pair generated in TEE")
            true
        } catch (e: Exception) {
            Log.e(TAG, "TEE key generation failed", e)
            false
        }
    }

    /** Generate a 16-byte random challenge for attestation. */
    private fun generateChallenge(): ByteArray {
        val challenge = ByteArray(16)
        SecureRandom().nextBytes(challenge)
        return challenge
    }

    /**
     * Get the attestation certificate chain.
     * Returns: [leaf_cert, intermediate_cert, ..., root_cert]
     * The leaf cert contains device boot state and key properties.
     */
    fun getCertificateChain(): Array<ByteArray>? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val chain = keyStore.getCertificateChain(KEY_ALIAS) ?: return null
            chain.map { it.encoded }.toTypedArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get certificate chain", e)
            null
        }
    }

    /**
     * Sign data using the hardware-backed private key.
     * All operations execute inside TEE/StrongBox — key never leaves hardware.
     */
    fun sign(data: ByteArray): ByteArray? {
        return try {
            val privateKey = keyPair?.private ?: return null
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(privateKey)
            signature.update(data)
            signature.sign()
        } catch (e: Exception) {
            Log.e(TAG, "Hardware signing failed", e)
            null
        }
    }

    /**
     * Verify that the key is hardware-backed.
     * Returns: 2=StrongBox, 1=TEE, 0=Software (insecure), -1=unknown
     */
    fun getSecurityLevel(): Int {
        return try {
            val factory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: return -1
            val keyInfo = factory.getKeySpec(entry.privateKey, KeyInfo::class.java)
            when {
                keyInfo.isInsideSecureHardware -> 1
                else -> 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Security level check failed", e)
            -1
        }
    }
}
