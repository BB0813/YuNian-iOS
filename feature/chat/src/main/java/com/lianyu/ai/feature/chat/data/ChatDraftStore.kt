package com.lianyu.ai.feature.chat.data

import android.content.Context

/**
 * 聊天输入框草稿存储：按 companionId 持久化未发送的文本。
 *
 * 草稿只应在「用户手动删除」或「发送后」消失 —— 退出聊天页、杀进程后重新进入都要能恢复，
 * 因此不能只靠 ViewModel / remember 存内存态，必须落盘。
 *
 * 选用 SharedPreferences 而非 DataStore：每次击键都会写，`apply()` 内存同步、磁盘异步，
 * 打字后立刻退出也不丢；DataStore 高频写入会排队积压。
 */
class ChatDraftStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDraft(companionId: Long): String = prefs.getString(key(companionId), null).orEmpty()

    fun setDraft(companionId: Long, text: String) {
        val editor = prefs.edit()
        if (text.isBlank()) {
            editor.remove(key(companionId))
        } else {
            editor.putString(key(companionId), text)
        }
        editor.apply()
    }

    private fun key(companionId: Long) = "draft_$companionId"

    private companion object {
        const val PREFS_NAME = "chat_draft_store"
    }
}
