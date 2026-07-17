package com.lianyu.ai.common

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

object PerformanceTrace {
    private val shellStarted = AtomicLong()
    private val shellAntiHookDone = AtomicLong()
    private val shellNativeInitDone = AtomicLong()
    private val shellRecoveryDone = AtomicLong()
    private val shellMemoryGuardDone = AtomicLong()
    private val shellPreflightDone = AtomicLong()
    private val securityStarted = AtomicLong()
    private val securityTinkDone = AtomicLong()
    private val securityWhiteBoxDone = AtomicLong()
    private val securityAttestationDone = AtomicLong()
    private val securitySignatureDone = AtomicLong()
    private val securityKmsDone = AtomicLong()
    private val securityIntegrityDone = AtomicLong()
    private val securityDone = AtomicLong()
    private val startupStarted = AtomicLong()
    private val startupDrawn = AtomicLong()
    private val contactsStarted = AtomicLong()
        fun startStartup(startedNanos: Long = SystemClock.elapsedRealtimeNanos()) {
            startupDrawn.set(0L)
            startupStarted.set(startedNanos)
        }

        fun markStartupDrawn() {
            startupDrawn.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
        }

    private val contactsDrawn = AtomicLong()
    private val chatStarted = AtomicLong()
    private val chatShellDrawn = AtomicLong()
    private val chatMessagesDrawn = AtomicLong()

    fun startShell() = resetAndStart(
        shellStarted,
        shellAntiHookDone,
        shellNativeInitDone,
        shellRecoveryDone,
        shellMemoryGuardDone,
        shellPreflightDone
    )

    fun markShellAntiHookDone() = mark(shellAntiHookDone)
    fun markShellNativeInitDone() = mark(shellNativeInitDone)
    fun markShellRecoveryDone() = mark(shellRecoveryDone)
    fun markShellMemoryGuardDone() = mark(shellMemoryGuardDone)
    fun markShellPreflightDone() = mark(shellPreflightDone)

    fun startSecurity() = resetAndStart(
        securityStarted,
        securityTinkDone,
        securityWhiteBoxDone,
        securityAttestationDone,
        securitySignatureDone,
        securityKmsDone,
        securityIntegrityDone,
        securityDone
    )

    fun markSecurityTinkDone() = mark(securityTinkDone)
    fun markSecurityWhiteBoxDone() = mark(securityWhiteBoxDone)
    fun markSecurityAttestationDone() = mark(securityAttestationDone)
    fun markSecuritySignatureDone() = mark(securitySignatureDone)
    fun markSecurityKmsDone() = mark(securityKmsDone)
    fun markSecurityIntegrityDone() = mark(securityIntegrityDone)
    fun markSecurityDone() = mark(securityDone)

    fun shellMetricsNanos(): Map<String, Long> = linkedMapOf(
        "shell_anti_hook" to duration(shellStarted, shellAntiHookDone),
        "shell_native_init" to duration(shellAntiHookDone, shellNativeInitDone),
        "shell_recovery_oat" to duration(shellNativeInitDone, shellRecoveryDone),
        "shell_memory_guard" to duration(shellRecoveryDone, shellMemoryGuardDone),
        "shell_preflight" to duration(shellMemoryGuardDone, shellPreflightDone),
        "shell_total" to duration(shellStarted, shellPreflightDone)
    )

    fun securityMetricsNanos(): Map<String, Long> = linkedMapOf(
        "security_tink" to duration(securityStarted, securityTinkDone),
        "security_white_box" to duration(securityTinkDone, securityWhiteBoxDone),
        "security_attestation" to duration(securityWhiteBoxDone, securityAttestationDone),
        "security_signature" to duration(securityAttestationDone, securitySignatureDone),
        "security_kms" to duration(securitySignatureDone, securityKmsDone),
        "security_integrity" to duration(securityKmsDone, securityIntegrityDone),
        "security_finalize" to duration(securityIntegrityDone, securityDone),
        "security_total" to duration(securityStarted, securityDone)
    )

    fun persistReleaseMetrics(context: Context) {
        val editor = context.getSharedPreferences(RELEASE_METRICS_PREFS, Context.MODE_PRIVATE).edit()
        (shellMetricsNanos() + securityMetricsNanos()).forEach { (name, nanos) ->
            editor.putLong(name, nanos)
        }
        editor.putLong("recorded_at_elapsed_nanos", SystemClock.elapsedRealtimeNanos())
        editor.apply()
    }

    fun startContacts() {
        contactsDrawn.set(0L)
        contactsStarted.set(SystemClock.elapsedRealtimeNanos())
    }

    fun markContactsDrawn() {
        if (contactsStarted.get() != 0L) {
            contactsDrawn.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
        }
    }

    fun startChat() {
        chatShellDrawn.set(0L)
        chatMessagesDrawn.set(0L)
        chatStarted.set(SystemClock.elapsedRealtimeNanos())
    }

    fun markChatShellDrawn() {
        if (chatStarted.get() != 0L) {
            chatShellDrawn.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
        }
    }

    fun markChatMessagesDrawn() {
        if (chatStarted.get() != 0L) {
            chatMessagesDrawn.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
        }
    }

    fun startupNanos(): Long = duration(startupStarted, startupDrawn)

    fun contactsNanos(): Long = duration(contactsStarted, contactsDrawn)

    fun chatShellNanos(): Long = duration(chatStarted, chatShellDrawn)

    fun chatMessagesNanos(): Long = duration(chatShellDrawn, chatMessagesDrawn)

    fun chatTotalNanos(): Long = duration(chatStarted, chatMessagesDrawn)

    private fun duration(start: AtomicLong, end: AtomicLong): Long {
        val startNanos = start.get()
        val endNanos = end.get()
        return if (startNanos > 0L && endNanos >= startNanos) endNanos - startNanos else 0L
    }

    private fun mark(target: AtomicLong) {
        target.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
    }

    private fun resetAndStart(start: AtomicLong, vararg stages: AtomicLong) {
        stages.forEach { it.set(0L) }
        start.set(SystemClock.elapsedRealtimeNanos())
    }

    const val RELEASE_METRICS_PREFS = "release_performance_metrics"
}