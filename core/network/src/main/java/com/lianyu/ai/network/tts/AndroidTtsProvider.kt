package com.lianyu.ai.network.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.lianyu.ai.common.SecureLog
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale

class AndroidTtsProvider : TtsProviderInterface {
    private var tts: TextToSpeech? = null
    private val initLock = Any()

    override suspend fun synthesize(context: Context, text: String, voiceId: String?): String? {
        val t = tts ?: return null
        val deferred = CompletableDeferred<Unit>()

        try {
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { deferred.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { deferred.complete(Unit) }
            })
            t.setSpeechRate(1.0f)
            t.setPitch(1.0f)
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
            deferred.await()
        } catch (e: Exception) {
            SecureLog.e("AndroidTTS", "speak error", e)
        }
        return null
    }

    override fun getVoices(): List<TtsVoice> = listOf(
        TtsVoice("default", "系统默认语音", "Female", "zh", "使用系统内置TTS引擎")
    )

    override suspend fun testConnection(): Boolean = true

    fun isInitialized(): Boolean = tts != null

    fun initialize(context: Context): Boolean {
        var result = false
        synchronized(initLock) {
            if (tts != null) { result = true; return@synchronized }
            try {
                var success = false
                val deferred = CompletableDeferred<Unit>()
                tts = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) success = true
                    deferred.complete(Unit)
                }
                runCatching { kotlinx.coroutines.runBlocking { deferred.await() } }
                if (success && tts != null) {
                    tts?.language = Locale.CHINESE
                    result = true
                } else {
                    tts?.shutdown()
                    tts = null
                    result = false
                }
            } catch (e: Exception) {
                tts?.shutdown()
                tts = null
                SecureLog.e("AndroidTTS", "init failed", e)
                result = false
            }
        }
        return result
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun release() {
        synchronized(initLock) {
            tts?.shutdown()
            tts = null
        }
    }

    companion object {
        const val PROVIDER_ID = "android_tts"
    }
}
