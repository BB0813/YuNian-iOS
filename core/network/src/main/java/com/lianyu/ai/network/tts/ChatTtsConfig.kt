package com.lianyu.ai.network.tts

import android.content.Context
import com.lianyu.ai.common.SecureLog

/**
 * 聊天页 TTS 模式。
 *
 * - [SILENT] 完全静音，只落文字
 * - [VOICE_BAR] 语音条：合成音频写入消息 `linkString`，单条同时展示语音条 + 文字（DB 级去重）
 * - [READ_ALOUD] 历史枚举位，已废弃；读取时映射为 [SILENT]，不再自动朗读
 *
 * 放在 core:network 以便 feature:chat 和 feature:settings 共用（feature 模块间不能互相依赖）。
 * ordinal 顺序不可改，避免 SharedPreferences 旧值错位。
 */
enum class ChatTtsMode(val displayName: String, val description: String) {
    SILENT("静音", "只显示文字，不生成语音"),
    VOICE_BAR("语音条", "单条消息同时显示语音条和文字，点击播放"),
    @Deprecated("已移除自动朗读，保留 ordinal 兼容旧配置")
    READ_ALOUD("语音朗读(已停用)", "已改为语音条入库，不再自动朗读");

    companion object {
        /** 设置页/快捷切换可选模式（不含已废弃的 READ_ALOUD） */
        val selectableModes: List<ChatTtsMode> = listOf(SILENT, VOICE_BAR)

        fun fromOrdinalSafe(value: Int): ChatTtsMode {
            val raw = entries.elementAtOrNull(value) ?: SILENT
            // 旧「朗读」配置降级为静音，避免误触发已删除的自动播放路径
            return if (raw == READ_ALOUD) SILENT else raw
        }
    }
}

/**
 * 聊天页 TTS 配置（独立于 [TtsConfig]，避免污染其 data class）。
 *
 * 持久化到 SharedPreferences `"tts_settings"`（与 TtsConfig 同名但独立 keys），
 * 不触发 Room schema 变更。
 *
 * @property mode 语音模式（静音 / 语音条）
 * @property skipParentheses 跳过括号内容（AI 内心戏），合成前清洗
 * @property autoDedup 历史字段，语音条改为消息入库后天然去重，保留读写兼容
 * @property beautify 启用音频美化（播放侧 Equalizer，部分设备不支持）
 */
data class ChatTtsConfig(
    val mode: ChatTtsMode = ChatTtsMode.SILENT,
    val skipParentheses: Boolean = false,
    val autoDedup: Boolean = true,
    val beautify: Boolean = true
) {
    companion object {
        private const val PREFS_NAME = "tts_settings"
        private const val KEY_MODE = "chat_tts_mode"
        private const val KEY_SKIP_PARENTHESES = "key_skip_parentheses"
        private const val KEY_AUTO_DEDUP = "chat_tts_auto_dedup"
        private const val KEY_BEAUTIFY = "chat_tts_beautify"

        fun fromSharedPreferences(context: Context): ChatTtsConfig {
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ChatTtsConfig(
                    mode = ChatTtsMode.fromOrdinalSafe(prefs.getInt(KEY_MODE, 0)),
                    skipParentheses = prefs.getBoolean(KEY_SKIP_PARENTHESES, false),
                    autoDedup = prefs.getBoolean(KEY_AUTO_DEDUP, true),
                    beautify = prefs.getBoolean(KEY_BEAUTIFY, true)
                )
            } catch (e: Exception) {
                SecureLog.e("ChatTtsConfig", "Failed to read prefs, using defaults", e)
                ChatTtsConfig()
            }
        }

        fun saveToSharedPreferences(context: Context, config: ChatTtsConfig) {
            try {
                // 禁止把已废弃 READ_ALOUD 写回；统一落 SILENT / VOICE_BAR
                val modeToSave = when (config.mode) {
                    ChatTtsMode.VOICE_BAR -> ChatTtsMode.VOICE_BAR
                    else -> ChatTtsMode.SILENT
                }
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putInt(KEY_MODE, modeToSave.ordinal)
                    putBoolean(KEY_SKIP_PARENTHESES, config.skipParentheses)
                    putBoolean(KEY_AUTO_DEDUP, config.autoDedup)
                    putBoolean(KEY_BEAUTIFY, config.beautify)
                    apply()
                }
            } catch (e: Exception) {
                SecureLog.e("ChatTtsConfig", "Failed to save prefs", e)
            }
        }
    }
}
