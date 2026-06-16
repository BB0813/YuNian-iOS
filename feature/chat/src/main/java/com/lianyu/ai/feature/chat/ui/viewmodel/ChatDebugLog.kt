package com.lianyu.ai.feature.chat.ui.viewmodel

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Zero-dependency debug log to file — bypasses vivo logcat filtering */
object ChatDebugLog {
    private val file by lazy { File("/data/data/com.lianyu.ai/files/chatvm_debug.log") }

    fun log(msg: String) {
        try {
            file.parentFile?.mkdirs()
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("$ts $msg\n")
        } catch (_: Exception) {}
    }
}
