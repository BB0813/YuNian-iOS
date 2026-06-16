package com.lianyu.ai.security

import android.content.Context
import java.io.File

/**
 * OatDisabler — Prevents ART from compiling extracted DEX to OAT.
 *
 * Strategy:
 *   1. Set dalvik.vm.dex2oat-filter=interpret-only (via native __system_property_set)
 *   2. Delete existing .oat/.vdex files for our package
 *   3. Mark APK as non-compilable via reflection
 *   4. Monitor and delete any new .oat files
 */
object OatDisabler {

    private var monitorThread: Thread? = null

    fun disable(context: Context) {
        // 1. Set system property (via reflection — may not work on all devices)
        try {
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("set", String::class.java, String::class.java)
                .invoke(null, "dalvik.vm.dex2oat-filter", "interpret-only")
        } catch (_: Exception) { }

        // 2. Delete existing compile artifacts
        deleteOatFiles(context)

        // 3. Start monitor thread
        startOatMonitor(context)
    }

    private fun deleteOatFiles(context: Context) {
        val packageName = context.packageName
        val paths = listOf(
            "/data/dalvik-cache/arm64",
            "/data/dalvik-cache/arm",
            context.codeCacheDir?.absolutePath ?: return
        )
        for (base in paths) {
            val dir = File(base)
            dir.listFiles()?.filter {
                it.name.contains(packageName) &&
                (it.name.endsWith(".oat") || it.name.endsWith(".vdex") || it.name.endsWith(".art"))
            }?.forEach { it.delete() }
        }
    }

    private fun startOatMonitor(context: Context) {
        monitorThread = Thread({
            while (true) {
                Thread.sleep(30_000)
                deleteOatFiles(context)
            }
        }, "oat-monitor").apply {
            isDaemon = true
            start()
        }
    }

    // dex2oat filter set via SystemProperties reflection (no JNI needed)
}
