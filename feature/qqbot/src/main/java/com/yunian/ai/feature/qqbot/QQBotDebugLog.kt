package com.yunian.ai.feature.qqbot

import com.yunian.ai.feature.qqbot.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * QQ 通道的 debug 文件日志。
 *
 * **为什么需要它**：本应用的 `android.util.Log` 输出在部分机型上会被系统/壳层抑制，
 * 实测在 vivo 设备上 `adb logcat -b all` 对本应用为 0 行，因此只写 logcat 的丢弃点
 * 在真机上是**完全不可观测**的。文件日志是唯一可靠的诊断出口。
 *
 * 与 [com.yunian.ai.feature.wechat.WeChatDebugLog]、
 * [com.yunian.ai.feature.chat.ui.viewmodel.ChatDebugLog] 同形：
 * 每个 feature 模块自带一个，避免 feature→feature 依赖。
 *
 * 只在 debug 包写文件；写失败静默吞掉，绝不影响消息主链路。
 */
object QQBotDebugLog {
    private val file by lazy {
        if (!BuildConfig.DEBUG) return@lazy null
        File("/data/data/com.yunian.ai/files/qqbot_debug.log")
    }

    fun log(msg: String) {
        if (!BuildConfig.DEBUG) return
        try {
            val currentFile = file ?: return
            currentFile.parentFile?.mkdirs()
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            currentFile.appendText("$ts $msg\n")
        } catch (_: Exception) {}
    }
}
