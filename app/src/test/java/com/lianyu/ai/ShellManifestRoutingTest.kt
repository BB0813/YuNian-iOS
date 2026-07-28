package com.lianyu.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShellManifestRoutingTest {
    private val projectRoot: File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun appManifestRoutesSystemEntryPointsThroughShellComponents() {
        val manifest = File(projectRoot, "app/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:name=\".security.SActivity\""))
        assertTrue(manifest.contains("android:name=\"com.lianyu.ai.security.SService\""))
        assertTrue(manifest.contains("android:name=\"com.lianyu.ai.security.SReceiver\""))
    }

    @Test
    fun wechatManifestRoutesWeChatEntryPointsThroughShellComponents() {
        val manifest = File(projectRoot, "feature/wechat/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:name=\"com.lianyu.ai.security.SWechatPollingService\""))
        assertTrue(manifest.contains("android:name=\"com.lianyu.ai.security.SWechatBootReceiver\""))
        // S6: proactive sync no longer uses BroadcastReceiver
        assertTrue(!manifest.contains("SWechatProactiveMessageReceiver"))
        assertTrue(!manifest.contains("SEND_PROACTIVE"))
    }
}
