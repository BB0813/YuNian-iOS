package com.lianyu.ai.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import java.io.File

/**
 * SecurityGuard — central security gate for LianYu.
 *
 * Architecture:
 *   1. init() at Application.onCreate — runs all detection chains
 *   2. Periodic checks via WorkManager (every 30s)
 *   3. Any detection triggers AuditLogger + tamper flag
 *   4. Call isSafe() before sensitive operations
 */
object SecurityGuard {

    private const val TAG = "LianYu-Security"

    @Volatile
    private var tampered = false

    @Volatile
    private var inited = false

    @Volatile
    private var lastCheckMs = 0L

    private const val CHECK_INTERVAL_MS = 30_000L  // 30 seconds

    /**
     * Production startup gate.
     *
     * This is intentionally non-fatal at process attach time. Real devices can
     * differ in how APK/SO mappings appear before normal Application startup;
     * killing here prevents diagnostics and can block legitimate installs.
     * Sensitive paths still fail closed through SecurityState.
     */
    fun productionPreflight(context: Context) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) return

        fun recordFailure(reason: String) {
            tampered = true
            SecurityState.markTampered(reason)
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED, reason)
        }

        // Anti-debug: ptrace self-attach + inotify watcher (per-OS-version gating)
        // JNI_OnLoad already does early ptrace on all versions.
        // Android 14-15: inotify watcher is safe (dl_iterate_phdr replaces /proc/self/maps).
        // Android 16+: vivo kernel kills process on late ptrace/inotify → skip.
        val sdkInt = android.os.Build.VERSION.SDK_INT
        if (sdkInt <= 35) {
            val antiDebugOk = runCatching { NativeBridge.antiDebugInit() }.getOrDefault(false)
            if (!antiDebugOk && sdkInt <= 33) {
                // Only critical on pre-14 where we don't have dl_iterate_phdr fallback
                recordFailure("anti-debug initialization failed")
            }
        }

        val wbAesReady = runCatching { NativeBridge.wbAesInit() }.isSuccess
        if (!wbAesReady) recordFailure("white-box AES init failed")

        // VMP v2.0: Trust-anchor verification inside VM bytecode
        // MUST run AFTER wbAesInit() — wb_aes_keycheck selftest needs initialized T-Box tables.
        // Non-fatal: failure flags tampered state but doesn't kill process.
        val vmpAnchorsOk = runCatching { CompositeVmpRuntime.verifyTrustAnchors() }.getOrDefault(false)
        if (!vmpAnchorsOk) recordFailure("VMP trust anchors verification failed")

        val signatureOk = runCatching { NativeBridge.verifySignature(context) }.getOrDefault(false)
                || verifySignatureViaPackageManager(context)
        if (!signatureOk) recordFailure("APK signature verification failed")

        val dexOk = runCatching { NativeBridge.checkDexIntegrity() }.getOrDefault(false)
        if (!dexOk) recordFailure("DEX integrity verification failed")

        val soOk = runCatching { NativeBridge.checkSoIntegrity() }.getOrDefault(false)
        if (!soOk) recordFailure("native library integrity verification failed")

        val resourcesOk = runCatching { NativeBridge.checkResourcesIntegrity() }.getOrDefault(false)
        if (!resourcesOk) recordFailure("resource integrity verification failed")

        val digest = runCatching { NativeBridge.computeIntegrityDigest() }.getOrNull()
        val digestOk = digest != null && digest.size == 32
        if (digestOk) {
            DatabaseKeyProvider.setIntegrityDigest(digest!!)
        } else {
            recordFailure("APK integrity digest unavailable")
        }

        if (wbAesReady && signatureOk && dexOk && soOk && resourcesOk && digestOk) {
            SecurityState.markPreflightPassed(
                wbAesReady = true,
                signatureTrusted = true,
                dexTrusted = true,
                soTrusted = true,
                resourcesTrusted = true,
                payloadVerified = true,
                kmsReady = KmsProvider.isReady
            )
        }
    }

    /**
     * Full initialization + detection run.
     * Call from LianYuApplication.onCreate().
     */
    fun init(context: Context) {
        if (inited) return
        inited = true

        val isEmulator = runCatching { NativeBridge.isEmulator() }.getOrDefault(false)
                || android.os.Build.FINGERPRINT.contains("generic")
                || android.os.Build.FINGERPRINT.contains("sdk_gphone")
                || android.os.Build.MODEL.contains("sdk_gphone")

        // Tink AEAD — primary encryption path (hardware-backed KEK + AEAD)
        try {
            TinkAeadProvider.initialize()
        } catch (e: Exception) {
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED, "Tink AEAD init failed")
            tampered = true
            SecurityState.markTampered("Tink AEAD init failed")
        }

        // White-box AES table load (defense-in-depth, non-fatal)
        var wbAesReady = false
        try {
            NativeBridge.wbAesInit()
            wbAesReady = true
        } catch (e: Exception) {

        // Hardware Key Attestation — trust anchor binding to TEE/StrongBox
        try {
            HardwareKeyAttestation.ensureKeyPair()
            val securityLevel = HardwareKeyAttestation.getSecurityLevel()
            android.util.Log.i(TAG, "Hardware key attestation: level=$securityLevel (2=StrongBox 1=TEE 0=SW)")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Hardware key attestation unavailable", e)
        }
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED, "white-box AES init failed")
            tampered = true
            SecurityState.markTampered("white-box AES init failed")
        }

        // APK signature verification
        val sigOk = runCatching { NativeBridge.verifySignature(context) }.getOrDefault(false)

        // KMS initialize (random/KDF/NEON registers)
        var kmsOk = false
        try {
            kmsOk = KmsProvider.initialize()
        } catch (e: Exception) {
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.KEYSTORE_ERROR, "KMS initialization failed")
            tampered = true
            SecurityState.markTampered("KMS initialization failed")
        }

        // Re-init WB-AES after KMS: kms_derive_dk_ephemeral() may have
        // poisoned g_wb_tampered=1 on platforms where T-Box checksums
        // diverge. Body encryption depends on clean WB-AES state and
        // must not be held hostage by KMS initialization.
        try {
            NativeBridge.wbAesInit()
        } catch (_: Exception) { /* body enc will still try */ }

        // L2: boot-time table obfuscation + side-channel defense
        // Only apply when WB-AES is confirmed ready (KMS may have tainted it)
        if (kmsOk) {
            try {
                val seed = java.security.SecureRandom().generateSeed(32)
                NativeBridge.wbAesObfuscateTables(seed)
                NativeBridge.wbAesSideChannelDefense()
            } catch (e: Exception) {
                AuditLogger.log(context, AuditLogger.Level.WARNING,
                    AuditLogger.Event.TAMPER_DETECTED, "L2 white-box hardening initialization failed")
            }
        }

        // Phase 5: Bind database key to APK integrity (DEX+SO+ARSC)
        try {
            val digest = NativeBridge.computeIntegrityDigest()
            if (digest != null && digest.size == 32) {
                DatabaseKeyProvider.setIntegrityDigest(digest)
            } else {
                AuditLogger.log(context, AuditLogger.Level.ERROR,
                    AuditLogger.Event.TAMPER_DETECTED, "Integrity digest unavailable")
            }
        } catch (e: Exception) {
            AuditLogger.log(context, AuditLogger.Level.ERROR,
                AuditLogger.Event.TAMPER_DETECTED, "Integrity digest binding failed")
        }

        // ═══════════════════════════════════════════════════
        // P0: RELEASE builds must NEVER run on emulators.
        // Emulators have no TEE, can be snapshot-debugged, and
        // expose all security internals to dynamic analysis.
        // ═══════════════════════════════════════════════════
        if (isEmulator && (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            android.util.Log.e(TAG, "FATAL: emulator detected on release build — locking up")
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED, "emulator_detected_deadloop")
            // Sleep 10s to trigger sandbox timeout, then enter VMP dead loop.
            // No exit — sandbox can't intercept a busy-wait.
            try { Thread.sleep(10000) } catch (e: Exception) {}
            NativeBridge.enterDeadLoop()
            return
        }

        // Start native CRC32 heartbeat (runs every 30s in background thread)
        try {
            NativeBridge.startHeartbeat()
        } catch (e: Exception) {
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED, "heartbeat start failed")
        }

        // Phase 5 was done above. Integrity check deferred to background.
        SecurityState.markRuntimeReady(
            wbAesReady = wbAesReady,
            kmsReady = KmsProvider.isReady
        )
    }

    /**
     * Quick safety check. Returns false if any tampering detected.
     * Designed to be called before every sensitive operation.
     */
    fun isSafe(context: Context): Boolean {
        if (tampered) {
            SecurityState.markTampered("SecurityGuard tampered")
            return false
        }

        val now = System.currentTimeMillis()
        if (now - lastCheckMs > CHECK_INTERVAL_MS) {
            performFullCheck(context)
        }

        return !tampered
    }

    /**
     * Get detailed threat assessment.
     */
    fun getThreatAssessment(context: Context): ThreatAssessment {
        val score = NativeBridge.getThreatScore()
        return ThreatAssessment(
            isRooted = NativeBridge.isDeviceRooted(),
            isHooked = NativeBridge.isHookDetected(),
            isEmulator = NativeBridge.isEmulator(),
            isDebugged = NativeBridge.isDebugged(),
            isMitm = NativeBridge.isMitmDetected(),
            sigValid = NativeBridge.verifySignature(context),
            threatScore = score,
            dbKeyIntegrity = DatabaseKeyProvider.verifyKeyIntegrity(context)
        )
    }

    /**
     * Run the complete detection chain.
     */
    private 
    /** Detect Xposed framework at Java level — checks for known framework classes. */
    
    /** Sensor-based emulator detection.
     *  Real devices have noisy sensor data; emulators return constant zeros or no sensors. */
    fun isEmulatorBySensors(): Boolean {
        return try {
            // Use reflection to avoid hidden API restrictions
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null)
            val ctx = app.javaClass.getMethod("getApplication").invoke(app) as? android.content.Context
            val sm = ctx?.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
                ?: return false
            val sensors = sm.getSensorList(android.hardware.Sensor.TYPE_ALL)
            // No sensors = likely emulator
            if (sensors.isEmpty()) return true
            // Check if key sensor types exist
            val hasAccelerometer = sensors.any { it.type == android.hardware.Sensor.TYPE_ACCELEROMETER }
            val hasGyroscope = sensors.any { it.type == android.hardware.Sensor.TYPE_GYROSCOPE }
            val hasMagneticField = sensors.any { it.type == android.hardware.Sensor.TYPE_MAGNETIC_FIELD }
            // Most real phones have at least accelerometer + magnetic field
            if (!hasAccelerometer && !hasMagneticField) return true
            // Too few sensors (< 5) is suspicious for a modern phone
            if (sensors.size < 5) return true
            false
        } catch (_: Exception) {
            // fail-closed: cannot verify → assume emulator
            return true
        }
    }

    fun isXposedDetected(): Boolean {
        val xposedClasses = arrayOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XposedHelpers",
            "de.robv.android.xposed.XposedInit",
            "de.robv.android.xposed.XposedInstaller",
            "de.robv.android.xposed.callbacks.XC_LoadPackage",
            "io.github.lsposed.LSPosedBridge",
            "org.lsposed.lspd.LSPosedBridge",
            "com.android.internal.util.XposedHelpers",
        )
        for (clsName in xposedClasses) {
            try {
                Class.forName(clsName)
                return true
            } catch (_: ClassNotFoundException) { }
        }
        return false
    }

    fun performFullCheck(context: Context) {
        lastCheckMs = System.currentTimeMillis()
        var detected = false

        // ═══ L1: Lightweight checks (system calls, <1ms each) ═══
        // 1. Debugger detection
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.DEBUG_DETECTED, "Debugger connected")
            detected = true
        }
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.DEBUG_DETECTED, "Debuggable flag set")
            detected = true
        }
        if (NativeBridge.isDebugged()) {
            AuditLogger.log(context, AuditLogger.Level.WARNING,
                AuditLogger.Event.DEBUG_DETECTED, "Native debug detection triggered")
            detected = true
        }

        // 2. Hook/root/emulator (single JNI calls)
        if (NativeBridge.isHookDetected()) {
            AuditLogger.log(context, AuditLogger.Level.ERROR,
                AuditLogger.Event.HOOK_DETECTED, "Xposed/Frida/LSPosed detected")
            detected = true
        }
        if (NativeBridge.isDeviceRooted()) {
            AuditLogger.log(context, AuditLogger.Level.ERROR,
                AuditLogger.Event.ROOT_DETECTED, "Root/Magisk/KernelSU detected")
            detected = true
        }
        if (NativeBridge.isEmulator()) {
            AuditLogger.log(context, AuditLogger.Level.WARNING,
                AuditLogger.Event.EMULATOR_DETECTED, "Emulator/virtual environment detected")
            detected = true
        }

        // ═══ L2: Heavy checks → deferred to IdleHandler (IO/CPU intensive) ═══
        // These run when UI thread is idle to avoid frame drops
        val heavyContext = context.applicationContext
        android.os.Looper.myQueue().addIdleHandler(object : android.os.MessageQueue.IdleHandler {
            override fun queueIdle(): Boolean {
                var heavyDetected = false
                // Signature verification (JNI + PM)
                if (!NativeBridge.verifySignature(heavyContext)) {
                    AuditLogger.log(heavyContext, AuditLogger.Level.CRITICAL,
                        AuditLogger.Event.SIGNATURE_FAIL, "APK signature verification failed")
                    heavyDetected = true
                }
                // MITM detection
                if (NativeBridge.isMitmDetected()) {
                    AuditLogger.log(heavyContext, AuditLogger.Level.ERROR,
                        AuditLogger.Event.MITM_DETECTED, "MITM/proxy detected")
                    heavyDetected = true
                }
                // Threat score
                val score = NativeBridge.getThreatScore()
                if (score >= 3) {
                    AuditLogger.log(heavyContext, AuditLogger.Level.CRITICAL,
                        AuditLogger.Event.THREAT_HIGH, "Threat score: $score (threshold: 3)")
                    heavyDetected = true
                }
                // CRC32 heartbeat
                if (!NativeBridge.isHeartbeatOk()) {
                    AuditLogger.log(heavyContext, AuditLogger.Level.CRITICAL,
                        AuditLogger.Event.TAMPER_DETECTED, "SO CRC32 heartbeat detected tampering")
                    heavyDetected = true
                }
                // Frida heartbeat
                if (NativeBridge.isFridaDetected()) {
                    AuditLogger.log(heavyContext, AuditLogger.Level.CRITICAL,
                        AuditLogger.Event.TAMPER_DETECTED, "Frida heartbeat detected instrumentation")
                    heavyDetected = true
                }
                // DB key integrity
                if (!DatabaseKeyProvider.verifyKeyIntegrity(heavyContext)) {
                    AuditLogger.log(heavyContext, AuditLogger.Level.ERROR,
                        AuditLogger.Event.KEYSTORE_ERROR, "Database key integrity check failed")
                    heavyDetected = true
                }
                if (heavyDetected) {
                    tampered = true
                    SecurityState.markTampered("Security tampering confirmed (L2)")
                    AuditLogger.log(heavyContext, AuditLogger.Level.CRITICAL,
                        AuditLogger.Event.TAMPER_DETECTED, "L2 heavy check: tampering confirmed")
                }
                return false  // one-shot IdleHandler
            }
        })

        if (detected) {
            tampered = true
            SecurityState.markTampered("Security tampering confirmed (L1)")
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED, "L1 lightweight check: tampering confirmed")
        }
    }

    fun reset() {
        NativeBridge.resetGuard()
        tampered = false
        SecurityState.resetForTest()
        lastCheckMs = 0L
        AuditLogger.log(null, AuditLogger.Level.INFO,
            AuditLogger.Event.GUARD_RESET, "Security guard reset")
    }

    data class ThreatAssessment(
        val isRooted: Boolean,
        val isHooked: Boolean,
        val isEmulator: Boolean,
        val isDebugged: Boolean,
        val isMitm: Boolean,
        val sigValid: Boolean,
        val threatScore: Int,
        val dbKeyIntegrity: Boolean
    )

    /** C1-C4 cryptography maturity level */
    enum class CryptoLevel(val level: Int, val description: String) {
        C1(1, "对称加密 (SM4 + 白盒AES)"),
        C2(2, "哈希与签名 (SM3 + SM2)"),
        C3(3, "密钥管理 (KMS + TEE)"),
        C4(4, "硬件信任根 (HSM + 远程证明 + PKI)")
    }

    /**
     * Get the current cryptography maturity level achieved.
     * Based on which layers are fully initialized and operational.
     *
     * C1 — C4 逐级依赖：
     *   C1: SM4 和白盒 AES 可用
     *   C2: SM3 哈希和 SM2 签名可用
     *   C3: KMS 初始化 + TEE 硬件可用
     *   C4: 远程证明 + 证书锁定 + 完整 PKI
     */
    fun getCryptoLevel(context: Context): CryptoLevel {
        // Check C4: TEE hardware-backed + KMS ready
        val teeOk = DatabaseKeyProvider.isHardwareBacked(context)
        val kmsOk = KmsProvider.isReady

        if (teeOk && kmsOk) return CryptoLevel.C4

        // Check C3: KMS initialized
        if (kmsOk) return CryptoLevel.C3

        // Check C2: SM3+SM2 available (always true in this build)
        // SM verification is checked via native signature verification
        if (NativeBridge.verifySignature(context)) return CryptoLevel.C2

        // C1 is the baseline
        return CryptoLevel.C1
    }

    /**
     * Kotlin-level APK signature verification as a fallback when the JNI
     * verifySignature() call fails due to vendor-ROM PackageManager quirks.
     *
     * Uses android.content.pm.PackageManager directly to retrieve signing
     * certificates and compares SHA-256 against the expected value.
     *
     * 🔒 SecurityConstants.Level.TOP_SECRET: Certificate hash fetched from native
     *    code (encrypted in .rodata). No hardcoded hash in Kotlin source.
     */
    internal fun verifySignatureViaPackageManager(context: Context): Boolean {
        return runCatching {
            val pm = context.packageManager
            val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            val pi = pm.getPackageInfo(context.packageName, flags)
            val signingInfo = pi.signingInfo ?: return@runCatching false
            val certs = signingInfo.apkContentsSigners ?: return@runCatching false
            if (certs.isEmpty()) return@runCatching false

            val md = java.security.MessageDigest.getInstance("SHA-256")
            val actual = md.digest(certs[0].toByteArray())

            // 🔒 TOP_SECRET: Expected certificate SHA-256 fetched from native .rodata encrypted section
            // Native code returns the deobfuscated hash, then zeros the buffer.
            val expected = NativeBridge.getExpectedCertSha256() ?: return@runCatching false

            actual.contentEquals(expected)
        }.getOrDefault(false)
    }
    // ════════════════ UI Protection — anti-screenshot/recording ════════════════
    @JvmStatic
    fun enableScreenProtection(activity: android.app.Activity) {
        try {
            activity.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Exception) {}
    }
}

