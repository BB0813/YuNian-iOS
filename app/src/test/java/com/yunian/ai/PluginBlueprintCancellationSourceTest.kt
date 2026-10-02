package com.yunian.ai

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source regression guard only: does not execute Android startup or prove runtime recovery. */
class PluginBlueprintCancellationSourceTest {
    private val projectRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val source = File(projectRoot, "app/src/main/java/com/yunian/ai/YuNianApplication.kt").readText()

    @Test
    fun outerBlueprintGuardRethrowsCancellationBeforeRecordingFailure() {
        val call = source.substringAfter("runCatching { loadDefaultBlueprint(app) }")
            .substringBefore("applyDisabledPluginOverlay()")
        assertTrue(Regex("""^\s*\.onFailure\s*\{\s*failure\s*->\s*if \(failure is CancellationException\) throw failure\s*PluginBlueprintStatus.recordFailure\(""").containsMatchIn(call))
    }

    @Test
    fun snapshotGuardAlsoRethrowsCancellationBeforeLogging() {
        val body = source.substringAfter("private fun loadDefaultBlueprint(app: Application)")
            .substringBefore("private suspend fun applyDisabledPluginOverlay")
        assertTrue(Regex("""\.onFailure\s*\{\s*failure\s*->\s*if \(failure is CancellationException\) throw failure\s*SecureLog.w\(""").containsMatchIn(body))
    }
}
