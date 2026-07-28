package com.lianyu.ai.feature.chat.voice

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.network.tts.ChatTtsConfig
import com.lianyu.ai.network.tts.ChatTtsMode
import com.lianyu.ai.network.tts.TtsService
import com.lianyu.ai.network.tts.TtsTextCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 聊天页 TTS 控制器（语音条数据库模式）。
 *
 * 产品形态：
 * - 不再自动朗读（已移除 READ_ALOUD 队列播放）
 * - [VOICE_BAR]：合成 → 持久化到 filesDir → 由消息 `linkString` 引用
 * - 同一条 AI 消息只写一次，重进聊天不会重复合成（架构去重）
 *
 * @param context 应用上下文（用于 filesDir 持久化）
 * @param ttsService TTS 合成服务
 * @param scope 应用级 CoroutineScope（保留参数兼容构造方）
 * @param configProvider 配置提供者
 * @param callActiveProvider 通话激活时跳过合成，避免抢设备
 */
class ChatTtsController(
    private val context: Context,
    private val ttsService: TtsService,
    @Suppress("UNUSED_PARAMETER") private val scope: CoroutineScope,
    private val configProvider: () -> ChatTtsConfig,
    private val callActiveProvider: () -> Boolean
) {
    private val _state = MutableStateFlow(ChatTtsState.IDLE)
    val state: StateFlow<ChatTtsState> = _state.asStateFlow()

    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()

    /**
     * 仅合成并持久化音频，返回 [VoiceBarAudio]；不自动播放。
     * 用于 [ChatTtsMode.VOICE_BAR]：写入 ChatMessage.linkString + type=VOICE，
     * content 保留原文，UI 同时展示语音条与文字。
     */
    suspend fun synthesizeOnly(text: String): VoiceBarAudio? {
        if (callActiveProvider()) {
            SecureLog.d(TAG, "synthesizeOnly skipped: voice call active")
            return null
        }
        val cfg = configProvider()
        if (cfg.mode != ChatTtsMode.VOICE_BAR) return null
        val cleaned = TtsTextCleaner.clean(text, cfg.skipParentheses)
        if (cleaned.isBlank()) return null
        return try {
            val tempPath = withTimeoutOrNull(TimeoutBudgets.TTS_SYNTH_MS) {
                ttsService.synthesize(cleaned)
            } ?: return null
            val durablePath = persistVoiceBarFile(File(tempPath)) ?: return null
            val durationMs = probeDurationMs(durablePath)
            VoiceBarAudio(path = durablePath, durationMs = durationMs)
        } catch (e: Exception) {
            SecureLog.e(TAG, "synthesizeOnly failed", e)
            null
        }
    }

    /**
     * 将 cache 临时文件复制到 filesDir，避免系统清缓存后语音条失效。
     * 合成侧仍写 cache；入库前必须落盘。
     */
    private suspend fun persistVoiceBarFile(temp: File): String? = withContext(Dispatchers.IO) {
        if (!temp.exists() || temp.length() <= 0L) return@withContext null
        try {
            val dir = File(context.filesDir, VOICE_BAR_DIR)
            if (!dir.exists()) dir.mkdirs()
            val ext = temp.extension.ifBlank { "mp3" }
            val dest = File(dir, "vb_${System.currentTimeMillis()}_${temp.nameWithoutExtension}.$ext")
            temp.copyTo(dest, overwrite = true)
            // 临时文件可删；持久副本由消息 linkString 持有
            runCatching { temp.delete() }
            dest.absolutePath
        } catch (e: Exception) {
            SecureLog.e(TAG, "persistVoiceBarFile failed", e)
            // 降级：仍返回临时路径，至少本会话可播
            temp.takeIf { it.exists() }?.absolutePath
        }
    }

    private fun probeDurationMs(path: String): Long? {
        // 优先 MediaMetadataRetriever；失败再试 MediaPlayer
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                raw?.toLongOrNull()?.takeIf { it > 0L }
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()?.let { return it }

        return runCatching {
            val player = MediaPlayer()
            try {
                player.setDataSource(path)
                player.prepare()
                player.duration.toLong().takeIf { it > 0L }
            } finally {
                runCatching { player.release() }
            }
        }.getOrNull()
    }

    /** 停止（兼容旧调用；语音条模式下无队列可停） */
    fun stop() {
        _state.value = ChatTtsState.IDLE
        _currentText.value = ""
    }

    /** 兼容旧调用：内存去重已废弃，空实现 */
    fun clearSeen() = Unit

    companion object {
        private const val TAG = "ChatTtsController"
        private const val VOICE_BAR_DIR = "tts_voice_bars"
    }
}

/** 语音条合成结果：持久路径 + 可选时长 */
data class VoiceBarAudio(
    val path: String,
    val durationMs: Long?
)

/** 朗读状态（保留枚举兼容 UI 收集；语音条模式恒为 IDLE） */
enum class ChatTtsState {
    IDLE,
    SPEAKING
}
